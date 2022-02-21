package com.lstec.jvm.hash;

public class LongAraayBlock
{
    private final long[] values;
    private final int positionCount;

    public LongAraayBlock(long[] values, int positionCount)
    {
        this.values = values;
        this.positionCount = positionCount;
    }

    public long[] getValues()
    {
        return values;
    }

    public int getPositionCount()
    {
        return positionCount;
    }
}
