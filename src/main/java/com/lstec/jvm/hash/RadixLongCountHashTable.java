package com.lstec.jvm.hash;

import org.openjdk.jmh.annotations.CompilerControl;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static it.unimi.dsi.fastutil.HashCommon.arraySize;
import static it.unimi.dsi.fastutil.HashCommon.murmurHash3;

/**
 * Counts by radix partitioning first, so no probe ever reaches DRAM.
 * <p>
 * A single flat table cannot beat latency/MLP per row, and MLP saturates near 12 on this
 * hardware while latency keeps climbing with the footprint, so a 64 MB table costs ~7 ns per
 * row before any probe work. This trades that random access for sequential traffic:
 * {@link #putBlock} appends each value to one of {@link #partitionCount} buffers, and
 * {@link #getCounts} aggregates one partition at a time into a table small enough to stay in
 * cache. The only random access left is inside that small table.
 * <p>
 * The partition comes from the high bits of the hash and the slot within a partition from the
 * low bits, so the two are independent and a partition's keys spread over its whole table.
 * <p>
 * Sizing is what makes or breaks this: the partition table, the chunk being streamed into it
 * and the emitted counts all have to fit in L2 together, so the table is deliberately sized
 * well under L2 rather than up to it.
 * <p>
 * Costs paid: every row is written out and read back once, and the input is buffered in full,
 * so unlike the other tables this is not a streaming aggregation.
 */
