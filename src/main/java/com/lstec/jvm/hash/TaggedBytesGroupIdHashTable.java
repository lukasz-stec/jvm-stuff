package com.lstec.jvm.hash;

import org.openjdk.jmh.annotations.CompilerControl;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;

import static it.unimi.dsi.fastutil.HashCommon.arraySize;
import static it.unimi.dsi.fastutil.HashCommon.murmurHash3;

/**
 * Experiment rig: how many bits of the hash are worth keeping in the slot, and what growing costs.
 * <p>
 * The slot keeps the top {@code hashBits} of the hash as a tag. A probe only reads the key and
 * compares bytes when the tag matches, so the tag exists to keep collisions cheap: with 0 bits
 * every occupied slot on the probe path forces a full comparison, and with 32 bits a false match
 * is about one in four billion. Because the slot is two longs either way, the tag costs no space
 * here - narrower tags buy nothing and only lose filtering, which is what makes this worth
 * measuring rather than assuming.
 * <p>
 * The tag is taken from the <em>top</em> of the hash because the bottom drives the slot index,
 * so the two would otherwise carry the same information.
 * <p>
 * For variable length keys the tag turns out to matter far less than it looks, because the key
 * length is stored beside it and already rejects most colliding probes before the arena is
 * touched. {@link #getArenaCompares()} against {@link #getByteComparisons()} shows the split.
 * <p>
 * Growing is where a partial tag actually costs something. A slot's new index needs
 * {@code log2(newCapacity)} bits of hash, which a tag no longer has, so anything short of the
 * full 32 bits forces every key to be read back out of the arena and rehashed. With 32 bits the
 * rehash never touches a key. {@link #getRehashNanos()} and {@link #getRehashedEntries()} expose
 * the difference.
 * <p>
 * The counters cost a little on the hot path, so absolute numbers run slightly above
 * {@link SimpleBytesGroupIdHashTable}; every variant here pays them equally.
 */
public class TaggedBytesGroupIdHashTable
        implements BytesGroupIdHashTable
{
    private static final float FILL_RATIO = 0.75f;
    private static final int SLOT_LONGS = 2;
    private static final VarHandle LONG_VIEW = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle INT_VIEW = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

    private final int hashBits;
    private final int tagShift;

    private long[] table;
    private int mask;
    private int slotMask;
    private int maxFill;

    private byte[] keys;
    private int keysSize;

    private int groupCount;
    private int hashCollisions;

    private long byteComparisons;
    private long arenaCompares;
    private long falsePositives;
    private int rehashCount;
    private long rehashedEntries;
    private long rehashNanos;

    /**
     * @param hashBits bits of hash kept in the slot, 0 to 32
     * @param initialTableGroups groups the slot array is sized for, independent of the arena, so
     * growing the table can be forced without also growing the arena
     */
    public TaggedBytesGroupIdHashTable(int expectedGroupCount, int expectedKeyLength, int hashBits, int initialTableGroups)
    {
        if (hashBits < 0 || hashBits > 32) {
            throw new IllegalArgumentException("hashBits must be 0..32, got " + hashBits);
        }
        this.hashBits = hashBits;
        // 0 bits would be a shift of 32, which Java masks to 0, so the tag is forced to 0 instead
        this.tagShift = hashBits == 0 ? 0 : 32 - hashBits;

        int capacity = arraySize(Math.max(1, initialTableGroups), FILL_RATIO);
        table = new long[capacity * SLOT_LONGS];
        mask = capacity - 1;
        slotMask = table.length - 1;
        maxFill = (int) (capacity * FILL_RATIO);
        keys = new byte[Math.max(64, expectedGroupCount * Math.max(1, expectedKeyLength))];
    }

    private int tag(int hash)
    {
        return hashBits == 0 ? 0 : hash >>> tagShift;
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
        int tag = tag(hash);
        int slot = (hash & mask) * SLOT_LONGS;
        while (true) {
            long entry = table[slot];
            if (entry == 0) {
                return insert(slot, tag, slice, offset, length);
            }
            if ((int) (entry >>> 32) == tag) {
                long key = table[slot + 1];
                byteComparisons++;
                // the length sits in the slot next to the tag, so a length mismatch costs
                // nothing and never reaches the arena
                if ((int) key == length) {
                    arenaCompares++;
                    if (Arrays.equals(keys, (int) (key >>> 32), (int) (key >>> 32) + length, slice, offset, offset + length)) {
                        return ((int) entry) - 1;
                    }
                }
                falsePositives++;
            }
            slot = (slot + SLOT_LONGS) & slotMask;
            hashCollisions++;
        }
    }

    private int insert(int slot, int tag, byte[] slice, int offset, int length)
    {
        while (keysSize + length > keys.length) {
            keys = Arrays.copyOf(keys, keys.length * 2);
        }
        System.arraycopy(slice, offset, keys, keysSize, length);

        int groupId = groupCount;
        table[slot] = ((long) tag << 32) | (groupId + 1);
        table[slot + 1] = ((long) keysSize << 32) | length;
        keysSize += length;
        groupCount = groupId + 1;
        if (groupCount > maxFill) {
            rehash();
        }
        return groupId;
    }

    private void rehash()
    {
        long start = System.nanoTime();
        long[] oldTable = table;
        int capacity = (mask + 1) * 2;
        table = new long[capacity * SLOT_LONGS];
        mask = capacity - 1;
        slotMask = table.length - 1;
        maxFill = (int) (capacity * FILL_RATIO);

        int moved = 0;
        for (int oldSlot = 0; oldSlot < oldTable.length; oldSlot += SLOT_LONGS) {
            long entry = oldTable[oldSlot];
            if (entry == 0) {
                continue;
            }
            long key = oldTable[oldSlot + 1];
            int hash;
            if (hashBits == 32) {
                // the whole hash is in the slot, so the key is never touched
                hash = (int) (entry >>> 32);
            }
            else {
                // a tag cannot say where the entry belongs now: read the key back and rehash it
                int keyOffset = (int) (key >>> 32);
                hash = hash(keys, keyOffset, (int) key);
            }
            int slot = (hash & mask) * SLOT_LONGS;
            while (table[slot] != 0) {
                slot = (slot + SLOT_LONGS) & slotMask;
            }
            table[slot] = entry;
            table[slot + 1] = key;
            moved++;
        }
        rehashCount++;
        rehashedEntries += moved;
        rehashNanos += System.nanoTime() - start;
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

    /** Full byte comparisons attempted, the thing the tag exists to avoid. */
    public long getByteComparisons()
    {
        return byteComparisons;
    }

    /** Of {@link #getByteComparisons()}, those whose length also matched so the arena was read. */
    public long getArenaCompares()
    {
        return arenaCompares;
    }

    /** Comparisons the tag let through that turned out to be a different key. */
    public long getFalsePositives()
    {
        return falsePositives;
    }

    public int getRehashCount()
    {
        return rehashCount;
    }

    public long getRehashedEntries()
    {
        return rehashedEntries;
    }

    public long getRehashNanos()
    {
        return rehashNanos;
    }
}
