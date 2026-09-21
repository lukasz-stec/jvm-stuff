package com.lstec.jvm.hash;

import com.google.common.collect.ImmutableList;
import com.lstec.jvm.Benchmarks;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.CompilerControl;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.RunnerException;

import java.util.List;
import java.util.Random;
import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 5, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 8, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@BenchmarkMode(Mode.AverageTime)
public class BenchmarkBytesGroupIdHashTable
{
    private static final int POSITIONS = 1024 * 1024 * 4;
    private static final int PAGE_SIZE = 8 * 1024;
    private static final int MIN_KEY_LENGTH = 4;

    @SuppressWarnings("FieldMayBeFinal")
    @State(Scope.Thread)
    public static class BenchmarkData
    {
        // 1024 -> slots fit L1, 100k -> 1MB of slots, 3M -> 32MB of slots plus a bigger arena
        @Param({"1024", "100000", "3000000"})
        private int groupCount = 1024;

        // key lengths are uniform in [4, maxKeyLength], so the average is about half of this
        @Param({"32", "64", "128"})
        private int maxKeyLength = 32;


        private List<VariableWidthBlock> pages;
        private int[] groupIds;
        private int averageKeyLength;

        @Setup
        public void setup()
        {
            Random random = new Random(0);
            byte[][] keys = new byte[groupCount][];
            long totalKeyLength = 0;
            for (int group = 0; group < groupCount; group++) {
                int length = MIN_KEY_LENGTH + random.nextInt(maxKeyLength - MIN_KEY_LENGTH + 1);
                byte[] key = new byte[length];
                // the group index goes in the first four bytes so every key is distinct
                for (int i = 0; i < length; i++) {
                    key[i] = (byte) (i < 4 ? group >>> (i * 8) : random.nextInt());
                }
                keys[group] = key;
                totalKeyLength += length;
            }
            averageKeyLength = (int) (totalKeyLength / groupCount);

            pages = createPages(keys, random);
            groupIds = new int[PAGE_SIZE];
        }

        private static List<VariableWidthBlock> createPages(byte[][] keys, Random random)
        {
            ImmutableList.Builder<VariableWidthBlock> pages = ImmutableList.builder();
            for (int start = 0; start < POSITIONS; start += PAGE_SIZE) {
                int positionCount = Math.min(PAGE_SIZE, POSITIONS - start);
                byte[][] pageKeys = new byte[positionCount][];
                int totalLength = 0;
                for (int position = 0; position < positionCount; position++) {
                    byte[] key = keys[random.nextInt(keys.length)];
                    pageKeys[position] = key;
                    totalLength += key.length;
                }

                byte[] slice = new byte[totalLength];
                int[] offsets = new int[positionCount + 1];
                int offset = 0;
                for (int position = 0; position < positionCount; position++) {
                    byte[] key = pageKeys[position];
                    System.arraycopy(key, 0, slice, offset, key.length);
                    offset += key.length;
                    offsets[position + 1] = offset;
                }
                pages.add(new VariableWidthBlock(slice, offsets, positionCount));
            }
            return pages.build();
        }

        public List<VariableWidthBlock> getPages()
        {
            return pages;
        }

        public int[] getGroupIds()
        {
            return groupIds;
        }

        public int getGroupCount()
        {
            return groupCount;
        }

        public int getAverageKeyLength()
        {
            return averageKeyLength;
        }
    }

    @Benchmark
    @OperationsPerInvocation(POSITIONS)
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public Object bytesGroupIdHashTable(BenchmarkData data)
    {
        BytesGroupIdHashTable hashTable = new SimpleBytesGroupIdHashTable(data.getGroupCount(), data.getAverageKeyLength());

        int[] groupIds = data.getGroupIds();
        for (VariableWidthBlock page : data.getPages()) {
            hashTable.putBlock(page, groupIds);
        }

        return hashTable;
    }

    @Benchmark
    @OperationsPerInvocation(POSITIONS)
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public Object inlineKeyBytesGroupIdHashTable(BenchmarkData data)
    {
        BytesGroupIdHashTable hashTable = new InlineKeyBytesGroupIdHashTable(data.getGroupCount(), data.getAverageKeyLength());

        int[] groupIds = data.getGroupIds();
        for (VariableWidthBlock page : data.getPages()) {
            hashTable.putBlock(page, groupIds);
        }

        return hashTable;
    }

    public static void main(String[] args)
            throws RunnerException
    {
        BenchmarkData benchmarkData = new BenchmarkData();
        benchmarkData.setup();
        new BenchmarkBytesGroupIdHashTable().bytesGroupIdHashTable(benchmarkData);

        Benchmarks.benchmark(BenchmarkBytesGroupIdHashTable.class)
                .withOptions(optionsBuilder -> optionsBuilder
                        .jvmArgs("-Xmx16g"))
                .run();
    }
}
