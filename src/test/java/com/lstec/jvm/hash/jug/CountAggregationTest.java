package com.lstec.jvm.hash.jug;

import it.unimi.dsi.fastutil.longs.Long2IntMap;
import it.unimi.dsi.fastutil.longs.Long2IntOpenHashMap;
import org.testng.annotations.Test;

import java.util.Random;
import java.util.stream.LongStream;

import static org.testng.Assert.assertEquals;

public class CountAggregationTest
{

    @Test
    public void testCountAggregation()
    {
        testMap(new CountAggregation_01(4));
    }

    @Test
    public void testCountAggregation_V02()
    {
        testMap(new CountAggregation_02(4));
    }

    @Test
    public void testCountAggregation_v03()
    {
        testMap(new CountAggregation_03(4));
    }

    @Test
    public void testCountAggregation_v04()
    {
        testMap(new CountAggregation_04(10));
    }

    @Test
    public void testCountAggregation_v05()
    {
        testMap(new CountAggregation_05(10, 8));
    }

    @Test
    public void testCountAggregation_v06()
    {
        testMap(new CountAggregation_06(10, 8));
    }

    @Test
    public void testCountAggregation_v05_large()
    {
        testMapLArge(new CountAggregation_05(3000000));
    }

    @Test
    public void testCountAggregation_v06_large()
    {
        testMapLArge(new CountAggregation_06(3000000));
    }

    private static void testMapLArge(LongCountAggregation map)
    {
        Random random = new Random(1234);
        long[] values = LongStream.range(0, 10000000).map(i -> random.nextLong(3000000)).toArray();
        map.batchIncrementCount(values);
        Long2IntMap otherMap = new Long2IntOpenHashMap(3000000);
        for (long value : values) {
            otherMap.compute(value, (key, count) -> count == null ? 1 : count + 1);
        }
        for (Long2IntMap.Entry entry : otherMap.long2IntEntrySet()) {
            assertEquals(map.getCount(entry.getLongKey()), entry.getIntValue(), "expected count equals for " + entry.getLongKey());
        }
    }

    private static void testMap(CountAggregation map)
    {
        map.incrementCount(1);
        map.incrementCount(1);
        map.incrementCount(2);
        map.incrementCount(2);
        map.incrementCount(2);
        assertEquals(map.getCount(1), 2);
        assertEquals(map.getCount(2), 3);
        assertEquals(map.getCount(3), -1);
    }

    private static void testMap(LongCountAggregation map)
    {
        map.batchIncrementCount(new long[] {0, 1, 1, 2, 2, 2, 4, 4, 4, 4, 4});
        assertEquals(map.getCount(0), 1);
        assertEquals(map.getCount(1), 2);
        assertEquals(map.getCount(2), 3);
        assertEquals(map.getCount(3), -1);
        assertEquals(map.getCount(4), 5);
    }
}