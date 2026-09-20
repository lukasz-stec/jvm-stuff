package com.lstec.jvm.hash;

/**
 * Variable length keys laid out back to back in one array, the way a columnar engine passes
 * them around: key {@code i} is {@code slice[offsets[i]..offsets[i + 1])}. Keeping them in a
 * single array rather than a {@code byte[][]} means the hot loop reads them sequentially and
 * pays no per key object header or indirection.
 */
public class VariableWidthBlock
{
    private final byte[] slice;
    private final int[] offsets;
    private final int positionCount;

    public VariableWidthBlock(byte[] slice, int[] offsets, int positionCount)
    {
        this.slice = slice;
        this.offsets = offsets;
        this.positionCount = positionCount;
    }

    public byte[] getSlice()
    {
        return slice;
    }

    /** {@code positionCount + 1} entries, so the length of the last key is available too. */
    public int[] getOffsets()
    {
        return offsets;
    }

    public int getPositionCount()
    {
        return positionCount;
    }
}
