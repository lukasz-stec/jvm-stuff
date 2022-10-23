package com.lstec.jvm.hash.jug;

import it.unimi.dsi.fastutil.HashCommon;

import static com.google.common.base.Preconditions.checkArgument;

public class CountAggregation_03
        implements CountAggregation
{
    private final int mask;
    private int collisions;

    public CountAggregation_03(int size)
    {
        size = Math.max(HashCommon.arraySize(size, 0.5f), 1024);
        this.hashTable = new Object[size];
        this.count = new long[size];
        mask = size - 1;
    }

    private Object[] hashTable;
    private long[] count;

    public void incrementCount(Object row)
    {
        int position = calculatePosition(row);
        while (hashTable[position] != null) {
            if (hashTable[position].equals(row)) {
                // found an existing group
                count[position]++;
                return;
            }
            collisions++;
            // increment position
            position = (position + 1) & mask;
        }

        // existing group not found
        hashTable[position] = row;
        count[position] = 1;
    }

    private int calculatePosition(Object row)
    {
        return CountAggregation.hash(row.hashCode()) & mask;
    }

    public void batchIncrementCount(Object[] rows)
    {
        for (Object row : rows) {
            incrementCount(row);
        }
    }

    public long getCount(Object row)
    {
        int position = calculatePosition(row);
        while (hashTable[position] != null) {
            if (hashTable[position].equals(row)) {
                return count[position];
            }
            // increment position
            position = (position + 1) & mask;
        }
        return -1;
    }

    public int getCollisions()
    {
        return collisions;
    }
}
