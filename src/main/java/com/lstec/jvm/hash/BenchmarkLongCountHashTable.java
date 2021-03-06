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
    private static final int POSITIONS = 1024 * 1024 * 8;

    @SuppressWarnings("FieldMayBeFinal")
    @State(Scope.Thread)
    public static class BenchmarkData
    {
        @Param({"4", "100000", "3000000"})
        private int groupCount = 4;

        private List<LongAraayBlock> pages;

        @Setup
        public void setup()
        {
            pages = createBigintPages(POSITIONS, groupCount);
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

    @Benchmark
    @OperationsPerInvocation(POSITIONS)
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public Object longCountHashTable(BenchmarkData data)
    {
        LongCountHashTable hashTable = new ScalarLongCountHashTable(data.groupCount);

        for (LongAraayBlock page : data.getPages()) {
            hashTable.putBlock(page);
        }

        return hashTable.getCounts();
    }

    @Benchmark
    @OperationsPerInvocation(POSITIONS)
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public Object vectorLongCountHashTable(BenchmarkData data)
    {
        LongCountHashTable hashTable = new VectorizedLongCountHashTable(data.groupCount);

        for (LongAraayBlock page : data.getPages()) {
            hashTable.putBlock(page);
        }
        Preconditions.checkArgument(hashTable.getHashCollisions() == 0, "got %s collisions", hashTable.getHashCollisions());

        return hashTable.getCounts();
    }

    public static void main(String[] args)
            throws RunnerException
    {
        BenchmarkData benchmarkData = new BenchmarkData();
        benchmarkData.setup();
        new BenchmarkLongCountHashTable().vectorLongCountHashTable((benchmarkData));
        String profilerOutputDir = profilerOutputDir();
        Benchmarks.benchmark(BenchmarkLongCountHashTable.class)
                .withOptions(optionsBuilder -> optionsBuilder
                                .param("groupCount", "4")
                                .warmupIterations(30)
                                .measurementIterations(10)
//                        .addProfiler(AsyncProfiler.class, String.format("dir=%s;output=text;output=flamegraph", profilerOutputDir))
                                .addProfiler(DTraceAsmProfiler.class, String.format("hotThreshold=0.05;tooBigThreshold=3000;saveLog=true;saveLogTo=%s", profilerOutputDir, profilerOutputDir))
                                .jvmArgsPrepend("--enable-preview")
                                .jvmArgs("-Xmx10g")
                                .jvmArgsAppend("--add-modules=jdk.incubator.vector")
//                        .forks(0)
                )
//                .includeMethod("vectorLongCountHashTable")
//                .includeMethod("longCountHashTable")
                .run();

        File dir = new File(profilerOutputDir);
        if (dir.list().length == 0) {
            dir.delete();
        }
    }

    private static String profilerOutputDir()
    {
        try {
            String jmhDir = "jmh";
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
