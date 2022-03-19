package com.lstec.jvm;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Param;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.BenchmarkParams;
import org.openjdk.jmh.runner.RunnerException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static com.lstec.jvm.Benchmarks.benchmark;

/**
 * Benchmark                   (arraySize)  (uniqueValues)  Mode  Cnt   Score   Error  Units
 * BenchmarkLongSort.baseline         8192               4  avgt   10   0.572 ± 0.014  ns/op
 * BenchmarkLongSort.baseline         8192            1024  avgt   10   0.566 ± 0.012  ns/op
 * BenchmarkLongSort.baseline         8192            8192  avgt   10   0.571 ± 0.013  ns/op
 * BenchmarkLongSort.baseline       100000               4  avgt   10   0.584 ± 0.015  ns/op
 * BenchmarkLongSort.baseline       100000            1024  avgt   10   0.591 ± 0.016  ns/op
 * BenchmarkLongSort.baseline       100000            8192  avgt   10   0.590 ± 0.016  ns/op
 * BenchmarkLongSort.sort             8192               4  avgt   10   5.467 ± 0.103  ns/op
 * BenchmarkLongSort.sort             8192            1024  avgt   10  37.918 ± 2.261  ns/op
 * BenchmarkLongSort.sort             8192            8192  avgt   10  39.614 ± 2.964  ns/op
 * BenchmarkLongSort.sort           100000               4  avgt   10   7.280 ± 0.293  ns/op
 * BenchmarkLongSort.sort           100000            1024  avgt   10  32.195 ± 2.535  ns/op
 * BenchmarkLongSort.sort           100000            8192  avgt   10  46.301 ± 1.061  ns/op
 */
@State(Scope.Thread)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 30, time = 100, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 10, time = 100, timeUnit = TimeUnit.MILLISECONDS)
@BenchmarkMode(Mode.AverageTime)
public class BenchmarkLongSort
{

    @State(Scope.Thread)
    @SuppressWarnings("unused")
    public static class Data
    {
        private static final Random random = new Random(1231255);

        @Param({"8192", "100000"})
        private int arraySize = 8192;

        //        @Param({"4", "16", "256", "1024", "8192"})
        @Param({"4", "1024", "8192"})
        private int uniqueValues = 1024;

        public long[][] arrays;

        private int currentArrayIndex;
        private long[] array;

        @Setup(value = Level.Trial)
        public void setup(BenchmarkParams benchmarkParams)
        {
            if (benchmarkParams != null) {
                benchmarkParams.setOpsPerInvocation(arraySize);
            }
            currentArrayIndex = 0;
            array = new long[arraySize];
            for (int j = 0; j < arraySize; j++) {
                array[j] = random.nextLong(uniqueValues);
            }
        }

        public long[] array()
        {
            return arrays[currentArrayIndex++];
        }
    }

    @Benchmark
    public Object sort(Data data)
    {
        long[] array = Arrays.copyOf(data.array, data.array.length);
        Arrays.sort(array);
        return array;
    }

    @Benchmark
    public Object baseline(Data data)
    {
        return Arrays.copyOf(data.array, data.array.length);
    }

    public static void main(String[] args)
            throws RunnerException, IOException
    {
        String profilerOutputDir = profilerOutputDir();

        benchmark(BenchmarkLongSort.class)
                .withOptions(optionsBuilder ->
                                optionsBuilder.forks(1)
//                                .param("arraySize", "64")
//                                        .addProfiler(DTraceAsmProfiler.class, String.format("hotThreshold=0.1;tooBigThreshold=3000;saveLog=true;saveLogTo=%s", profilerOutputDir, profilerOutputDir))
                )
//                .includeMethod("sort")
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
                    .map(path -> path.getFileName().toString())
                    .filter(path -> path.matches("\\d+"))
                    .map(path -> Integer.parseInt(path) + 1)
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
