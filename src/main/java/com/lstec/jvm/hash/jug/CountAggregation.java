package com.lstec.jvm.hash.jug;

import it.unimi.dsi.fastutil.HashCommon;

import static java.lang.Long.rotateLeft;

public interface CountAggregation
{
    void incrementCount(Object row);

    void batchIncrementCount(Object[] rows);

    long getCount(Object row);

    int getCollisions();

    static int hash(int value)
    {
        return (int) hash((long) value);
    }

    static long hash(long value)
    {
        
        // xxHash64 mix
        return rotateLeft(value * 0xC2B2AE3D27D4EB4FL, 31) * 0x9E3779B185EBCA87L;
    }
}
