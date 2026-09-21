package com.lstec.jvm.hash;

import org.openjdk.jmh.annotations.CompilerControl;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.nio.ByteOrder;
import java.util.Arrays;

import static it.unimi.dsi.fastutil.HashCommon.arraySize;
import static it.unimi.dsi.fastutil.HashCommon.murmurHash3;

/**
 * Same layout as {@link SimpleBytesGroupIdHashTable}, but a batch at a time so the two levels of
 * the lookup stop chasing each other.
 * <p>
 * A row there costs a slot miss and then an arena miss whose address came out of the slot, so the
 * second miss cannot overlap with the first and each row serialises two round trips. Measured on
 * the real geometry that pointer chase costs 52 ns/row against 37 for the same two misses at
 * independent addresses, while the byte comparison itself is only 3 ns.
 * <p>
 * What makes batching possible is that the probe chain never needs the key: the slot carries the
 * hash and the key length, and between them they settle every step except the final confirmation.
 * So a batch is walked in passes - hash, warm the slot lines, resolve every chain down to one
 * candidate slot, warm the arena lines for the whole batch at once, then confirm. The arena
 * misses of a batch are issued back to back instead of each waiting behind its own slot miss,
 * which measured 30.6 ns/row against 52.3 - better even than independent addresses, because a
 * tight loop of loads keeps more of them in flight than one interleaved with other work.
 * <p>
 * A candidate that fails confirmation is a genuine hash-and-length collision, which is rare
 * enough to hand to a scalar probe.
 * <p>
 * Growing is deferred to a batch boundary, because a rehash moves entries and would invalidate
 * the candidate slots already recorded for this batch. The table is never less than a quarter
 * empty at the fill ratio used, so a batch cannot fill it in the meantime.
 */
