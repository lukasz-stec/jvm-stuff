package com.lstec.jvm.hash;

import org.openjdk.jmh.annotations.CompilerControl;

import static it.unimi.dsi.fastutil.HashCommon.murmurHash3;
import static it.unimi.dsi.fastutil.HashCommon.nextPowerOfTwo;

/**
 * Open addressing table whose bucket is one cache line, resolved without a branch chain.
 * <p>
 * On a table that does not fit in cache the probe has two independent costs: the miss itself,
 * which is bounded by latency/MLP and cannot be improved much once the misses overlap, and the
 * collision handling, which turns out to be the larger of the two. On a fully cache resident
 * table a gather costs 1.4 ns/row while the full linear probe costs 9.2, because every probe
 * step is a dependent compare and an unpredictable branch.
 * <p>
 * Linear probing already stays inside the fetched line - {@code slot + 2} is only 16 bytes on -
 * so the line was never the problem. What this changes is resolving all four candidates the line
 * holds at once: a bucket is four values followed by their four counts, and the matching slot
 * and the first free slot are both found with conditional moves over that snapshot. A row then
 * costs one miss and one well predicted branch instead of a chain of unpredictable ones.
 * <p>
 * Values come first so the four comparisons read 32 contiguous bytes, and the counts share the
 * line so updating one is free. The array is padded because a long[] body starts 16 bytes into
 * the object, which would otherwise split every bucket across two lines.
 * <p>
 * A bucket that is full spills to the next one, and occupied slots are always a prefix of a
 * bucket, so a scan can stop at the first free slot. Like the other flat tables this one never
 * grows, so {@code expectedSize} has to be an upper bound.
 */
public class BucketedLongCountHashTable
        implements LongCountHashTable
{
    public static final float DEFAULT_FILL_RATIO = 0.5f;
    public static final int DEFAULT_BATCH_SIZE = 64;

    private static final int ENTRIES_PER_BUCKET = 4;
    /** 4 values plus 4 counts: 64 bytes, one cache line. */
    private static final int BUCKET_LONGS = 8;
    /** A long[] body starts 16 bytes in, so 6 longs of padding put bucket 0 on a line boundary. */
    private static final int ALIGNMENT_PAD = 6;

    private final long[] table;
    private final int bucketMask;
    private final int lastBucketBase;
    /** Base index of each row's bucket, filled by {@link #hashBatch}. */
    private final int[] bucketBases;

    private long prefetchSink;
    private int zeroCount;
    private int hashCollisions;
    private int entryCount;

    public BucketedLongCountHashTable(int expectedSize)
    {
        this(expectedSize, DEFAULT_FILL_RATIO, DEFAULT_BATCH_SIZE);
    }

    public BucketedLongCountHashTable(int expectedSize, float fillRatio, int batchSize)
    {
        int bucketCount = (int) nextPowerOfTwo((long) Math.max(1, Math.ceil(expectedSize / (ENTRIES_PER_BUCKET * (double) fillRatio))));
        table = new long[ALIGNMENT_PAD + bucketCount * BUCKET_LONGS];
        bucketMask = bucketCount - 1;
        lastBucketBase = ALIGNMENT_PAD + (bucketCount - 1) * BUCKET_LONGS;
        bucketBases = new int[batchSize];
    }

    @Override
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public void putBlock(LongAraayBlock block)
    {
        long[] values = block.getValues();
        int positionCount = block.getPositionCount();
        int batchSize = bucketBases.length;
        for (int start = 0; start < positionCount; start += batchSize) {
            int size = Math.min(batchSize, positionCount - start);
            hashBatch(values, start, size);
            prefetchBatch(size);
            countBatch(values, start, size);
        }
    }

    private void hashBatch(long[] values, int start, int size)
    {
        int[] bucketBases = this.bucketBases;
        for (int i = 0; i < size; i++) {
            bucketBases[i] = bucketBase(values[start + i]);
        }
    }

    /**
     * Pulls every bucket line in. Independent addresses, no stores into the table and no
     * branches, so the whole batch of misses overlaps. The loaded values are only a side effect,
     * {@link #countBatch} re-reads them out of L1 so it cannot act on a stale snapshot of a
     * bucket an earlier row of the same batch has just written to.
     */
    private void prefetchBatch(int size)
    {
        int[] bucketBases = this.bucketBases;
        long[] table = this.table;
        long sink = 0;
        for (int i = 0; i < size; i++) {
            sink ^= table[bucketBases[i]];
        }
        prefetchSink ^= sink;
    }

    private void countBatch(long[] values, int start, int size)
    {
        int[] bucketBases = this.bucketBases;
        long[] table = this.table;
        for (int i = 0; i < size; i++) {
            long value = values[start + i];
            if (value == 0) {
                zeroCount++;
                continue;
            }
            int base = bucketBases[i];
            long v0 = table[base];
            long v1 = table[base + 1];
            long v2 = table[base + 2];
            long v3 = table[base + 3];

            // four independent conditional sets ORed into a mask, so the comparisons do not
            // serialize the way a chain of ternaries would, then one bit scan for the slot
            int matches = (v0 == value ? 1 : 0)
                    | (v1 == value ? 2 : 0)
                    | (v2 == value ? 4 : 0)
                    | (v3 == value ? 8 : 0);
            if (matches != 0) {
                table[base + ENTRIES_PER_BUCKET + Integer.numberOfTrailingZeros(matches)]++;
                continue;
            }

            int free = (v0 == 0 ? 1 : 0)
                    | (v1 == 0 ? 2 : 0)
                    | (v2 == 0 ? 4 : 0)
                    | (v3 == 0 ? 8 : 0);
            if (free != 0) {
                // lowest free slot keeps the occupied slots a prefix of the bucket
                int slot = Integer.numberOfTrailingZeros(free);
                table[base + slot] = value;
                table[base + ENTRIES_PER_BUCKET + slot] = 1;
                entryCount++;
                continue;
            }

            putOverflow(value, base);
        }
    }

    /** Only reached for a value whose own bucket is full. */
    private void putOverflow(long value, int base)
    {
        while (true) {
            base = base == lastBucketBase ? ALIGNMENT_PAD : base + BUCKET_LONGS;
            hashCollisions++;
            for (int slot = 0; slot < ENTRIES_PER_BUCKET; slot++) {
                long current = table[base + slot];
                if (current == value) {
                    table[base + ENTRIES_PER_BUCKET + slot]++;
                    return;
                }
                if (current == 0) {
                    table[base + slot] = value;
                    table[base + ENTRIES_PER_BUCKET + slot] = 1;
                    entryCount++;
                    return;
                }
            }
        }
    }

    private int bucketBase(long value)
    {
        return ALIGNMENT_PAD + ((((int) murmurHash3(value)) & bucketMask) * BUCKET_LONGS);
    }

    @Override
    public long[] getCounts()
    {
        long[] counts = new long[(entryCount + (zeroCount > 0 ? 1 : 0)) * 2];
        int countsPosition = 0;
        if (zeroCount > 0) {
            counts[1] = zeroCount;
            countsPosition = 2;
        }
        for (int base = ALIGNMENT_PAD; base <= lastBucketBase; base += BUCKET_LONGS) {
            for (int slot = 0; slot < ENTRIES_PER_BUCKET; slot++) {
                long value = table[base + slot];
                if (value == 0) {
                    break; // occupied slots are a prefix of the bucket
                }
                counts[countsPosition] = value;
                counts[countsPosition + 1] = table[base + ENTRIES_PER_BUCKET + slot];
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
