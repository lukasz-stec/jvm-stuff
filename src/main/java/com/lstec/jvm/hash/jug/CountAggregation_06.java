package com.lstec.jvm.hash.jug;

import it.unimi.dsi.fastutil.HashCommon;
import org.openjdk.jmh.annotations.CompilerControl;

public class CountAggregation_06
        implements LongCountAggregation
{
    private final int entryCountMask;
    private final int tableSizeMask;
    private int collisions;
    private final int batchSize;
    private final long[] currentValues;
    private final int[] positions;
    private long[] hashTable;
    private int zeroCount;

    public CountAggregation_06(int size)
    {
        this(size, 16);
    }

    public CountAggregation_06(int size, int batchSize)
    {
        this.batchSize = batchSize;
        size = Math.max(HashCommon.arraySize(size, 0.2f), 1024);
        this.hashTable = new long[size * 2];
        entryCountMask = size - 1;
        tableSizeMask = size * 2 - 1;
        currentValues = new long[batchSize];
        positions = new int[batchSize];
    }

    public void incrementCount(long row)
    {
        incrementCount(row, calculatePosition(row));
    }

    private int calculatePosition(long row)
    {
        return ((int) CountAggregation.hash(row) & entryCountMask) * 2;
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public void batchIncrementCount(long[] rows)
    {
        int batchStart = 0;

        for (; batchStart + batchSize <= rows.length; batchStart += batchSize) {
            processBatch(rows, batchStart);
        }
        incrementCountForLeftovers(rows, batchStart);
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    private void processBatch(long[] rows, int batchStart)
    {
        calculatePositions(rows, batchStart, positions);
//        getCurrentValues(positions);
        incrementCountForBatch(rows, batchStart, positions);
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    private void incrementCountForLeftovers(long[] rows, int batchStart)
    {
        for (int i = batchStart; i < rows.length; i++) {
            incrementCount(rows[i]);
        }
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    private void incrementCountForBatch(long[] rows, int batchStart, int[] positions)
    {
        long[] hashTable = this.hashTable;
        long[] currentValues = this.currentValues;

        for (int i = 0; i < positions.length; i++) {
            long value = rows[batchStart + i];
            int position = positions[i];
            if (value == 0) {
                zeroCount++;
                continue;
            }
//            long currentValue = currentValues[i];
            boolean found = false;
            while (hashTable[position] != 0) {
                if (hashTable[position] == value) {
                    // found an existing group
                    hashTable[position + 1]++;
                    found = true;
                    break;
                }
                collisions++;
                // increment position
                position = (position + 2) & tableSizeMask;
            }
            if (!found) {
                // existing group not found
                hashTable[position] = value;
                hashTable[position + 1] = 1;
            }
        }
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    private void getCurrentValues(int[] positions)
    {
        long[] currentValues = this.currentValues;
        long[] hashTable = this.hashTable;
        for (int i = 0; i + 8 <= positions.length; i++) {
            currentValues[i] = hashTable[positions[i]];
        }
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    private void getCurrentValues_unroll8(int[] positions)
    {
        long[] currentValues = this.currentValues;
        long[] hashTable = this.hashTable;
        for (int i = 0; i + 8 <= positions.length; i += 8) {
            currentValues[i] = hashTable[positions[i]];
            currentValues[i + 1] = hashTable[positions[i + 1]];
            currentValues[i + 2] = hashTable[positions[i + 2]];
            currentValues[i + 3] = hashTable[positions[i + 3]];
            currentValues[i + 4] = hashTable[positions[i + 4]];
            currentValues[i + 5] = hashTable[positions[i + 5]];
            currentValues[i + 6] = hashTable[positions[i + 6]];
            currentValues[i + 7] = hashTable[positions[i + 7]];
        }
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    private void getCurrentValues2(int[] positions)
    {
        long[] currentValues = this.currentValues;
        long[] hashTable = this.hashTable;
        for (int i = 0; i + 8 <= positions.length; i += 8) {
            int position = positions[i];
            int position1 = positions[i + 1];
            int position2 = positions[i + 2];
            int position3 = positions[i + 3];
            int position4 = positions[i + 4];
            int position5 = positions[i + 5];
            int position6 = positions[i + 6];
            int position7 = positions[i + 7];
            currentValues[i] = hashTable[position];
            currentValues[i + 1] = hashTable[position1];
            currentValues[i + 2] = hashTable[position2];
            currentValues[i + 3] = hashTable[position3];
            currentValues[i + 4] = hashTable[position4];
            currentValues[i + 5] = hashTable[position5];
            currentValues[i + 6] = hashTable[position6];
            currentValues[i + 7] = hashTable[position7];
        }
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    private void getCurrentValues_unroll4(int[] positions)
    {
        long[] currentValues = this.currentValues;
        long[] hashTable = this.hashTable;
        for (int i = 0; i + 8 <= positions.length; i += 4) {
            int position = positions[i];
            int position1 = positions[i + 1];
            int position2 = positions[i + 2];
            int position3 = positions[i + 3];
            currentValues[i] = hashTable[position];
            currentValues[i + 1] = hashTable[position1];
            currentValues[i + 2] = hashTable[position2];
            currentValues[i + 3] = hashTable[position3];
        }
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    private void calculatePositions(long[] rows, int batchStart, int[] positions)
    {
        for (int i = 0; i < positions.length; i++) {
            positions[i] = calculatePosition(rows[batchStart + i]);
        }
    }

    private void incrementCount(long value, int position)
    {
        if (value == 0) {
            zeroCount++;
            return;
        }
        while (hashTable[position] != 0) {
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
        if (row == 0) {
            return zeroCount;
        }
        
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
