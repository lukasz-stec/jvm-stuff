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

@State(Scope.Thread)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 30, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 10, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@BenchmarkMode(Mode.AverageTime)
public class BenchmarkCMOV
{
    private static final int ITERATIONS = 1000 * 1000;

    @SuppressWarnings("FieldMayBeFinal")
    @State(Scope.Thread)
    public static class BenchmarkData
    {
        private static final Random RANDOM = new Random(2675837);
        public final int[] array = new int[ITERATIONS];
        public final boolean[] match = new boolean[ITERATIONS];
        @Param({"0", "0.2", "0.5", "0.8", "1"})
        private double matchRatio = 0.5;

        @Setup
        public void setup()
        {
            double matchThreshold = matchRatio * 1000;
            for (int i = 0; i < ITERATIONS; i++) {
                array[i] = RANDOM.nextInt();
                match[i] = RANDOM.nextInt(1000) < matchThreshold;
            }
        }
    }

    @Benchmark
    @OperationsPerInvocation(ITERATIONS)
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public Object cmov(BenchmarkData data)
    {
        int[] array = data.array;
        boolean[] match = data.match;
        for (int i = 0; i < ITERATIONS; i++) {
            array[i] = match[i] ? -1 : array[i];
        }
        return array;
    }

    @Benchmark
    @OperationsPerInvocation(ITERATIONS)
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public Object trick(BenchmarkData data)
    {
        int[] array = data.array;
        boolean[] match = data.match;
        for (int i = 0; i < ITERATIONS; i++) {
            array[i] = (array[i] + 1) * (match[i] ? 1 : 0) - 1;
        }
        return array;
    }

    public static void main(String[] args)
            throws RunnerException, IOException
    {
        for (int i = 0; i < 5; i++) {
            BenchmarkData data = new BenchmarkData();
            data.setup();
            new BenchmarkCMOV().cmov(data);
            data.setup();
            new BenchmarkCMOV().trick(data);
        }
        String profilerOutputDir = profilerOutputDir();

        benchmark(BenchmarkCMOV.class)
                .withOptions(optionsBuilder -> optionsBuilder
//                        .jvmArgsAppend("-XX:ConditionalMoveLimit=10")
//                        .param("matchRatio", "0.5")
                        .addProfiler(DTraceAsmProfiler.class, String.format("hotThreshold=0.1;tooBigThreshold=3000;saveLog=true;saveLogTo=%s", profilerOutputDir, profilerOutputDir)))
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
