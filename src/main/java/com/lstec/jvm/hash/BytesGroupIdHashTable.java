package com.lstec.jvm.hash;

/**
 * Maps variable length keys to dense group ids, 0, 1, 2 ... in order of first appearance.
 * Unlike {@link LongCountHashTable} it aggregates nothing itself; the group id is what an
 * aggregation would use to index its own state.
 */
public interface BytesGroupIdHashTable
{
    /** Fills {@code groupIds[0..positionCount)} with the group of each key in the block. */
    void putBlock(VariableWidthBlock block, int[] groupIds);

    int getGroupCount();

    int getHashCollisions();
}
