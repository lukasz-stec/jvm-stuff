package com.lstec.jvm.hash;

import org.openjdk.jmh.annotations.CompilerControl;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;

import static it.unimi.dsi.fastutil.HashCommon.arraySize;
import static it.unimi.dsi.fastutil.HashCommon.murmurHash3;

/**
 * Like {@link SimpleBytesGroupIdHashTable}, but short keys live in the slot instead of an arena.
 * <p>
 * Measuring the arena version showed the byte comparison, not the memory, was the dominant cost:
 * 17 ns/row of 30 even with 1024 groups, where the whole arena sits in L1. {@code Arrays.equals}
 * is intrinsified and does inline, but {@code vectorizedMismatch} has a fixed setup cost that
 * swamps a 4..16 byte key, and it means touching a second array at an unrelated offset.
 * <p>
 * So the slot carries the key's first 8 bytes and its last 8 bytes. For any key of at most
 * {@link #INLINE_BYTES} those two reads overlap and cover it completely, so the length together
 * with the two words determines the key exactly and the comparison is two long compares that
 * never leave the slot's own cache line. Longer keys still use the arena, with the two words
 * acting as a filter ahead of it.
 * <p>
 * The same two words feed the hash, so the length dependent branches are paid once per key
 * rather than once for hashing and again for comparing. A key of at most {@link #INLINE_BYTES}
 * is hashed entirely from them.
 * <p>
 * A slot is four longs, two to a 64 byte line: {@code hash << 32 | groupId + 1},
 * {@code keyLength << 32 | keyOffset}, then the head and tail words. That is twice the slot of
 * the arena version, but short keys no longer need the arena at all, so the totals are closer
 * than they look.
 */
public class InlineKeyBytesGroupIdHashTable
        implements BytesGroupIdHashTable
{
    /** Keys up to this length are covered completely by the head and tail words. */
    public static final int INLINE_BYTES = 2 * Long.BYTES;

    private static final float FILL_RATIO = 0.75f;
    private static final int SLOT_LONGS = 4;
    private static final VarHandle LONG_VIEW = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle INT_VIEW = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

    private long[] table;
    private int mask;
    private int slotMask;
    private int maxFill;

    /** Holds only the keys too long to inline. */
    private byte[] keys;
    private int keysSize;

    private int groupCount;
    private int hashCollisions;

    public InlineKeyBytesGroupIdHashTable(int expectedGroupCount, int expectedKeyLength)
    {
        int capacity = arraySize(Math.max(1, expectedGroupCount), FILL_RATIO);
        table = new long[capacity * SLOT_LONGS];
        mask = capacity - 1;
        slotMask = table.length - 1;
        maxFill = (int) (capacity * FILL_RATIO);
        int longKeyBytes = expectedKeyLength <= INLINE_BYTES ? 0 : expectedGroupCount * expectedKeyLength;
        keys = new byte[Math.max(64, longKeyBytes)];
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
        // the only length dependent branch in the whole probe; hashing and comparing both reuse it
        long head;
        long tail;
        if (length >= Long.BYTES) {
            head = (long) LONG_VIEW.get(slice, offset);
            tail = (long) LONG_VIEW.get(slice, offset + length - Long.BYTES);
        }
        else if (length >= Integer.BYTES) {
            head = (((long) (int) INT_VIEW.get(slice, offset)) << 32)
                    | ((int) INT_VIEW.get(slice, offset + length - Integer.BYTES) & 0xFFFFFFFFL);
            tail = head;
        }
        else {
            long word = 0;
            for (int position = offset; position < offset + length; position++) {
                word = (word << 8) | (slice[position] & 0xFF);
            }
            head = word;
            tail = word;
        }

        int hash = hash(head, tail, length, slice, offset);
        int slot = (hash & mask) * SLOT_LONGS;
        while (true) {
            long entry = table[slot];
            if (entry == 0) {
                return insert(slot, hash, head, tail, slice, offset, length);
            }
            if ((int) (entry >>> 32) == hash) {
                long meta = table[slot + 1];
                if ((int) (meta >>> 32) == length && table[slot + 2] == head && table[slot + 3] == tail) {
                    // head and tail overlap, so for a short key they are the whole key
                    if (length <= INLINE_BYTES) {
                        return ((int) entry) - 1;
                    }
                    int keyOffset = (int) meta;
                    if (Arrays.equals(keys, keyOffset, keyOffset + length, slice, offset, offset + length)) {
                        return ((int) entry) - 1;
                    }
                }
            }
            slot = (slot + SLOT_LONGS) & slotMask;
            hashCollisions++;
        }
    }

    private int insert(int slot, int hash, long head, long tail, byte[] slice, int offset, int length)
    {
        int keyOffset = 0;
        if (length > INLINE_BYTES) {
            while (keysSize + length > keys.length) {
                keys = Arrays.copyOf(keys, Math.max(64, keys.length * 2));
            }
            System.arraycopy(slice, offset, keys, keysSize, length);
            keyOffset = keysSize;
            keysSize += length;
        }

        int groupId = groupCount;
        table[slot] = ((long) hash << 32) | (groupId + 1);
        table[slot + 1] = ((long) length << 32) | keyOffset;
        table[slot + 2] = head;
        table[slot + 3] = tail;
        groupCount = groupId + 1;
        if (groupCount > maxFill) {
            rehash();
        }
        return groupId;
    }

    private void rehash()
    {
        long[] oldTable = table;
        int capacity = (mask + 1) * 2;
        table = new long[capacity * SLOT_LONGS];
        mask = capacity - 1;
        slotMask = table.length - 1;
        maxFill = (int) (capacity * FILL_RATIO);
        for (int oldSlot = 0; oldSlot < oldTable.length; oldSlot += SLOT_LONGS) {
            long entry = oldTable[oldSlot];
            if (entry != 0) {
                int slot = ((int) (entry >>> 32) & mask) * SLOT_LONGS;
                while (table[slot] != 0) {
                    slot = (slot + SLOT_LONGS) & slotMask;
                }
                table[slot] = entry;
                table[slot + 1] = oldTable[oldSlot + 1];
                table[slot + 2] = oldTable[oldSlot + 2];
                table[slot + 3] = oldTable[oldSlot + 3];
            }
        }
    }

    /** A key of at most {@link #INLINE_BYTES} is hashed from the two words alone. */
    private static int hash(long head, long tail, int length, byte[] slice, int offset)
    {
        long hash = length * 0x9E3779B97F4A7C15L;
        hash ^= head;
        hash *= 0xff51afd7ed558ccdL;
        hash ^= tail;
        hash *= 0xc4ceb9fe1a85ec53L;
        if (length > INLINE_BYTES) {
            // head and tail leave the middle uncovered, so mix that too
            int end = offset + length - Long.BYTES;
            for (int position = offset + Long.BYTES; position + Long.BYTES <= end; position += Long.BYTES) {
                hash ^= (long) LONG_VIEW.get(slice, position);
                hash *= 0xff51afd7ed558ccdL;
                hash ^= hash >>> 29;
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

    /** Bytes held by the arena, which only long keys use. */
    public long getKeyBytes()
    {
        return keysSize;
    }
}
