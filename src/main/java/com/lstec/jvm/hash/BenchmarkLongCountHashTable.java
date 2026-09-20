package com.lstec.jvm.hash;

import com.google.common.base.Preconditions;
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
import org.openjdk.jmh.profile.DTraceAsmProfiler;
import org.openjdk.jmh.runner.RunnerException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

@State(Scope.Thread)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 10, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 10, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@BenchmarkMode(Mode.AverageTime)
public class BenchmarkLongCountHashTable
{
    /**
     * Rows every invocation processes, whatever the {@code rows} parameter is: a cell that
     * aggregates fewer rows per table just builds more tables. That keeps the score comparable
     * as ns per row across the whole matrix. Every {@code rows} value must divide it.
     */
    private static final int ROWS_PER_INVOCATION = 1024 * 1024 * 32;

    @SuppressWarnings("FieldMayBeFinal")
    @State(Scope.Thread)
    public static class BenchmarkData
    {
        // 4 -> table fits L1, 65536 -> 1MB fits L2, 1M -> 32MB fits L3, 8M -> 256MB out of cache
        @Param({"4", "1024", "65536", "1000000", "8000000"})
        private int groupCount = 4;

        // rows aggregated into a single table
        @Param({"32768", "262144", "2097152", "8388608", "33554432"})
        private int rows = ROWS_PER_INVOCATION;

        private List<LongAraayBlock> pages;
        private int expectedSize;
        private int tablesPerInvocation;

        @Setup
        public void setup()
        {
            // a table can never hold more distinct values than it is given rows
            expectedSize = Math.min(groupCount, rows);
            tablesPerInvocation = ROWS_PER_INVOCATION / rows;
            pages = createBigintPages(rows, groupCount);
        }

        public int getExpectedSize()
        {
            return expectedSize;
        }

        public int getTablesPerInvocation()
        {
            return tablesPerInvocation;
        }

        private static List<LongAraayBlock> createBigintPages(int positionCount, int groupCount)
        {
            int pageSize = 8 * 1024;
            long[] current = new long[pageSize];
            ImmutableList.Builder<LongAraayBlock> pages = ImmutableList.builder();
            int currentPosition = 0;
            for (int i = 0; i < positionCount; i++) {
                int rand = ThreadLocalRandom.current().nextInt(groupCount) + 1; // + 1 to avoid 0 value for tests
                current[currentPosition++] = rand;
                if (currentPosition == pageSize) {
                    pages.add(new LongAraayBlock(current, currentPosition));
                    current = new long[pageSize];
                    currentPosition = 0;
                }
            }

            pages.add(new LongAraayBlock(current, currentPosition));

            return pages.build();
        }

        public List<LongAraayBlock> getPages()
        {
            return pages;
        }
    }

    @SuppressWarnings("FieldMayBeFinal")
    @State(Scope.Thread)
    public static class PipelineData
    {
        @Param({"128"})
        private int batchSize = PipelinedLongCountHashTable.DEFAULT_BATCH_SIZE;

        public int getBatchSize()
        {
            return batchSize;
        }
    }

    @SuppressWarnings("FieldMayBeFinal")
    @State(Scope.Thread)
    public static class BucketData
    {
        @Param({"0.5"})
        private float fillRatio = BucketedLongCountHashTable.DEFAULT_FILL_RATIO;

        public float getFillRatio()
        {
            return fillRatio;
        }
    }

    @Benchmark
    @OperationsPerInvocation(ROWS_PER_INVOCATION)
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public long longCountHashTable(BenchmarkData data)
    {
        long checksum = 0;
        for (int table = 0; table < data.getTablesPerInvocation(); table++) {
            LongCountHashTable hashTable = new ScalarLongCountHashTable(data.getExpectedSize());
            for (LongAraayBlock page : data.getPages()) {
                hashTable.putBlock(page);
            }
            checksum += hashTable.getCounts().length;
        }
        return checksum;
    }

    @Benchmark
    @OperationsPerInvocation(ROWS_PER_INVOCATION)
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public Object vectorLongCountHashTable(BenchmarkData data)
    {
        LongCountHashTable hashTable = new VectorizedLongCountHashTable(data.getExpectedSize());

        for (LongAraayBlock page : data.getPages()) {
            hashTable.putBlock(page);
        }
        Preconditions.checkArgument(hashTable.getHashCollisions() == 0, "got %s collisions", hashTable.getHashCollisions());

        return hashTable.getCounts();
    }

    @Benchmark
    @OperationsPerInvocation(ROWS_PER_INVOCATION)
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public long pipelinedLongCountHashTable(BenchmarkData data, PipelineData pipelineData)
    {
        long checksum = 0;
        for (int table = 0; table < data.getTablesPerInvocation(); table++) {
            LongCountHashTable hashTable = new PipelinedLongCountHashTable(data.getExpectedSize(), pipelineData.getBatchSize());
            for (LongAraayBlock page : data.getPages()) {
                hashTable.putBlock(page);
            }
            checksum += hashTable.getCounts().length;
        }
        return checksum;
    }

