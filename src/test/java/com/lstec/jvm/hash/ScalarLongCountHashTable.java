package com.lstec.jvm.hash;

import static it.unimi.dsi.fastutil.HashCommon.arraySize;
import static it.unimi.dsi.fastutil.HashCommon.murmurHash3;

public class ScalarLongCountHashTable
        implements LongCountHashTable
{
    private static final float FILL_RATIO = 0.75f;

    private final long[] hashTable;
    private final int hashCapacity;
    private final int mask;
    private int zeroCount;
    private int hashCollisions;
    private int entryCount;

    public ScalarLongCountHashTable(int expectedSize)
    {
        hashCapacity = arraySize(expectedSize, FILL_RATIO);
        hashTable = new long[hashCapacity * 2]; // value + count
        mask = hashTable.length - 1;
    }

    @Override
    public void put(LongAraayBlock block)
    {
        long[] values = block.getValues();
        for (int i = 0; i < block.getPositionCount(); i++) {
            put(values[i]);
        }
    }

    void put(long value)
    {
        if (value == 0) {
            entryCount += zeroCount == 0 ? 1 : 0;
            zeroCount++;
            return;
        }

        int position = position(value);
        while (true) {
            long current = hashTable[position];
            if (current == 0) {
                break;
            }

            if (value == current) {
                // increase count
                hashTable[position + 1]++;
                return;
            }

            // increment position and mask to handle wrap around
            position = (position + 2) & mask;
            hashCollisions++;
        }

        // new entry
        hashTable[position] = value;
        hashTable[position + 1] = 1;
        entryCount++;
    }

    private int position(long value)
    {
        return ((int) murmurHash3(value)) & mask;
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
