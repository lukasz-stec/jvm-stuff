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
