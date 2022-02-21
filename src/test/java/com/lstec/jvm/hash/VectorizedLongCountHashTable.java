package com.lstec.jvm.hash;

import static it.unimi.dsi.fastutil.HashCommon.arraySize;
import static it.unimi.dsi.fastutil.HashCommon.murmurHash3;

public class VectorizedLongCountHashTable
        implements LongCountHashTable
{
    private static final float FILL_RATIO = 0.75f;

    private final long[] hashTable0;
    private final long[] hashTable1;
    private final long[] hashTable2;
    private final long[] hashTable3;
    private final int hashCapacity;
    private final int mask;
    private int zeroCount;
    private int hashCollisions0;
    private int hashCollisions1;
    private int hashCollisions2;
    private int hashCollisions3;
    private int entryCount0;
    private int entryCount1;
    private int entryCount2;
    private int entryCount3;

    public VectorizedLongCountHashTable(int expectedSize)
    {
        hashCapacity = arraySize(expectedSize, FILL_RATIO);
        hashTable0 = new long[hashCapacity * 2]; // value + count
        hashTable1 = new long[hashCapacity * 2]; // value + count
        hashTable2 = new long[hashCapacity * 2]; // value + count
        hashTable3 = new long[hashCapacity * 2]; // value + count

        mask = hashTable0.length - 1;
    }

    static class BatchBuffers
    {
        private final int positions[];
        private final long currentValues[];
        private final int toProcess0[];
        private final int toProcess1[];
        private final int toProcess2[];
        private final int toProcess3[];

        public BatchBuffers(int batchSize)
        {
            positions = new int[batchSize];
            currentValues = new long[batchSize];
            toProcess0 = new int[batchSize];
            toProcess1 = new int[batchSize];
            toProcess2 = new int[batchSize];
            toProcess3 = new int[batchSize];
        }
    }

    @Override
    public void put(LongAraayBlock block)
    {
        long[] values = block.getValues();
        int batchSize = 64;
        BatchBuffers batchBuffers = new BatchBuffers(batchSize);

        int batchStart = 0;
        while (batchStart + batchSize <= block.getPositionCount()) {
            put(values, batchStart, batchSize, batchBuffers);
            batchStart += batchSize;
        }

        int lastBatchSize = Math.min(batchSize, block.getPositionCount() - batchStart);
        int batchSizeNot4 = lastBatchSize % 4;

        if (lastBatchSize > 0) {
            put(values, batchStart, lastBatchSize - batchSizeNot4, batchBuffers);
            batchStart += lastBatchSize - batchSizeNot4;
            for (int i = batchStart; i < block.getPositionCount(); i++) {
                put0(position(values[i]), values[i]);
            }
        }
    }

    void put(long[] values, int startPosition, int batchSize, BatchBuffers batchBuffers)
    {
        // TODO lysy: handle 0
//        if (value == 0) {
//            entryCount += zeroCount == 0 ? 1 : 0;
//            zeroCount++;
//            return;
//        }

        int[] positions = batchBuffers.positions;
//        long[] currentValues = batchBuffers.currentValues;
        int toProcess0[] = batchBuffers.toProcess0;
        int toProcess1[] = batchBuffers.toProcess1;
        int toProcess2[] = batchBuffers.toProcess2;
        int toProcess3[] = batchBuffers.toProcess3;
        for (int i = 0; i < batchSize; i++) {
            positions[i] = position(values[startPosition + i]);
        }

//        for (int i = 0; i < batchSize; i += 4) {
//            currentValues[i] = hashTable0[positions[i]];
//            currentValues[i + 1] = hashTable1[positions[i + 1]];
//            currentValues[i + 2] = hashTable2[positions[i + 2]];
//            currentValues[i + 3] = hashTable3[positions[i + 3]];
//        }

        int toProcess0Index = 0;
        int toProcess1Index = 0;
        int toProcess2Index = 0;
        int toProcess3Index = 0;
        
        for (int i = 0; i < batchSize; i += 4) {
            if (values[startPosition + i] == hashTable0[positions[i]]) {
                hashTable0[positions[i] + 1]++;
            }
            else if (hashTable0[positions[i]] == 0) {
                hashTable0[positions[i]] = values[startPosition + i];
                hashTable0[positions[i] + 1] = 1;
                entryCount0++;
            }
            else {
                // increment position and mask to handle wrap around
                positions[i] = (positions[i] + 2) & mask;
                toProcess0[toProcess0Index++] = i;
                hashCollisions0++;
            }

            if (values[startPosition + i + 1] == hashTable1[positions[i + 1]]) {
                hashTable0[positions[i + 1] + 1]++;
            }
            else if (hashTable1[positions[i + 1]] == 0) {
                hashTable0[positions[i + 1]] = values[startPosition + i + 1];
                hashTable0[positions[i + 1] + 1] = 1;
                entryCount1++;
            }
            else {
                // increment position and mask to handle wrap around
                positions[i + 1] = (positions[i + 1] + 2) & mask;
                toProcess1[toProcess1Index++] = i;
                hashCollisions1++;
            }

            if (values[startPosition + i + 2] == hashTable2[positions[i + 2]]) {
                hashTable0[positions[i + 2] + 1]++;
            }
            else if (hashTable2[positions[i + 2]] == 0) {
                hashTable0[positions[i + 2]] = values[startPosition + i + 2];
                hashTable0[positions[i + 2] + 1] = 1;
                entryCount2++;
            }
            else {
                // increment position and mask to handle wrap around
                positions[i + 2] = (positions[i + 2] + 2) & mask;
                toProcess2[toProcess2Index++] = i;
                hashCollisions2++;
            }

            if (values[startPosition + i + 3] == hashTable3[positions[i + 3]]) {
                hashTable0[positions[i + 3] + 1]++;
            }
            else if (hashTable3[positions[i + 3]] == 0) {
                hashTable0[positions[i + 3]] = values[startPosition + i + 3];
                hashTable0[positions[i + 3] + 1] = 1;
                entryCount3++;
            }
            else {
                // increment position and mask to handle wrap around
                positions[i + 3] = (positions[i + 3] + 3) & mask;
                toProcess3[toProcess3Index++] = i;
                hashCollisions3++;
            }
        }

        for (int i = 0; i < toProcess0Index; i++) {
            int toProcessIndex = toProcess0[i];
            int position = positions[toProcessIndex];
            long value = values[toProcessIndex];
            put0(position, value);
        }

        for (int i = 0; i < toProcess1Index; i++) {
            int toProcessIndex = toProcess1[i];
            int position = positions[toProcessIndex];
            long value = values[toProcessIndex];
            put1(position, value);
        }

        for (int i = 0; i < toProcess2Index; i++) {
            int toProcessIndex = toProcess2[i];
            int position = positions[toProcessIndex];
            long value = values[toProcessIndex];
            put2(position, value);
        }

        for (int i = 0; i < toProcess3Index; i++) {
            int toProcessIndex = toProcess3[i];
            int position = positions[toProcessIndex];
            long value = values[toProcessIndex];
            put3(position, value);
        }
    }

    private boolean put3(int position, long value)
    {
        while (true) {
            long current = hashTable3[position];
            if (current == 0) {
                break;
            }

            if (value == current) {
                // increase count
                hashTable3[position + 1]++;
                return true;
            }

            // increment position and mask to handle wrap around
            position = (position + 3) & mask;
            hashCollisions3++;
        }

        // new entry
        hashTable3[position] = value;
        hashTable3[position + 3] = 1;
        entryCount3++;
        return false;
    }

    private boolean put2(int position, long value)
    {
        while (true) {
            long current = hashTable2[position];
            if (current == 0) {
                break;
            }

            if (value == current) {
                // increase count
                hashTable2[position + 1]++;
                return true;
            }

            // increment position and mask to handle wrap around
            position = (position + 2) & mask;
            hashCollisions2++;
        }

        // new entry
        hashTable2[position] = value;
        hashTable2[position + 2] = 1;
        entryCount2++;
        return false;
    }

    private boolean put1(int position, long value)
    {
        while (true) {
            long current = hashTable1[position];
            if (current == 0) {
                break;
            }

            if (value == current) {
                // increase count
                hashTable1[position + 1]++;
                return true;
            }

            // increment position and mask to handle wrap around
            position = (position + 2) & mask;
            hashCollisions1++;
        }

        // new entry
        hashTable1[position] = value;
        hashTable1[position + 1] = 1;
        entryCount1++;
        return false;
    }

    private boolean put0(long value, long count)
    {
        return put0(position(value), value, count);
    }

    private boolean put0(int position, long value)
    {
        return put0(position, value, 1);
    }

    private boolean put0(int position, long value, long count)
    {
        while (true) {
            long current = hashTable0[position];
            if (current == 0) {
                break;
            }

            if (value == current) {
                // increase count
                hashTable0[position + 1]++;
                return true;
            }

            // increment position and mask to handle wrap around
            position = (position + 2) & mask;
            hashCollisions0++;
        }

        // new entry
        hashTable0[position] = value;
        hashTable0[position + 1] = count;
        entryCount0++;
        return false;
    }

    private int position(long value)
    {
        return ((int) murmurHash3(value)) & mask;
    }

    @Override
    public long[] getCounts()
    {
        for (int i = 0; i < hashTable1.length; i += 2) {
            if (hashTable1[i] != 0) {
                put0(hashTable1[i], hashTable1[i + 1]);
            }
        }
        for (int i = 0; i < hashTable2.length; i += 2) {
            if (hashTable2[i] != 0) {
                put0(hashTable2[i], hashTable2[i + 1]);
            }
        }
        for (int i = 0; i < hashTable3.length; i += 2) {
            if (hashTable3[i] != 0) {
                put0(hashTable3[i], hashTable3[i + 1]);
            }
        }

        long[] counts = new long[entryCount0 * 2];
        int countsPosition = 0;
        if (zeroCount > 0) {
            counts[0] = 0;
            counts[1] = zeroCount;
            countsPosition = 2;
        }
        for (int i = 0; i < hashTable0.length && countsPosition < counts.length; i += 2) {
            if (hashTable0[i] != 0) {
                counts[countsPosition] = hashTable0[i];
                counts[countsPosition + 1] = hashTable0[i + 1];
                countsPosition += 2;
            }
        }
        return counts;
    }

    @Override
    public int getHashCollisions()
    {
        return hashCollisions0 + hashCollisions1 + hashCollisions2 + hashCollisions3;
    }
}
