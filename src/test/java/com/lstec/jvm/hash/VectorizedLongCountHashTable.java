package com.lstec.jvm.hash;

import org.openjdk.jmh.annotations.CompilerControl;

import static it.unimi.dsi.fastutil.HashCommon.arraySize;
import static it.unimi.dsi.fastutil.HashCommon.murmurHash3;

public class VectorizedLongCountHashTable
        implements LongCountHashTable
{
    private static final float FILL_RATIO = 0.75f;

    private final long[] hashTable0;
    private final long[] hashTable1;
    private final long[] hashTable2;
    private final long[] hashTable3;
    private final int hashCapacity;
    private final int cycleMask;
    private final int hashPositionMask;
    private int zeroCount;
    private int hashCollisions0;
    private int hashCollisions1;
    private int hashCollisions2;
    private int hashCollisions3;
    private int entryCount0;
    private int entryCount1;
    private int entryCount2;
    private int entryCount3;

    public VectorizedLongCountHashTable(int expectedSize)
    {
        hashCapacity = arraySize(expectedSize, FILL_RATIO);
        hashTable0 = new long[hashCapacity * 2]; // value + count
        hashTable1 = new long[hashCapacity * 2]; // value + count
        hashTable2 = new long[hashCapacity * 2]; // value + count
        hashTable3 = new long[hashCapacity * 2]; // value + count

        cycleMask = hashTable0.length - 1;
        hashPositionMask = hashCapacity - 1;
    }

    static class BatchBuffers
    {
        private final int positions[];
        private final long currentValues[];
        private final int toProcess0[];
        private final int toProcess1[];
        private final int toProcess2[];
        private final int toProcess3[];
        private int toProcess0Index = 0;
        private int toProcess1Index = 0;
        private int toProcess2Index = 0;
        private int toProcess3Index = 0;

        public BatchBuffers(int batchSize)
        {
            positions = new int[batchSize];
            currentValues = new long[batchSize];
            toProcess0 = new int[batchSize];
            toProcess1 = new int[batchSize];
            toProcess2 = new int[batchSize];
            toProcess3 = new int[batchSize];
        }

        public void reset()
        {
            toProcess0Index = 0;
            toProcess1Index = 0;
            toProcess2Index = 0;
            toProcess3Index = 0;
        }

        public boolean anythingToProcess()
        {
            return toProcess0Index + toProcess1Index + toProcess2Index + toProcess3Index > 0;
        }
    }

    @Override
    public void put(LongAraayBlock block)
    {
        long[] values = block.getValues();
        int batchSize = 64;
        BatchBuffers batchBuffers = new BatchBuffers(batchSize);

        int batchStart = 0;
        while (batchStart + batchSize <= block.getPositionCount()) {
            put(values, batchStart, batchSize, batchBuffers);
            batchStart += batchSize;
        }

        int lastBatchSize = Math.min(batchSize, block.getPositionCount() - batchStart);
        int batchSizeNot4 = lastBatchSize % 4;

        if (lastBatchSize > 0) {
            put(values, batchStart, lastBatchSize - batchSizeNot4, batchBuffers);
            batchStart += lastBatchSize - batchSizeNot4;
            for (int i = batchStart; i < block.getPositionCount(); i++) {
                put0(position(values[i]), values[i]);
            }
        }
    }

    void put(long[] values, int startPosition, int batchSize, BatchBuffers batchBuffers)
    {
        batchBuffers.reset();
        // TODO lysy: handle 0
//        if (value == 0) {
//            entryCount += zeroCount == 0 ? 1 : 0;
//            zeroCount++;
//            return;
//        }

//        long[] currentValues = batchBuffers.currentValues;
//        int toProcess0[] = batchBuffers.toProcess0;
//        int toProcess1[] = batchBuffers.toProcess1;
//        int toProcess2[] = batchBuffers.toProcess2;
//        int toProcess3[] = batchBuffers.toProcess3;
        int[] positions = hashPositions(values, startPosition, batchSize, batchBuffers);

//        for (int i = 0; i < batchSize; i += 4) {
//            currentValues[i] = hashTable0[positions[i]];
//            currentValues[i + 1] = hashTable1[positions[i + 1]];
//            currentValues[i + 2] = hashTable2[positions[i + 2]];
//            currentValues[i + 3] = hashTable3[positions[i + 3]];
//        }

        boolean[] toInc = new boolean[4];
        boolean[] toInsertNew = new boolean[4];
        for (int i = 0; i < batchSize; i += 4) {
            toInc[0] = values[startPosition + i] == hashTable0[positions[i]];
            toInc[1] = values[startPosition + i + 1] == hashTable1[positions[i + 1]];
            toInc[2] = values[startPosition + i + 2] == hashTable2[positions[i + 2]];
            toInc[3] = values[startPosition + i + 3] == hashTable3[positions[i + 3]];
            toInsertNew[0] = hashTable0[positions[i]] == 0;
            toInsertNew[1] = hashTable1[positions[i + 1]] == 0;
            toInsertNew[2] = hashTable2[positions[i + 2]] == 0;
            toInsertNew[3] = hashTable3[positions[i + 3]] == 0;
            toInsertNew[0] &= !toInc[0];
            toInsertNew[1] &= !toInc[1];
            toInsertNew[2] &= !toInc[2];
            toInsertNew[3] &= !toInc[3];

//            hashTable0[positions[i] + 1] = toInc[0] ? hashTable0[positions[i] + 1] + 1 : hashTable0[positions[i] + 1];
//            hashTable1[positions[i + 1] + 1] = toInc[1] ? hashTable1[positions[i + 1] + 1] + 1 : hashTable1[positions[i + 1] + 1];
//            hashTable2[positions[i + 2] + 1] = toInc[2] ? hashTable2[positions[i + 2] + 1] + 1 : hashTable2[positions[i + 2] + 1];
//            hashTable3[positions[i + 3] + 1] = toInc[3] ? hashTable3[positions[i + 3] + 1] + 1 : hashTable3[positions[i + 3] + 1];

            hashTable0[positions[i] + 1] = (hashTable0[positions[i] + 1] + 1) * (toInc[0] ? 1 : 0) - 1;
            hashTable1[positions[i + 1] + 1] = (hashTable1[positions[i + 1] + 1] + 1) * (toInc[0] ? 1 : 0) - 1;
            hashTable2[positions[i + 2] + 1] = (hashTable2[positions[i + 2] + 1] + 1) * (toInc[0] ? 1 : 0) - 1;
            hashTable3[positions[i + 3] + 1] = (hashTable3[positions[i + 3] + 1] + 1) * (toInc[0] ? 1 : 0) - 1;
            
            boolean anyNewOrConflict = !toInc[0] | !toInc[1] | !toInc[2] | !toInc[3];
            if (anyNewOrConflict) {
                newOrConflict(values, startPosition, batchBuffers, positions, toInc, toInsertNew, i);
            }
        }

        if (batchBuffers.anythingToProcess()) {
            processConflicts(values, positions, batchBuffers);
        }
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    private int[] hashPositions(long[] values, int startPosition, int batchSize, BatchBuffers batchBuffers)
    {
        int[] positions = batchBuffers.positions;
        for (int i = 0; i < batchSize; i++) {
            positions[i] = position(values[startPosition + i]);
        }
        return positions;
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    private void newOrConflict(long[] values, int startPosition, BatchBuffers batchBuffers, int[] positions, boolean[] toInc, boolean[] toInsertNew, int i)
    {
        if (toInsertNew[0]) {
            hashTable0[positions[i]] = values[startPosition + i];
            hashTable0[positions[i] + 1] = 1;
            entryCount0++;
        }
        else if (!toInc[0]) {
            // increment position and mask to handle wrap around
            positions[i] = (positions[i] + 2) & cycleMask;
            batchBuffers.toProcess0[batchBuffers.toProcess0Index++] = i;
            hashCollisions0++;
        }

        if (toInsertNew[1]) {
            hashTable1[positions[i + 1]] = values[startPosition + i + 1];
            hashTable1[positions[i + 1] + 1] = 1;
            entryCount1++;
        }
        else if (!toInc[1]) {
            // increment position and mask to handle wrap around
            positions[i + 1] = (positions[i + 1] + 2) & cycleMask;
            batchBuffers.toProcess1[batchBuffers.toProcess1Index++] = i;
            hashCollisions1++;
        }

        if (toInsertNew[2]) {
            hashTable2[positions[i + 2]] = values[startPosition + i + 2];
            hashTable2[positions[i + 2] + 1] = 1;
            entryCount2++;
        }
        else if (!toInc[2]) {
            // increment position and mask to handle wrap around
            positions[i + 2] = (positions[i + 2] + 2) & cycleMask;
            batchBuffers.toProcess2[batchBuffers.toProcess2Index++] = i;
            hashCollisions2++;
        }

        if (toInsertNew[3]) {
            hashTable3[positions[i + 3]] = values[startPosition + i + 3];
            hashTable3[positions[i + 3] + 1] = 1;
            entryCount3++;
        }
        else if (!toInc[3]) {
            // increment position and mask to handle wrap around
            positions[i + 3] = (positions[i + 3] + 2) & cycleMask;
            batchBuffers.toProcess3[batchBuffers.toProcess3Index++] = i;
            hashCollisions3++;
        }
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    private void processConflicts(long[] values, int[] positions, BatchBuffers batchBuffers)
    {
        for (int i = 0; i < batchBuffers.toProcess0Index; i++) {
            int toProcessIndex = batchBuffers.toProcess0[i];
            int position = positions[toProcessIndex];
            long value = values[toProcessIndex];
            put0(position, value);
        }

        for (int i = 0; i < batchBuffers.toProcess1Index; i++) {
            int toProcessIndex = batchBuffers.toProcess1[i];
            int position = positions[toProcessIndex];
            long value = values[toProcessIndex];
            put1(position, value);
        }

        for (int i = 0; i < batchBuffers.toProcess2Index; i++) {
            int toProcessIndex = batchBuffers.toProcess2[i];
            int position = positions[toProcessIndex];
            long value = values[toProcessIndex];
            put2(position, value);
        }

        for (int i = 0; i < batchBuffers.toProcess3Index; i++) {
            int toProcessIndex = batchBuffers.toProcess3[i];
            int position = positions[toProcessIndex];
            long value = values[toProcessIndex];
            put3(position, value);
        }
    }

    private boolean put3(int position, long value)
    {
        while (true) {
            long current = hashTable3[position];
            if (current == 0) {
                break;
            }

            if (value == current) {
                // increase count
                hashTable3[position + 1]++;
                return true;
            }

            // increment position and mask to handle wrap around
            position = (position + 3) & cycleMask;
            hashCollisions3++;
        }

        // new entry
        hashTable3[position] = value;
        hashTable3[position + 3] = 1;
        entryCount3++;
        return false;
    }

    private boolean put2(int position, long value)
    {
        while (true) {
            long current = hashTable2[position];
            if (current == 0) {
                break;
            }

            if (value == current) {
                // increase count
                hashTable2[position + 1]++;
                return true;
            }

            // increment position and mask to handle wrap around
            position = (position + 2) & cycleMask;
            hashCollisions2++;
        }

        // new entry
        hashTable2[position] = value;
        hashTable2[position + 2] = 1;
        entryCount2++;
        return false;
    }

    private boolean put1(int position, long value)
    {
        while (true) {
            long current = hashTable1[position];
            if (current == 0) {
                break;
            }

            if (value == current) {
                // increase count
                hashTable1[position + 1]++;
                return true;
            }

            // increment position and mask to handle wrap around
            position = (position + 2) & cycleMask;
            hashCollisions1++;
        }

        // new entry
        hashTable1[position] = value;
        hashTable1[position + 1] = 1;
        entryCount1++;
        return false;
    }

    private boolean put0(long value, long count)
    {
        return put0(position(value), value, count);
    }

    private boolean put0(int position, long value)
    {
        return put0(position, value, 1);
    }

    private boolean put0(int position, long value, long count)
    {
        while (true) {
            long current = hashTable0[position];
            if (current == 0) {
                break;
            }

            if (value == current) {
                // increase count
                hashTable0[position + 1]++;
                return true;
            }

            // increment position and mask to handle wrap around
            position = (position + 2) & cycleMask;
            hashCollisions0++;
        }

        // new entry
        hashTable0[position] = value;
        hashTable0[position + 1] = count;
        entryCount0++;
        return false;
    }

    private int position(long value)
    {
        return (((int) murmurHash3(value)) & hashPositionMask) * 2;
    }

    @Override
    public long[] getCounts()
    {
        for (int i = 0; i < hashTable1.length; i += 2) {
            if (hashTable1[i] != 0) {
                put0(hashTable1[i], hashTable1[i + 1]);
            }
        }
        for (int i = 0; i < hashTable2.length; i += 2) {
            if (hashTable2[i] != 0) {
                put0(hashTable2[i], hashTable2[i + 1]);
            }
        }
        for (int i = 0; i < hashTable3.length; i += 2) {
            if (hashTable3[i] != 0) {
                put0(hashTable3[i], hashTable3[i + 1]);
            }
        }

        long[] counts = new long[entryCount0 * 2];
        int countsPosition = 0;
        if (zeroCount > 0) {
            counts[0] = 0;
            counts[1] = zeroCount;
            countsPosition = 2;
        }
        for (int i = 0; i < hashTable0.length && countsPosition < counts.length; i += 2) {
            if (hashTable0[i] != 0) {
                counts[countsPosition] = hashTable0[i];
                counts[countsPosition + 1] = hashTable0[i + 1];
                countsPosition += 2;
            }
        }
        return counts;
    }

    @Override
    public int getHashCollisions()
    {
        return hashCollisions0 + hashCollisions1 + hashCollisions2 + hashCollisions3;
    }
}
