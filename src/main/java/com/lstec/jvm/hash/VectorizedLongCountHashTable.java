package com.lstec.jvm.hash;

import com.google.common.base.Preconditions;
import jdk.incubator.vector.IntVector;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.VectorMask;
import org.openjdk.jmh.annotations.CompilerControl;

import static it.unimi.dsi.fastutil.HashCommon.arraySize;
import static it.unimi.dsi.fastutil.HashCommon.murmurHash3;
import static jdk.incubator.vector.VectorOperators.EQ;
import static jdk.incubator.vector.VectorOperators.LSHL;
import static jdk.incubator.vector.VectorOperators.LSHR;
import static jdk.incubator.vector.VectorOperators.XOR;

public class VectorizedLongCountHashTable
        implements LongCountHashTable
{
    public static final LongVector ZERO_VECTOR = LongVector.zero(LongVector.SPECIES_256);
    public static final LongVector ONE_VECTOR = LongVector.broadcast(LongVector.SPECIES_256, 1);
    public static final IntVector INT_ONE_VECTOR = IntVector.broadcast(IntVector.SPECIES_256, 1);

    private static final float FILL_RATIO = 0.75f;

    private final long[] hashTable;
    private final int hashCapacity;
    private final int hashPositionMask;
    private final int hashTable1Start;
    private final int hashTable2Start;
    private final int hashTable3Start;
    private final LongVector hashTableStartOffsets;
    private final LongVector hashPositionMaskVector;
    private final IntVector intHashTableStartOffsets;
    private final IntVector intHashPositionMaskVector;
    private ArrayHashTable[] hashTables;

    public VectorizedLongCountHashTable(int expectedSize)
    {
        hashCapacity = arraySize(expectedSize, FILL_RATIO);
        hashTable = new long[hashCapacity * 2 * 4]; // 4 hash tables with value + count

        hashPositionMask = hashCapacity - 1;
        hashPositionMaskVector = LongVector.broadcast(LongVector.SPECIES_256, hashPositionMask);
        intHashPositionMaskVector = IntVector.broadcast(IntVector.SPECIES_256, hashPositionMask);
        hashTable1Start = hashCapacity;
        hashTable2Start = hashCapacity * 2;
        hashTable3Start = hashCapacity * 3;
        hashTableStartOffsets = LongVector.fromArray(LongVector.SPECIES_256, new long[] {0, hashTable1Start, hashTable2Start, hashTable3Start}, 0);
        intHashTableStartOffsets = IntVector.fromArray(IntVector.SPECIES_256,
                new int[] {0, hashTable1Start, hashTable2Start, hashTable3Start, 0, hashTable1Start, hashTable2Start, hashTable3Start},
                0);

        hashTables = new ArrayHashTable[4];
        hashTables[0] = new ArrayHashTable(hashTable, 0, hashCapacity);
        hashTables[1] = new ArrayHashTable(hashTable, hashTable1Start, hashCapacity);
        hashTables[2] = new ArrayHashTable(hashTable, hashTable2Start, hashCapacity);
        hashTables[3] = new ArrayHashTable(hashTable, hashTable3Start, hashCapacity);
    }

    static class BatchBuffers
    {
        private final int positions[];
        private final int toProcess[][];
        private final int toProcess0[];
        private final int toProcess1[];
        private final int toProcess2[];
        private final int toProcess3[];
        public final int[] countPositions;
        private final int toProcessIndex[];

        public BatchBuffers(int batchSize)
        {
            positions = new int[batchSize];
            countPositions = new int[batchSize];
            toProcess0 = new int[batchSize];
            toProcess1 = new int[batchSize];
            toProcess2 = new int[batchSize];
            toProcess3 = new int[batchSize];
            toProcess = new int[4][];
            toProcess[0] = toProcess0;
            toProcess[1] = toProcess1;
            toProcess[2] = toProcess2;
            toProcess[3] = toProcess3;

            toProcessIndex = new int[4];
        }

        public void reset()
        {
            toProcessIndex[0] = 0;
            toProcessIndex[1] = 0;
            toProcessIndex[2] = 0;
            toProcessIndex[3] = 0;
        }

        public boolean anythingToProcess()
        {
            return toProcessIndex[0] + toProcessIndex[1] + toProcessIndex[2] + toProcessIndex[3] > 0;
        }

        public void toProcess(int hashTableIndex, int index)
        {
            toProcess[hashTableIndex][toProcessIndex[hashTableIndex]++] = index;
        }
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    @Override
    public void putBlock(LongAraayBlock block)
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
            ArrayHashTable hashTable0 = hashTables[0];
            put(values, batchStart, lastBatchSize - batchSizeNot4, batchBuffers);
            batchStart += lastBatchSize - batchSizeNot4;
            for (int i = batchStart; i < block.getPositionCount(); i++) {
                hashTable0.put(position(values[i]), values[i]);
            }
        }
    }

    void put(long[] values, int startPosition, int batchSize, BatchBuffers batchBuffers)
    {
        // TODO lysy: handle 0
//        if (value == 0) {
//            entryCount += zeroCount == 0 ? 1 : 0;
//            zeroCount++;
//            return;
//        }
        batchBuffers.reset();

        int[] positions = hashPositionsScalar(values, startPosition, batchSize, batchBuffers);
        int[] countPositions = storeCountPositions(batchBuffers, positions);

        for (int i = 0; i < batchSize; i += 4) {
            processSmallBatch(values, startPosition, batchBuffers, positions, countPositions, i);
        }

        if (batchBuffers.anythingToProcess()) {
            processConflicts(values, positions, batchBuffers);
        }
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    private void processSmallBatch(long[] values, int startPosition, BatchBuffers batchBuffers, int[] positions, int[] countPositions, int i)
    {
        LongVector valueVector = LongVector.fromArray(LongVector.SPECIES_256, values, startPosition + i);
        LongVector currentValuesVector = LongVector.fromArray(LongVector.SPECIES_256, hashTable, 0, positions, i);
        VectorMask<Long> toInc = valueVector.compare(EQ, currentValuesVector);

        LongVector currentCountsVector = LongVector.fromArray(LongVector.SPECIES_256, hashTable, 0, countPositions, i);
        LongVector incremented = currentCountsVector.add(ONE_VECTOR, toInc);
        // intoArray is extremely slow, lots of code generated
//        incremented.intoArray(hashTable, 0, countPositions, i);
        hashTable[countPositions[i]] = incremented.lane(0);
        hashTable[countPositions[i + 1]] = incremented.lane(1);
        hashTable[countPositions[i + 2]] = incremented.lane(2);
        hashTable[countPositions[i + 3]] = incremented.lane(3);

        boolean anyNewOrConflict = toInc.not().anyTrue();
        if (anyNewOrConflict) {
            newOrConflict(values, startPosition, batchBuffers, positions, toInc, currentValuesVector, i);
        }
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    private int[] storeCountPositions(BatchBuffers batchBuffers, int[] positions)
    {
        int[] countPositions = batchBuffers.countPositions;
        for (int i = 0; i < countPositions.length; i++) {
            countPositions[i] = positions[i] + 1;
        }
        return countPositions;
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    private int[] hashPositionsScalar(long[] values, int startPosition, int batchSize, BatchBuffers batchBuffers)
    {
        int[] positions = batchBuffers.positions;
        for (int i = 0; i < batchSize; i += 4) {
            positions[i] = position(values[startPosition + i]);
            positions[i + 1] = position(values[startPosition + i + 1]) + hashTable1Start;
            positions[i + 2] = position(values[startPosition + i + 2]) + hashTable2Start;
            positions[i + 3] = position(values[startPosition + i + 3]) + hashTable3Start;
        }
        return positions;
    }

    public static final LongVector LONG_33 = LongVector.broadcast(LongVector.SPECIES_256, 33);
    public static final LongVector VECTOR_0xff51afd7ed558ccd = LongVector.broadcast(LongVector.SPECIES_256, 0xff51afd7ed558ccdL);
    public static final LongVector VECTOR_0xc4ceb9fe1a85ec53 = LongVector.broadcast(LongVector.SPECIES_256, 0xc4ceb9fe1a85ec53L);

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    private int[] hashPositionsSimdLong(long[] values, int startPosition, int batchSize, BatchBuffers batchBuffers)
    {
        int[] positions = batchBuffers.positions;
        for (int i = 0; i < batchSize; i += 4) {
            LongVector positionVector = LongVector.fromArray(LongVector.SPECIES_256, values, startPosition + i);
            // murmur 3 long
//            x ^= x >>> 33;
//            x *= 0xff51afd7ed558ccdL;
//            x ^= x >>> 33;
//            x *= 0xc4ceb9fe1a85ec53L;
//            x ^= x >>> 33;
            positionVector = positionVector.lanewise(LSHR, LONG_33).lanewise(XOR, positionVector);
            positionVector = positionVector.mul(VECTOR_0xff51afd7ed558ccd);
            positionVector = positionVector.lanewise(LSHR, LONG_33).lanewise(XOR, positionVector);
            positionVector = positionVector.mul(VECTOR_0xc4ceb9fe1a85ec53);
            positionVector = positionVector.lanewise(LSHR, LONG_33).lanewise(XOR, positionVector);
            positionVector = positionVector.add(hashTableStartOffsets);

            //(((int) murmurHash3(value)) & hashPositionMask) * 2;
            positionVector = positionVector.and(hashPositionMaskVector).lanewise(LSHL, ONE_VECTOR);

            positions[i] = (int) positionVector.lane(0);
            positions[i + 1] = (int) positionVector.lane(1);
            positions[i + 2] = (int) positionVector.lane(2);
            positions[i + 3] = (int) positionVector.lane(3);
        }

        return positions;
    }

    public static final IntVector INT_16 = IntVector.broadcast(IntVector.SPECIES_256, 16);
    public static final IntVector INT_13 = IntVector.broadcast(IntVector.SPECIES_256, 13);
    public static final IntVector INT_0x85ebca6b = IntVector.broadcast(IntVector.SPECIES_256, 0x85ebca6b);
    public static final IntVector INT_0xc2b2ae35 = IntVector.broadcast(IntVector.SPECIES_256, 0xc2b2ae35);

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    private int[] hashPositionsSimdInt(long[] values, int startPosition, int batchSize, BatchBuffers batchBuffers)
    {
        // worse hash than long, has collision for values 1 and 3 nad hashCapacity 8
        int[] positions = batchBuffers.positions;
        for (int i = 0; i < batchSize; i++) {
            positions[i] = (int) values[startPosition + i];
        }
        for (int i = 0; i < batchSize; i += 8) {
            IntVector positionVector = IntVector.fromArray(IntVector.SPECIES_256, positions, i);
            // murmur 3 int
//            x ^= x >>> 16;
//            x *= 0x85ebca6b;
//            x ^= x >>> 13;
//            x *= 0xc2b2ae35;
//            x ^= x >>> 16;
            positionVector = positionVector.lanewise(LSHR, INT_16).lanewise(XOR, positionVector);
            positionVector = positionVector.mul(INT_0x85ebca6b);
            positionVector = positionVector.lanewise(LSHR, INT_13).lanewise(XOR, positionVector);
            positionVector = positionVector.mul(INT_0xc2b2ae35);
            positionVector = positionVector.lanewise(LSHR, INT_16).lanewise(XOR, positionVector);
            positionVector = positionVector.add(intHashTableStartOffsets);

            //(((int) murmurHash3(value)) & hashPositionMask) * 2;
            positionVector = positionVector.and(intHashPositionMaskVector).lanewise(LSHL, INT_ONE_VECTOR);

//            positions[i] = positionVector.lane(0);
//            positions[i + 1] = positionVector.lane(1);
//            positions[i + 2] = positionVector.lane(2);
//            positions[i + 3] = positionVector.lane(3);
            positionVector.intoArray(positions, i);
        }

        return positions;
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    private void newOrConflict(long[] values, int startPosition, BatchBuffers batchBuffers, int[] positions,
            VectorMask<Long> toInc, LongVector currentValuesVector, int i)
    {
        VectorMask<Long> toInsertNew = currentValuesVector.compare(EQ, ZERO_VECTOR);
        toInsertNew = toInsertNew.and(toInc.not());

        VectorMask<Long> notInc = toInc.not();

        for (int hashTableIndex = 0; hashTableIndex < 4; hashTableIndex++) {
            if (toInsertNew.laneIsSet(hashTableIndex)) {
                hashTables[hashTableIndex].insert(positions[i + hashTableIndex], values[startPosition + i + hashTableIndex]);
            }
            else if (notInc.laneIsSet(hashTableIndex)) {
                positions[i + hashTableIndex] = hashTables[hashTableIndex].nextPosition(positions[i + hashTableIndex]);
                batchBuffers.toProcess(hashTableIndex, i);
            }
        }
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    private void processConflicts(long[] values, int[] positions, BatchBuffers batchBuffers)
    {
        for (int hashTableIndex = 0; hashTableIndex < 4; hashTableIndex++) {

            for (int i = 0; i < batchBuffers.toProcessIndex[hashTableIndex]; i++) {
                int toProcessIndex = batchBuffers.toProcess[hashTableIndex][i];
                int position = positions[toProcessIndex];
                long value = values[toProcessIndex];
                hashTables[hashTableIndex].put(position, value);
            }
        }
    }

    private int position(long value)
    {
        return (((int) murmurHash3(value)) & hashPositionMask) * 2;
    }

    @Override
    public long[] getCounts()
    {
        // populate hash table 0 with all entries from tables 1, 2, 3
        ArrayHashTable hashTable0 = hashTables[0];
        for (int i = hashTable1Start; i < hashTable.length; i += 2) {
            if (hashTable[i] != 0) {
                hashTable0.put(position(this.hashTable[i]), this.hashTable[i], this.hashTable[i + 1]);
            }
        }

        return hashTable0.getCounts();
    }

    static class ArrayHashTable
    {
        private final long[] hashTable;
        private final int startOffset;
        private final int hashCapacity;
        private int zeroCount;
        private int hashCollisions;
        private int entryCount;
        private final int endIndex;

        ArrayHashTable(long[] hashTable, int startOffset, int hashCapacity)
        {

            this.hashTable = hashTable;
            this.startOffset = startOffset;
            this.hashCapacity = hashCapacity;
            this.endIndex = startOffset + hashCapacity * 2;
        }

        public void insert(int position, long value)
        {
            hashTable[position] = value;
            hashTable[position + 1] = 1;
            entryCount++;
            Preconditions.checkArgument(entryCount <= 16);
        }

        public int nextPosition(int position)
        {
            position = position + 2;
            if (position == endIndex) {
                position = startOffset;
            }
            hashCollisions++;
            return position;
        }

        private boolean put(int position, long value)
        {
            return put(position, value, 1);
        }

        private boolean put(int position, long value, long count)
        {
            while (true) {
                long current = hashTable[position];
                if (current == 0) {
                    break;
                }

                if (value == current) {
                    // increase count
                    hashTable[position + 1] += count;
                    return true;
                }

                // increment position and mask to handle wrap around
                position = nextPosition(position);
            }

            // new entry
            insert(position, value);
            return false;
        }

        public long[] getCounts()
        {
            long[] counts = new long[entryCount * 2];
            int countsPosition = 0;

            if (zeroCount > 0) {
                counts[0] = 0;
                counts[1] = zeroCount;
                countsPosition = 2;
            }
            for (int i = 0; i < hashCapacity * 2 && countsPosition < counts.length; i += 2) {
                if (hashTable[i] != 0) {
                    counts[countsPosition] = hashTable[i];
                    counts[countsPosition + 1] = hashTable[i + 1];
                    countsPosition += 2;
                }
            }
            return counts;
        }
    }

    @Override
    public int getHashCollisions()
    {
        int hashCollisions = 0;
        for (int i = 0; i < 4; i++) {
            hashCollisions += hashTables[i].hashCollisions;
        }
        return hashCollisions;
    }
}
