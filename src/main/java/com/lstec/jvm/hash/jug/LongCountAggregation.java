package com.lstec.jvm.hash.jug;

public interface LongCountAggregation
{
    void batchIncrementCount(long[] rows);

    long getCount(long row);

    int getCollisions();
}
