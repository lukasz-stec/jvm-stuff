package com.lstec.jvm.hash;

import org.openjdk.jmh.annotations.CompilerControl;

import static it.unimi.dsi.fastutil.HashCommon.arraySize;
import static it.unimi.dsi.fastutil.HashCommon.murmurHash3;

/**
 * Open addressing (value, count) table for a hash table that does not fit in cache.
 * <p>
 * A single row is a dependent chain: hash -> load slot -> compare -> store the count. Walking
 * rows one at a time keeps only a few of those misses in flight, because the count store and
 * the probe branch of row i sit between the address of row i and the address of row i + 1.
 * Here each batch of rows is walked three times instead:
 * <ol>
 * <li>{@link #hashBatch} turns values into slot indexes. Pure ALU work, it touches no table line.
 * <li>{@link #gatherBatch} loads the head slot of every probe. Independent addresses, no store
 * into the table, no data dependent branch, so a whole batch of misses is issued back to back
 * and the latencies overlap instead of adding up.
 * <li>{@link #countBatch} does the branchy read-modify-write work on lines that are in L1 by now.
 * </ol>
 * Keeping the gather in a pass of its own, rather than fusing it into the hash pass, is worth
 * about 5%: a loop body of two loads and a store fits the reorder window many more times over.
 * <p>
 * The value and its count are adjacent so they always share a cache line (both 64 and 128 byte
 * lines), which makes the count update free once the value has been loaded, and makes linear
 * probing cheap: the first retries stay inside the line the gather pass already fetched.
 */
public class PipelinedLongCountHashTable
        implements LongCountHashTable
{
    private static final float FILL_RATIO = 0.75f;
    public static final int DEFAULT_BATCH_SIZE = 128;

    private final long[] hashTable;
    private final int hashPositionMask;
    private final int slotMask;
    /** Head slot of each row's probe, filled by {@link #hashBatch}. */
    private final int[] slots;
    /** Table value found at {@link #slots}, filled by {@link #gatherBatch}. */
    private final long[] currentValues;

    private int zeroCount;
    private int hashCollisions;
    private int entryCount;

    public PipelinedLongCountHashTable(int expectedSize)
    {
        this(expectedSize, DEFAULT_BATCH_SIZE);
    }

    public PipelinedLongCountHashTable(int expectedSize, int batchSize)
    {
        int hashCapacity = arraySize(expectedSize, FILL_RATIO);
        hashTable = new long[hashCapacity * 2]; // value + count
        hashPositionMask = hashCapacity - 1;
        slotMask = hashTable.length - 1;
        slots = new int[batchSize];
        currentValues = new long[batchSize];
    }

    @Override
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public void putBlock(LongAraayBlock block)
    {
        long[] values = block.getValues();
        int positionCount = block.getPositionCount();
        int batchSize = slots.length;
        for (int start = 0; start < positionCount; start += batchSize) {
            int size = Math.min(batchSize, positionCount - start);
            hashBatch(values, start, size);
            gatherBatch(size);
            countBatch(values, start, size);
        }
    }

    private void hashBatch(long[] values, int start, int size)
    {
        int[] slots = this.slots;
        for (int i = 0; i < size; i++) {
            slots[i] = slot(values[start + i]);
        }
    }

    private void gatherBatch(int size)
    {
        int[] slots = this.slots;
        long[] currentValues = this.currentValues;
        long[] hashTable = this.hashTable;
        for (int i = 0; i < size; i++) {
            currentValues[i] = hashTable[slots[i]];
        }
    }

    private void countBatch(long[] values, int start, int size)
    {
        int[] slots = this.slots;
        long[] currentValues = this.currentValues;
        long[] hashTable = this.hashTable;
        for (int i = 0; i < size; i++) {
            long value = values[start + i];
            // only an insert changes a value slot, and it only ever fills an empty one, so a
            // non-zero currentValues[i] cannot have gone stale on an earlier row of this batch
            if (value == currentValues[i] && value != 0) {
                hashTable[slots[i] + 1]++;
            }
            else {
                put(value, slots[i]);
            }
        }
    }

    /** Insert, zero, and collision paths: re-reads the table so a slot filled earlier in this batch is seen. */
    private void put(long value, int slot)
    {
        if (value == 0) {
            entryCount += zeroCount == 0 ? 1 : 0;
            zeroCount++;
            return;
        }

        while (true) {
            long current = hashTable[slot];
            if (current == 0) {
                hashTable[slot] = value;
                hashTable[slot + 1] = 1;
                entryCount++;
                return;
            }

            if (current == value) {
                hashTable[slot + 1]++;
                return;
            }

            // increment slot and mask to handle wrap around
            slot = (slot + 2) & slotMask;
            hashCollisions++;
        }
    }

    private int slot(long value)
    {
        return (((int) murmurHash3(value)) & hashPositionMask) * 2;
    }

    @Override
    public long[] getCounts()
    {
        long[] counts = new long[entryCount * 2];
        int countsPosition = 0;
        if (zeroCount > 0) {
            counts[0] = 0;
            counts[1] = zeroCount;
            countsPosition = 2;
        }
        for (int i = 0; i < hashTable.length && countsPosition < counts.length; i += 2) {
            if (hashTable[i] != 0) {
                counts[countsPosition] = hashTable[i];
                counts[countsPosition + 1] = hashTable[i + 1];
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
}