    @Benchmark
    @OperationsPerInvocation(ROWS_PER_INVOCATION)
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public long radixLongCountHashTable(BenchmarkData data)
    {
        long checksum = 0;
        for (int table = 0; table < data.getTablesPerInvocation(); table++) {
            LongCountHashTable hashTable = new RadixLongCountHashTable(data.getExpectedSize());
            for (LongAraayBlock page : data.getPages()) {
                hashTable.putBlock(page);
            }
            checksum += hashTable.getCounts().length;
        }
        return checksum;
    }

    @SuppressWarnings("FieldMayBeFinal")
    @State(Scope.Thread)
    public static class WindowData
    {
        @Param({"16384"})
        private int subTableEntries = WindowedRadixLongCountHashTable.DEFAULT_SUB_TABLE_ENTRIES;

        @Param({"32768"})
        private int windowRowsPerPartition = WindowedRadixLongCountHashTable.DEFAULT_WINDOW_ROWS_PER_PARTITION;

        public int getSubTableEntries()
        {
            return subTableEntries;
        }

        public int getWindowRowsPerPartition()
        {
            return windowRowsPerPartition;
        }
    }

    @Benchmark
    @OperationsPerInvocation(ROWS_PER_INVOCATION)
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public long windowedRadixLongCountHashTable(BenchmarkData data, WindowData windowData)
    {
        long checksum = 0;
        for (int table = 0; table < data.getTablesPerInvocation(); table++) {
            LongCountHashTable hashTable = new WindowedRadixLongCountHashTable(
                    data.getExpectedSize(), windowData.getSubTableEntries(), windowData.getWindowRowsPerPartition());
            for (LongAraayBlock page : data.getPages()) {
                hashTable.putBlock(page);
            }
            checksum += hashTable.getCounts().length;
        }
        return checksum;
    }

    @Benchmark
    @OperationsPerInvocation(ROWS_PER_INVOCATION)
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public long bucketedLongCountHashTable(BenchmarkData data, BucketData bucketData)
    {
        long checksum = 0;
        for (int table = 0; table < data.getTablesPerInvocation(); table++) {
            LongCountHashTable hashTable = new BucketedLongCountHashTable(
                    data.getExpectedSize(), bucketData.getFillRatio(), BucketedLongCountHashTable.DEFAULT_BATCH_SIZE);
            for (LongAraayBlock page : data.getPages()) {
                hashTable.putBlock(page);
            }
            checksum += hashTable.getCounts().length;
        }
        return checksum;
    }

    public static void main(String[] args)
            throws RunnerException
    {
        BenchmarkData benchmarkData = new BenchmarkData();
        benchmarkData.setup();
        new BenchmarkLongCountHashTable().pipelinedLongCountHashTable(benchmarkData, new PipelineData());
        String profilerOutputDir = profilerOutputDir();
        Benchmarks.benchmark(BenchmarkLongCountHashTable.class)
                .withOptions(optionsBuilder -> optionsBuilder
                                .warmupIterations(10)
                                .measurementIterations(10)
//                        .addProfiler(AsyncProfiler.class, String.format("dir=%s;output=text;output=flamegraph", profilerOutputDir))
//                        .addProfiler(DTraceAsmProfiler.class, String.format("hotThreshold=0.05;tooBigThreshold=3000;saveLog=true;saveLogTo=%s", profilerOutputDir, profilerOutputDir))
                                .jvmArgsPrepend("--enable-preview")
                                .jvmArgs("-Xmx10g")
                                .jvmArgsAppend("--add-modules=jdk.incubator.vector")
//                        .forks(0)
                )
                // vectorLongCountHashTable overcounts and throws above 16 entries per sub table, so it is not comparable yet
                .includeMethod("longCountHashTable|pipelinedLongCountHashTable|radixLongCountHashTable|windowedRadixLongCountHashTable|bucketedLongCountHashTable")
                .run();

        File dir = new File(profilerOutputDir);
        if (dir.list().length == 0) {
            dir.delete();
        }
    }

    private static String profilerOutputDir()
    {
        try {
            String jmhDir = "jmh/long-count-hash-table";
            new File(jmhDir).mkdirs();
            String outDir = jmhDir + "/" + String.valueOf(Files.list(Paths.get(jmhDir))
                    .map(path -> Integer.parseInt(path.getFileName().toString()) + 1)
                    .sorted(Comparator.reverseOrder())
                    .findFirst().orElse(0));
            new File(outDir).mkdirs();
            return outDir;
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
