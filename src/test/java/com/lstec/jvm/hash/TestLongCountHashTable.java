package com.lstec.jvm.hash;

import it.unimi.dsi.fastutil.longs.Long2LongMap;
import it.unimi.dsi.fastutil.longs.Long2LongOpenHashMap;
import org.junit.jupiter.api.Test;

import java.util.Random;
import java.util.function.IntFunction;

import static org.assertj.core.api.Assertions.assertThat;

public class TestLongCountHashTable
{
    @Test
    public void testScalar()
    {
        for (int groupCount : new int[] {4, 1000, 100_000}) {
            assertCounts(ScalarLongCountHashTable::new, groupCount);
        }
    }

    @Test
    public void testPipelined()
    {
        for (int groupCount : new int[] {4, 1000, 100_000}) {
            assertCounts(PipelinedLongCountHashTable::new, groupCount);
        }
    }

    @Test
    public void testPipelinedBatchSize()
    {
        for (int batchSize : new int[] {1, 3, 8, 64, 1024}) {
            assertCounts(expectedSize -> new PipelinedLongCountHashTable(expectedSize, batchSize), 1000);
        }
    }

    @Test
    public void testBucketed()
    {
        for (int groupCount : new int[] {4, 1000, 100_000}) {
            assertCounts(BucketedLongCountHashTable::new, groupCount);
        }
    }

    @Test
    public void testBucketedFillRatio()
    {
        // 1.0 leaves no spare slots, so most values have to spill to a following bucket
        for (float fillRatio : new float[] {1.0f, 0.75f, 0.5f, 0.25f}) {
            for (int batchSize : new int[] {1, 7, 64}) {
                assertCounts(expectedSize -> new BucketedLongCountHashTable(expectedSize, fillRatio, batchSize), 1000);
            }
        }
    }

    @Test
    public void testBucketedZeroValue()
    {
        BucketedLongCountHashTable hashTable = new BucketedLongCountHashTable(4);
        hashTable.putBlock(new LongAraayBlock(new long[] {0, 1, 0, 1, 0}, 5));
        assertThat(toMap(hashTable.getCounts())).isEqualTo(new Long2LongOpenHashMap(new long[] {0, 1}, new long[] {3, 2}));
    }

    @Test
    public void testRadix()
    {
        for (int groupCount : new int[] {4, 1000, 100_000, 300_000}) {
            assertCounts(RadixLongCountHashTable::new, groupCount);
        }
    }

    @Test
    public void testRadixPartitionGrowth()
    {
        // sized for far fewer distinct values than it gets, so every partition has to grow
        assertCounts(expectedSize -> new RadixLongCountHashTable(16), 200_000);
    }

    @Test
    public void testRadixZeroValue()
    {
        RadixLongCountHashTable hashTable = new RadixLongCountHashTable(4);
        hashTable.putBlock(new LongAraayBlock(new long[] {0, 1, 0, 1, 0}, 5));
        assertThat(toMap(hashTable.getCounts())).isEqualTo(new Long2LongOpenHashMap(new long[] {0, 1}, new long[] {3, 2}));
    }

    @Test
    public void testPipelinedZeroValue()
    {
        PipelinedLongCountHashTable hashTable = new PipelinedLongCountHashTable(4);
        hashTable.putBlock(new LongAraayBlock(new long[] {0, 1, 0, 1, 0}, 5));
        assertThat(toMap(hashTable.getCounts())).isEqualTo(new Long2LongOpenHashMap(new long[] {0, 1}, new long[] {3, 2}));
    }

    private static void assertCounts(IntFunction<LongCountHashTable> factory, int groupCount)
    {
        // blocks whose size is neither a multiple nor a divisor of the batch size
        int blockSize = 3000;
        int blocks = 7;
        Random random = new Random(0);
        LongCountHashTable hashTable = factory.apply(groupCount);
        Long2LongMap expected = new Long2LongOpenHashMap();
        for (int block = 0; block < blocks; block++) {
            long[] values = new long[blockSize];
            for (int i = 0; i < blockSize; i++) {
                // includes 0, which the tables keep outside the array
                long value = random.nextInt(groupCount);
                values[i] = value;
                expected.put(value, expected.get(value) + 1);
            }
            hashTable.putBlock(new LongAraayBlock(values, blockSize));
        }

        assertThat(toMap(hashTable.getCounts())).isEqualTo(expected);
    }

    private static Long2LongMap toMap(long[] counts)
    {
        Long2LongMap map = new Long2LongOpenHashMap();
        for (int i = 0; i < counts.length; i += 2) {
            assertThat(map.put(counts[i], counts[i + 1])).as("duplicate entry for value %s", counts[i]).isEqualTo(0);
        }
        return map;
    }
}
