package com.lstec.jvm.hash;

import org.openjdk.jmh.annotations.CompilerControl;

import static it.unimi.dsi.fastutil.HashCommon.arraySize;
import static it.unimi.dsi.fastutil.HashCommon.murmurHash3;

/**
 * Radix partitioning with bounded memory, so it stays a streaming aggregation.
 * <p>
 * {@link RadixLongCountHashTable} buffers the whole input, which is what makes its probes
 * cache local. This keeps the same idea but buffers only a fixed window: the table is split
 * into partitions whose sub-tables each fit in cache, incoming values are appended to a
 * per-partition window buffer, and when one buffer fills, just that partition is drained into
 * its sub-table. Memory is the window plus the table and does not depend on how many rows
 * arrive.
 * <p>
 * Draining a partition pulls its sub-table into cache, so that cost is amortised over the rows
 * the buffer held. Misses per row are roughly
 * {@code (sub-table cache lines) / (window rows per partition)}, which is why the window has to
 * hold on the order of as many rows as the table has entries - the product is invariant under
 * how finely the table is split, so splitting it more finely does not buy a smaller window.
 * <p>
 * The partition comes from the high bits of the hash and the slot within a sub-table from the
 * low bits, so the two are independent. Like the other flat tables this one never grows.
 */
public class WindowedRadixLongCountHashTable
        implements LongCountHashTable
{
    private static final float FILL_RATIO = 0.75f;
    private static final int PROBE_BATCH_SIZE = 64;
    /** A sub-table of 16k entries is 256 KB, small enough to stay in L2 while a partition drains. */
    public static final int DEFAULT_SUB_TABLE_ENTRIES = 16 * 1024;
    /** Two rows per sub-table entry is eight rows per sub-table cache line, so ~0.125 misses per row. */
    public static final int DEFAULT_WINDOW_ROWS_PER_PARTITION = 32 * 1024;

    private final long[] table;
    private final int partitionCount;
    private final int partitionShift;
    private final int partitionMask;
    private final int subPositionMask;
    private final int tableSlotMask;
    private final int partitionStride;

    private final long[] window;
    private final int[] windowSize;
    private final int windowRowsPerPartition;

    private final int[] slots = new int[PROBE_BATCH_SIZE];

    private long prefetchSink;
    private int zeroCount;
    private int hashCollisions;
    private int entryCount;

    public WindowedRadixLongCountHashTable(int expectedSize)
    {
        this(expectedSize, DEFAULT_SUB_TABLE_ENTRIES, DEFAULT_WINDOW_ROWS_PER_PARTITION);
    }

    public WindowedRadixLongCountHashTable(int expectedSize, int subTableEntries, int windowRowsPerPartition)
    {
        int totalCapacity = arraySize(expectedSize, FILL_RATIO);
        int subCapacity = Math.min(totalCapacity, Integer.highestOneBit(subTableEntries));
        partitionCount = totalCapacity / subCapacity;
        partitionMask = partitionCount - 1;
        partitionShift = 64 - Integer.numberOfTrailingZeros(partitionCount);
        partitionStride = subCapacity * 2;
        subPositionMask = subCapacity - 1;
        table = new long[totalCapacity * 2]; // value + count
        tableSlotMask = table.length - 1;

        this.windowRowsPerPartition = Math.max(PROBE_BATCH_SIZE, windowRowsPerPartition);
        window = new long[partitionCount * this.windowRowsPerPartition];
        windowSize = new int[partitionCount];
    }

    @Override
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public void putBlock(LongAraayBlock block)
    {
        long[] values = block.getValues();
        int positionCount = block.getPositionCount();
        long[] window = this.window;
        int[] windowSize = this.windowSize;
        int windowRowsPerPartition = this.windowRowsPerPartition;
        for (int i = 0; i < positionCount; i++) {
            long value = values[i];
            if (value == 0) {
                zeroCount++;
                continue;
            }
            // high bits pick the partition, low bits pick the slot when it drains
            int partition = (int) (murmurHash3(value) >>> partitionShift) & partitionMask;
            int size = windowSize[partition];
            window[partition * windowRowsPerPartition + size] = value;
            size++;
            windowSize[partition] = size;
            if (size == windowRowsPerPartition) {
                drainPartition(partition);
            }
        }
    }

    /**
     * Counts one partition's buffered rows into its sub-table, which is cache resident for the
     * whole drain. Uses the same three-pass probe as {@link PipelinedLongCountHashTable}, since
     * the first rows of a drain still miss while the sub-table is being pulled in.
     */
    private void drainPartition(int partition)
    {
        int rows = windowSize[partition];
        int windowBase = partition * windowRowsPerPartition;
        int tableBase = partition * partitionStride;
        long[] window = this.window;
        long[] table = this.table;
        int[] slots = this.slots;

        for (int start = 0; start < rows; start += PROBE_BATCH_SIZE) {
            int batch = Math.min(PROBE_BATCH_SIZE, rows - start);
            for (int i = 0; i < batch; i++) {
                long value = window[windowBase + start + i];
                slots[i] = tableBase + ((((int) murmurHash3(value)) & subPositionMask) * 2);
            }
            long sink = 0;
            for (int i = 0; i < batch; i++) {
                sink ^= table[slots[i]];
            }
            prefetchSink ^= sink;
            for (int i = 0; i < batch; i++) {
                long value = window[windowBase + start + i];
                int slot = slots[i];
                // re-read so a slot filled earlier in this batch is seen
                if (table[slot] == value) {
                    table[slot + 1]++;
                }
                else {
                    put(value, slot);
                }
            }
        }
        windowSize[partition] = 0;
    }

    /**
     * Values are never 0 here, {@link #putBlock} counts those separately.
     * <p>
     * Probing runs on past the end of the home sub-table rather than wrapping inside it: a
     * partition holds a binomial share of the distinct values, so a sub-table can fill up while
     * the table as a whole has room, and wrapping inside it would spin forever. Spilling forward
     * costs an extra line only for the values that overflow, and leaves progress guaranteed
     * whenever any slot is free.
     */
    private void put(long value, int slot)
    {
        while (true) {
            long current = table[slot];
            if (current == 0) {
                table[slot] = value;
                table[slot + 1] = 1;
                entryCount++;
                return;
            }
            if (current == value) {
                table[slot + 1]++;
                return;
            }
            slot = (slot + 2) & tableSlotMask;
            hashCollisions++;
        }
    }

    @Override
    public long[] getCounts()
    {
        for (int partition = 0; partition < partitionCount; partition++) {
            if (windowSize[partition] > 0) {
                drainPartition(partition);
            }
        }

        long[] counts = new long[(entryCount + (zeroCount > 0 ? 1 : 0)) * 2];
        int countsPosition = 0;
        if (zeroCount > 0) {
            counts[1] = zeroCount;
            countsPosition = 2;
        }
        for (int i = 0; i < table.length; i += 2) {
            if (table[i] != 0) {
                counts[countsPosition] = table[i];
                counts[countsPosition + 1] = table[i + 1];
                countsPosition += 2;
            }
        }
        return counts;
    }

    @Override
    public int getHashCollisions()
    {
        return hashCollisions;
    }

    public int getPartitionCount()
    {
        return partitionCount;
    }

    /** Bytes held on top of the table itself, the part {@link RadixLongCountHashTable} grows without bound. */
    public long getWindowBytes()
    {
        return (long) window.length * Long.BYTES;
    }
}
