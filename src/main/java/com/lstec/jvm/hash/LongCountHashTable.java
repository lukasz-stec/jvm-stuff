package com.lstec.jvm.hash;

public interface LongCountHashTable
{
    void putBlock(LongAraayBlock block);

    long[] getCounts();

    int getHashCollisions();
}