public class RadixLongCountHashTable
        implements LongCountHashTable
{
    /** Only a safety valve: a partition whose table gets this full is resized. */
    private static final float MAX_FILL_RATIO = 0.75f;
    private static final int CHUNK_SIZE = 8192;
    private static final int PROBE_BATCH_SIZE = 64;
    /**
     * How full a partition's table is aimed to be. Deliberately far below the usual 0.75:
     * collisions, not memory, dominate the probe, and at 0.125 the probe costs a third of what
     * it does at 0.75. Only one partition table exists at a time, so the emptiness is bounded
     * by {@link #TARGET_TABLE_BYTES} rather than scaling with the group count.
     */
    public static final float DEFAULT_PARTITION_FILL_RATIO = 0.125f;
    /** Keep one partition's table comfortably inside L2. */
    private static final int TARGET_TABLE_BYTES = 1 << 20;

    private final int partitionCount;
    private final int partitionShift;
    private final int partitionMask;
    private final int initialCapacity;
    private final int expectedSize;

    private final long[][] currentChunk;
    private final int[] currentChunkPosition;
    private final List<long[]>[] filledChunks;

    private final int[] slots = new int[PROBE_BATCH_SIZE];
    private final long[] currentValues = new long[PROBE_BATCH_SIZE];

    private int zeroCount;
    private int hashCollisions;

    public RadixLongCountHashTable(int expectedSize)
    {
        this(expectedSize, DEFAULT_PARTITION_FILL_RATIO);
    }

    @SuppressWarnings("unchecked")
    public RadixLongCountHashTable(int expectedSize, float partitionFillRatio)
    {
        this.expectedSize = expectedSize;
        partitionCount = partitionCount(expectedSize, partitionFillRatio);
        partitionMask = partitionCount - 1;
        partitionShift = 64 - Integer.numberOfTrailingZeros(partitionCount);
        initialCapacity = arraySize(Math.max(1, expectedSize / partitionCount), partitionFillRatio);

        currentChunk = new long[partitionCount][];
        currentChunkPosition = new int[partitionCount];
        filledChunks = new List[partitionCount];
        for (int partition = 0; partition < partitionCount; partition++) {
            currentChunk[partition] = new long[CHUNK_SIZE];
            filledChunks[partition] = new ArrayList<>();
        }
    }

    /** Enough partitions that one partition's table, at the target fill, stays inside L2. */
    private static int partitionCount(int expectedSize, float partitionFillRatio)
    {
        int partitions = 1;
        while (partitions < (1 << 16)
                && (long) arraySize(Math.max(1, expectedSize / partitions), partitionFillRatio) * 2 * Long.BYTES > TARGET_TABLE_BYTES) {
            partitions *= 2;
        }
        return partitions;
    }

    @Override
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public void putBlock(LongAraayBlock block)
    {
        long[] values = block.getValues();
        int positionCount = block.getPositionCount();
        long[][] currentChunk = this.currentChunk;
        int[] currentChunkPosition = this.currentChunkPosition;
        for (int i = 0; i < positionCount; i++) {
            long value = values[i];
            if (value == 0) {
                zeroCount++;
                continue;
            }
            // high bits pick the partition, low bits pick the slot in phase two
            int partition = (int) (murmurHash3(value) >>> partitionShift) & partitionMask;
            int position = currentChunkPosition[partition];
            currentChunk[partition][position] = value;
            position++;
            if (position == CHUNK_SIZE) {
                filledChunks[partition].add(currentChunk[partition]);
                currentChunk[partition] = new long[CHUNK_SIZE];
                position = 0;
            }
            currentChunkPosition[partition] = position;
        }
    }

    @Override
    public long[] getCounts()
    {
        long[] counts = new long[Math.max(2, (expectedSize + 1) * 2)];
        int countsPosition = 0;
        if (zeroCount > 0) {
            counts[1] = zeroCount;
            countsPosition = 2;
        }

        int capacity = initialCapacity;
        long[] table = new long[capacity * 2];
        // every occupied slot is recorded, so emitting and clearing a partition cost O(entries)
        // rather than O(capacity) - without that the sparse table's empty slots dominate
        int[] occupied = new int[(int) (capacity * MAX_FILL_RATIO) + 1];
        for (int partition = 0; partition < partitionCount; partition++) {
            int entries = aggregatePartition(partition, table, capacity, occupied);
            while (entries < 0) {
                // more distinct values than the partition was sized for; the discarded table
                // is replaced rather than cleared, so nothing stale survives
                capacity *= 2;
                table = new long[capacity * 2];
                occupied = new int[(int) (capacity * MAX_FILL_RATIO) + 1];
                entries = aggregatePartition(partition, table, capacity, occupied);
            }

            if (countsPosition + entries * 2 > counts.length) {
                counts = Arrays.copyOf(counts, Math.max(counts.length * 2, countsPosition + entries * 2));
            }
            for (int i = 0; i < entries; i++) {
                int slot = occupied[i];
                counts[countsPosition] = table[slot];
                counts[countsPosition + 1] = table[slot + 1];
                countsPosition += 2;
                table[slot] = 0;
                table[slot + 1] = 0;
            }
        }
        return countsPosition == counts.length ? counts : Arrays.copyOf(counts, countsPosition);
    }

    /**
     * Counts one partition into {@code table}, which arrives zeroed, recording each occupied slot.
     * Returns -1 if the partition holds more distinct values than the table can take.
     */
    private int aggregatePartition(int partition, long[] table, int capacity, int[] occupied)
    {
        int entries = 0;
        List<long[]> chunks = filledChunks[partition];
        for (int chunk = 0; chunk < chunks.size(); chunk++) {
            entries = putChunk(chunks.get(chunk), CHUNK_SIZE, table, capacity, entries, occupied);
            if (entries < 0) {
                return -1;
            }
        }
        return putChunk(currentChunk[partition], currentChunkPosition[partition], table, capacity, entries, occupied);
    }

    /**
     * Same three-pass probe as {@link PipelinedLongCountHashTable}: the table is in cache here,
     * but a 256 KB footprint still has more latency than the core can hide row by row.
     */
    private int putChunk(long[] chunk, int size, long[] table, int capacity, int entries, int[] occupied)
    {
        int[] slots = this.slots;
        long[] currentValues = this.currentValues;
        int positionMask = capacity - 1;
        int slotMask = capacity * 2 - 1;
        int entryLimit = (int) (capacity * MAX_FILL_RATIO);
        int collisions = 0;

        for (int start = 0; start < size; start += PROBE_BATCH_SIZE) {
            int batch = Math.min(PROBE_BATCH_SIZE, size - start);
            for (int i = 0; i < batch; i++) {
                slots[i] = (((int) murmurHash3(chunk[start + i])) & positionMask) * 2;
            }
            for (int i = 0; i < batch; i++) {
                currentValues[i] = table[slots[i]];
            }
            for (int i = 0; i < batch; i++) {
                long value = chunk[start + i];
                // values are never 0 here, putBlock counts those separately
                if (value == currentValues[i]) {
                    table[slots[i] + 1]++;
                    continue;
                }
                int slot = slots[i];
                while (true) {
                    long current = table[slot];
                    if (current == 0) {
                        if (entries == entryLimit) {
                            hashCollisions += collisions;
                            return -1;
                        }
                        table[slot] = value;
                        table[slot + 1] = 1;
                        occupied[entries] = slot;
                        entries++;
                        break;
                    }
                    if (current == value) {
                        table[slot + 1]++;
                        break;
                    }
                    slot = (slot + 2) & slotMask;
                    collisions++;
                }
            }
        }
        hashCollisions += collisions;
        return entries;
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
}
