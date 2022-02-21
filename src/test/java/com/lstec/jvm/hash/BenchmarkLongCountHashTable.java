package com.lstec.jvm.hash;

import com.google.common.collect.ImmutableList;
import com.lstec.jvm.Benchmarks;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
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
    private static final int POSITIONS = 10_000_000;

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
                int rand = ThreadLocalRandom.current().nextInt(groupCount) + 1;
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
    public Object longCountHashTable(BenchmarkData data)
    {
        LongCountHashTable hashTable = new ScalarLongCountHashTable(data.groupCount);

        for (LongAraayBlock page : data.getPages()) {
            hashTable.put(page);
        }

        return hashTable.getCounts();
    }

    public static void main(String[] args)
            throws RunnerException
    {
        String profilerOutputDir = profilerOutputDir();
        Benchmarks.benchmark(BenchmarkLongCountHashTable.class)
                .withOptions(optionsBuilder -> optionsBuilder
//                        .param("groupCount", "4")
                        .warmupIterations(30)
                        .measurementIterations(10)
//                        .addProfiler(AsyncProfiler.class, String.format("dir=%s;output=text;output=flamegraph", profilerOutputDir))
                        .addProfiler(DTraceAsmProfiler.class, String.format("hotThreshold=0.1;tooBigThreshold=3000;saveLog=true;saveLogTo=%s", profilerOutputDir, profilerOutputDir))
                        .jvmArgs("-Xmx10g"))
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
