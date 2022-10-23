package com.lstec.jvm.hash.jug;

import it.unimi.dsi.fastutil.HashCommon;

public class CountAggregation_05
        implements LongCountAggregation
{
    private final int entryCountMask;
    private final int tableSizeMask;
    private int collisions;
    private final int batchSize;

    public CountAggregation_05(int size)
    {
        this(size, 256);
    }

    public CountAggregation_05(int size, int batchSize)
    {
        this.batchSize = batchSize;
        size = Math.max(HashCommon.arraySize(size, 0.5f), 1024);
        this.hashTable = new long[size * 2];
        entryCountMask = size - 1;
        tableSizeMask = size * 2 - 1;
    }

    private long[] hashTable;

    public void incrementCount(long row)
    {
        incrementCount(row, calculatePosition(row));
    }

    private int calculatePosition(long row)
    {
        return ((int) CountAggregation.hash(row) & entryCountMask) * 2;
    }

    public void batchIncrementCount(long[] rows)
    {
        int batchStart = 0;
        int[] positions = new int[batchSize];
        long[] currentValues = new long[batchSize];
        int conflictCount = 0;
        long[] conflicts = new long[batchSize];
        for (; batchStart + batchSize <= rows.length; batchStart += batchSize) {
            for (int i = 0; i < positions.length; i++) {
                positions[i] = calculatePosition(rows[batchStart + i]);
            }
            for (int i = 0; i < positions.length; i++) {
                currentValues[i] = hashTable[positions[i]];
            }
            for (int i = 0; i < positions.length; i++) {
                long value = rows[batchStart + i];
                int position = positions[i];
                int countPosition = position + 1;
                if (hashTable[countPosition] != 0) {    
                    if (currentValues[i] == value || hashTable[position] == value) {
                        hashTable[countPosition]++;
                    }
                    else {
                        positions[conflictCount] = (position + 2) & tableSizeMask;
                        conflicts[conflictCount++] = value;
                    }
                }
                else {
                    hashTable[position] = value;
                    hashTable[countPosition] = 1;
                }
            }
            for (int i = 0; i < conflictCount; i++) {
                int position = positions[i];
                long conflictValue = conflicts[i];
                incrementCount(conflictValue, position);
            }
            conflictCount = 0;
        }
        for (int i = batchStart; i < rows.length; i++) {
            incrementCount(rows[i]);
        }
    }

    private void incrementCount(long value, int position)
    {
        while (hashTable[position + 1] != 0) {
            if (hashTable[position] == value) {
                // found an existing group
                hashTable[position + 1]++;
                return;
            }
            collisions++;
            // increment position
            position = (position + 2) & tableSizeMask;
        }

        // existing group not found
        hashTable[position] = value;
        hashTable[position + 1] = 1;
    }

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

    public int getCollisions()
    {
        return collisions;
    }
}
