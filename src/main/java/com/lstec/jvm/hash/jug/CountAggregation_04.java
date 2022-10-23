package com.lstec.jvm.hash.jug;

import it.unimi.dsi.fastutil.HashCommon;

public class CountAggregation_04
        implements LongCountAggregation
{
    private final int entryCountMask;
    private final int tableSizeMask;
    private int collisions;

    public CountAggregation_04(int size)
    {
        size = Math.max(HashCommon.arraySize(size, 0.5f), 1024);
        this.hashTable = new long[size * 2];
        entryCountMask = size - 1;
        tableSizeMask = size * 2 - 1;
    }

    private long[] hashTable;

    public void incrementCount(long row)
    {
        int position = calculatePosition(row);
        while (hashTable[position + 1] != 0) {
            if (hashTable[position] == row) {
                // found an existing group
                hashTable[position + 1]++;
                return;
            }
            collisions++;
            // increment position
            position = (position + 2) & tableSizeMask;
        }

        // existing group not found
        hashTable[position] = row;
        hashTable[position + 1] = 1;
    }

    private int calculatePosition(long row)
    {
        return ((int) CountAggregation.hash(row) & entryCountMask) * 2;
    }

    @Override
    public void batchIncrementCount(long[] rows)
    {
        for (long row : rows) {
            incrementCount(row);
        }
    }

    @Override
    public long getCount(long row)
    {
        int position = calculatePosition(row);
        while (hashTable[position + 1] != 0) {
            if (hashTable[position] == row) {
                return hashTable[position + 1];
            }
            // increment position
            position = (position + 2) & tableSizeMask;
        }
        return -1;
    }

    @Override
    public int getCollisions()
    {
        return collisions;
    }
}
