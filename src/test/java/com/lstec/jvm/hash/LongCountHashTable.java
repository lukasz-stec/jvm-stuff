package com.lstec.jvm.hash;

public interface LongCountHashTable
{
    void put(LongAraayBlock block);

    long[] getCounts();
}
