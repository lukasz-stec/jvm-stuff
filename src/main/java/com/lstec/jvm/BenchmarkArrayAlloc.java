package com.lstec.jvm;

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
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static com.lstec.jvm.Benchmarks.benchmark;

/**
 * Benchmark                  (arraySize)  Mode  Cnt   Score   Error  Units
 * BenchmarkArrayAlloc.alloc            1  avgt   10   5.368 ± 3.080  ns/op
 * BenchmarkArrayAlloc.alloc            4  avgt   10   4.419 ± 0.102  ns/op
 * BenchmarkArrayAlloc.alloc           16  avgt   10   6.571 ± 0.212  ns/op
 * BenchmarkArrayAlloc.alloc           64  avgt   10  17.839 ± 0.139  ns/op
 * BenchmarkArrayAlloc.alloc          256  avgt   10  72.004 ± 2.270  ns/op
 */
@State(Scope.Thread)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 30, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 10, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@BenchmarkMode(Mode.AverageTime)
public class BenchmarkArrayAlloc
{

    @Param({"1", "4", "16", "64", "256"})
    private int arraySize = 64;

    @Benchmark
//    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public Object alloc()
    {
        int[] array = new int[arraySize];
        return array;
    }

    public static void main(String[] args)
            throws RunnerException, IOException
    {
        String profilerOutputDir = profilerOutputDir();

        benchmark(BenchmarkArrayAlloc.class)
                .withOptions(optionsBuilder ->
                                optionsBuilder.forks(1)
//                                .param("arraySize", "64")
//                                .addProfiler(DTraceAsmProfiler.class, String.format("hotThreshold=0.1;tooBigThreshold=3000;saveLog=true;saveLogTo=%s", profilerOutputDir, profilerOutputDir))
                )
//                .includeMethod("cmov")
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