public class PipelinedBytesGroupIdHashTable
        implements BytesGroupIdHashTable
{
    public static final int DEFAULT_BATCH_SIZE = 64;

    private static final float FILL_RATIO = 0.75f;
    private static final int SLOT_LONGS = 2;
    /** Keeps a deferred rehash safe: a quarter of this exceeds any batch. */
    private static final int MIN_CAPACITY = 1024;
    private static final VarHandle LONG_VIEW = MethodHandles.byteArrayViewVarHandle(long[].class, ByteOrder.LITTLE_ENDIAN);
    private static final VarHandle INT_VIEW = MethodHandles.byteArrayViewVarHandle(int[].class, ByteOrder.LITTLE_ENDIAN);

    private long[] table;
    private int mask;
    private int slotMask;
    private int maxFill;
    private boolean needsRehash;

    private byte[] keys;
    private int keysSize;

    private int groupCount;
    private int hashCollisions;

    private final int[] hashes;
    /** Slot holding this row's candidate, or -1 when the chain ended on a free slot. */
    private final int[] candidateSlots;
    /** Where to insert, for rows whose chain ended on a free slot. */
    private final int[] pendingSlots;
    private final int[] arenaOffsets;
    private long prefetchSink;

    public PipelinedBytesGroupIdHashTable(int expectedGroupCount, int expectedKeyLength)
    {
        this(expectedGroupCount, expectedKeyLength, DEFAULT_BATCH_SIZE);
    }

    public PipelinedBytesGroupIdHashTable(int expectedGroupCount, int expectedKeyLength, int batchSize)
    {
        int capacity = Math.max(MIN_CAPACITY, arraySize(Math.max(1, expectedGroupCount), FILL_RATIO));
        table = new long[capacity * SLOT_LONGS];
        mask = capacity - 1;
        slotMask = table.length - 1;
        maxFill = (int) (capacity * FILL_RATIO);
        keys = new byte[Math.max(64, expectedGroupCount * Math.max(1, expectedKeyLength))];

        hashes = new int[batchSize];
        candidateSlots = new int[batchSize];
        pendingSlots = new int[batchSize];
        arenaOffsets = new int[batchSize];
    }

    @Override
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public void putBlock(VariableWidthBlock block, int[] groupIds)
    {
        byte[] slice = block.getSlice();
        int[] offsets = block.getOffsets();
        int positionCount = block.getPositionCount();
        int batchSize = hashes.length;
        for (int start = 0; start < positionCount; start += batchSize) {
            int size = Math.min(batchSize, positionCount - start);
            hashBatch(slice, offsets, start, size);
            prefetchSlots(size);
            resolveBatch(offsets, start, size);
            prefetchKeys(size);
            confirmBatch(slice, offsets, start, size, groupIds);
            if (needsRehash) {
                rehash();
                needsRehash = false;
            }
        }
    }

    private void hashBatch(byte[] slice, int[] offsets, int start, int size)
    {
        int[] hashes = this.hashes;
        for (int i = 0; i < size; i++) {
            int offset = offsets[start + i];
            hashes[i] = hash(slice, offset, offsets[start + i + 1] - offset);
        }
    }

    /** Independent addresses, no stores and no branches, so the slot misses overlap. */
    private void prefetchSlots(int size)
    {
        int[] hashes = this.hashes;
        long[] table = this.table;
        int mask = this.mask;
        long sink = 0;
        for (int i = 0; i < size; i++) {
            sink ^= table[(hashes[i] & mask) * SLOT_LONGS];
        }
        prefetchSink ^= sink;
    }

    /**
     * Walks every row's probe chain to a single candidate without reading one key byte: the hash
     * and the length in the slot decide every step. This pass is read only - it assigns no group
     * ids and writes nothing into the table - so that ids stay in order of first appearance,
     * which only holds if every id is handed out by the single row-ordered pass that follows.
     */
    private void resolveBatch(int[] offsets, int start, int size)
    {
        for (int i = 0; i < size; i++) {
            int length = offsets[start + i + 1] - offsets[start + i];
            int hash = hashes[i];
            int slot = (hash & mask) * SLOT_LONGS;
            candidateSlots[i] = -1;
            while (true) {
                long entry = table[slot];
                if (entry == 0) {
                    pendingSlots[i] = slot;
                    break;
                }
                long meta = table[slot + 1];
                if ((int) (entry >>> 32) == hash && (int) meta == length) {
                    candidateSlots[i] = slot;
                    arenaOffsets[i] = (int) (meta >>> 32);
                    break;
                }
                slot = (slot + SLOT_LONGS) & slotMask;
                hashCollisions++;
            }
        }
    }

    /** The point of the whole class: every candidate's arena line is fetched in one tight loop. */
    private void prefetchKeys(int size)
    {
        byte[] keys = this.keys;
        long sink = 0;
        for (int i = 0; i < size; i++) {
            if (candidateSlots[i] >= 0) {
                sink ^= keys[arenaOffsets[i]];
            }
        }
        prefetchSink ^= sink;
    }

    /**
     * The only pass that hands out group ids, in row order, on lines the prefetch passes have
     * already pulled in. Anything the read-only passes recorded is treated as a hint and
     * re-checked, because an earlier row of this same batch may have filled the slot since.
     */
    private void confirmBatch(byte[] slice, int[] offsets, int start, int size, int[] groupIds)
    {
        for (int i = 0; i < size; i++) {
            int offset = offsets[start + i];
            int length = offsets[start + i + 1] - offset;
            int slot = candidateSlots[i];
            if (slot < 0) {
                // the chain ended on a free slot; still free unless this batch filled it
                int pending = pendingSlots[i];
                groupIds[start + i] = table[pending] == 0
                        ? insert(pending, hashes[i], slice, offset, length)
                        : probeFrom(hashes[i], pending, slice, offset, length);
                continue;
            }
            int keyOffset = arenaOffsets[i];
            if (Arrays.equals(keys, keyOffset, keyOffset + length, slice, offset, offset + length)) {
                groupIds[start + i] = ((int) table[slot]) - 1;
            }
            else {
                // same hash and same length but a different key: carry on past it
                groupIds[start + i] = probeFrom(hashes[i], (slot + SLOT_LONGS) & slotMask, slice, offset, length);
            }
        }
    }

    /** Ordinary scalar probe from {@code slot} inclusive, on lines that are warm by now. */
    private int probeFrom(int hash, int slot, byte[] slice, int offset, int length)
    {
        while (true) {
            long entry = table[slot];
            if (entry == 0) {
                return insert(slot, hash, slice, offset, length);
            }
            long meta = table[slot + 1];
            if ((int) (entry >>> 32) == hash && (int) meta == length) {
                int keyOffset = (int) (meta >>> 32);
                if (Arrays.equals(keys, keyOffset, keyOffset + length, slice, offset, offset + length)) {
                    return ((int) entry) - 1;
                }
            }
            slot = (slot + SLOT_LONGS) & slotMask;
            hashCollisions++;
        }
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
            // deferred: rehashing now would move the candidates this batch has already recorded
            needsRehash = true;
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

    public long getKeyBytes()
    {
        return keysSize;
    }
}
