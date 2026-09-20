package com.lstec.jvm.hash;

import org.openjdk.jmh.annotations.CompilerControl;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;

import static it.unimi.dsi.fastutil.HashCommon.arraySize;
import static it.unimi.dsi.fastutil.HashCommon.murmurHash3;

/**
 * Open addressing table from a variable length key to a dense group id.
 * <p>
 * A variable length key cannot live in the probe slot, so the table is split in two: a slot
 * holds the 32 bit hash, the group id and where the key sits, while the key bytes go into an
 * arena. A probe then touches two lines at most - the slot, and the arena only once the stored
 * hash already matches. With a full 32 bit hash a match is almost always real, so the byte
 * comparison confirms rather than filters. Note that the key's offset and length are kept in
 * the slot rather than in an array indexed by group id: the group id is as random as the hash,
 * so reading it anywhere else would add a third miss to every row.
 * <p>
 * A slot is two longs, {@code hash << 32 | groupId + 1} and {@code keyOffset << 32 | keyLength},
 * four slots to a 64 byte line. The low half of the first is at least 1 for an occupied slot,
 * so 0 means empty without a separate marker. Keeping the hash also makes growing cheap, since
 * rehashing never has to look at a key.
 * <p>
 * Hashing reads 8 bytes at a time and then re-reads the <em>last</em> 8 bytes, overlapping the
 * previous chunk, instead of walking the remainder a byte at a time. A byte wise tail costs
 * about 9 ns per key here, almost all of it branch misprediction, because its trip count varies
 * with every key - which is exactly what variable length means.
 */
public class SimpleBytesGroupIdHashTable
        implements BytesGroupIdHashTable
{
    private static final float FILL_RATIO = 0.75f;
    private static final int SLOT_LONGS = 2;
    private static final VarHandle LONG_VIEW = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle INT_VIEW = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

    private long[] table;
    private int mask;
    private int maxFill;

    /** Key bytes of every group, concatenated in group id order. */
    private byte[] keys;
    private int keysSize;

    private int groupCount;
    private int hashCollisions;

    public SimpleBytesGroupIdHashTable(int expectedGroupCount, int expectedKeyLength)
    {
        int capacity = arraySize(Math.max(1, expectedGroupCount), FILL_RATIO);
        table = new long[capacity * SLOT_LONGS];
        mask = capacity - 1;
        maxFill = (int) (capacity * FILL_RATIO);
        keys = new byte[Math.max(64, expectedGroupCount * Math.max(1, expectedKeyLength))];
    }

    @Override
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public void putBlock(VariableWidthBlock block, int[] groupIds)
    {
        byte[] slice = block.getSlice();
        int[] offsets = block.getOffsets();
        int positionCount = block.getPositionCount();
        for (int position = 0; position < positionCount; position++) {
            int offset = offsets[position];
            groupIds[position] = putKey(slice, offset, offsets[position + 1] - offset);
        }
    }

    private int putKey(byte[] slice, int offset, int length)
    {
        int hash = hash(slice, offset, length);
        int slot = (hash & mask) * SLOT_LONGS;
        while (true) {
            long entry = table[slot];
            if (entry == 0) {
                return insert(slot, hash, slice, offset, length);
            }
            // the stored hash filters almost everything; reaching the arena is the expensive part
            if ((int) (entry >>> 32) == hash) {
                long key = table[slot + 1];
                if (keyEquals((int) (key >>> 32), (int) key, slice, offset, length)) {
                    return ((int) entry) - 1;
                }
            }
            slot = (slot + SLOT_LONGS) & mask2();
            hashCollisions++;
        }
    }

    private int mask2()
    {
        return (mask << 1) | 1;
    }

    private boolean keyEquals(int keyOffset, int keyLength, byte[] slice, int offset, int length)
    {
        // Arrays.equals over ranges is intrinsified, so this is a vector compare
        return keyLength == length && Arrays.equals(keys, keyOffset, keyOffset + keyLength, slice, offset, offset + length);
    }

    private int insert(int slot, int hash, byte[] slice, int offset, int length)
    {
        while (keysSize + length > keys.length) {
            keys = Arrays.copyOf(keys, keys.length * 2);
        }
        System.arraycopy(slice, offset, keys, keysSize, length);

        int groupId = groupCount;
        table[slot] = ((long) hash << 32) | (groupId + 1);
        table[slot + 1] = ((long) keysSize << 32) | length;
        keysSize += length;
        groupCount = groupId + 1;
        if (groupCount > maxFill) {
            rehash();
        }
        return groupId;
    }

    /** Never touches a key: everything needed to place an entry is in the slot. */
    private void rehash()
    {
        long[] oldTable = table;
        int capacity = (mask + 1) * 2;
        table = new long[capacity * SLOT_LONGS];
        mask = capacity - 1;
        maxFill = (int) (capacity * FILL_RATIO);
        for (int oldSlot = 0; oldSlot < oldTable.length; oldSlot += SLOT_LONGS) {
            long entry = oldTable[oldSlot];
            if (entry != 0) {
                int slot = ((int) (entry >>> 32) & mask) * SLOT_LONGS;
                while (table[slot] != 0) {
                    slot = (slot + SLOT_LONGS) & mask2();
                }
                table[slot] = entry;
                table[slot + 1] = oldTable[oldSlot + 1];
            }
        }
    }

    private static int hash(byte[] slice, int offset, int length)
    {
        long hash = length * 0x9E3779B97F4A7C15L;
        int end = offset + length;
        if (length >= Long.BYTES) {
            for (int position = offset; position + Long.BYTES <= end; position += Long.BYTES) {
                hash ^= (long) LONG_VIEW.get(slice, position);
                hash *= 0xff51afd7ed558ccdL;
                hash ^= hash >>> 29;
            }
            // the last 8 bytes again, overlapping the chunk before it when length is not a
            // multiple of 8, which avoids a tail loop whose length varies per key
            hash ^= (long) LONG_VIEW.get(slice, end - Long.BYTES);
            hash *= 0xff51afd7ed558ccdL;
        }
        else if (length >= Integer.BYTES) {
            hash ^= (((long) (int) INT_VIEW.get(slice, offset)) << 32)
                    | ((int) INT_VIEW.get(slice, end - Integer.BYTES) & 0xFFFFFFFFL);
            hash *= 0xff51afd7ed558ccdL;
        }
        else {
            for (int position = offset; position < end; position++) {
                hash ^= slice[position] & 0xFF;
                hash *= 0x100000001b3L;
            }
        }
        return (int) murmurHash3(hash);
    }

    @Override
    public int getGroupCount()
    {
        return groupCount;
    }

    @Override
    public int getHashCollisions()
    {
        return hashCollisions;
    }

    /** Bytes held by the key arena, which grows with the total size of the distinct keys. */
    public long getKeyBytes()
    {
        return keysSize;
    }
}
