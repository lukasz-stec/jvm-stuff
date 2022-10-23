package com.lstec.jvm;

import com.google.common.base.Verify;
import it.unimi.dsi.fastutil.HashCommon;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.CompilerControl;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OperationsPerInvocation;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.profile.DTraceAsmProfiler;
import org.openjdk.jmh.profile.JavaFlightRecorderProfiler;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.TimeValue;

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
@Warmup(iterations = 20, time = 1)
@Measurement(iterations = 20, time = 1)
@BenchmarkMode(Mode.AverageTime)
public class BenchmarkMethodInlineJfr
{
    private static final int ITERATIONS = 1000 * 1000;

    @SuppressWarnings("FieldMayBeFinal")
    @State(Scope.Thread)
    public static class BenchmarkData
    {
        private static final Random RANDOM = new Random(435672143);
        public final long[] data = new long[ITERATIONS];
        public final int[] positions = new int[data.length];

        @Setup
        public void setup()
        {
            for (int i = 0; i < ITERATIONS; i++) {
                data[i] = RANDOM.nextLong();
                positions[i] = RANDOM.nextInt(data.length);
            }
        }

        private long inlinedMethod(int i)
        {
            long x = data[i];
            for (int j = 0; j < 10; j++) {
                x ^= x >>> 33;
                x *= 0xff51afd7ed558ccdL;
                x ^= x >>> 33;
                x *= 0xc4ceb9fe1a85ec53L;
                x ^= x >>> 33;
                x ^= x >>> data[(i + 1) % data.length];
            }
            return x;
        }

        private long inlinedMethod2(int i)
        {
            long x = 1;
            for (int j = 0; j < 100; j++) {
                int index = (i + j) % positions.length;
                x = x + data[positions[index]];
            }
            return x;
        }

        @CompilerControl(CompilerControl.Mode.DONT_INLINE)
        private long notInlinedMethod(int i)
        {
            return HashCommon.murmurHash3(data[i]) / data[i];
        }
    }

    @Benchmark
    @OperationsPerInvocation(ITERATIONS)
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public Object inlined(BenchmarkData data)
    {
        double result = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            result = data.inlinedMethod2(i);
        }
        return result;
    }

    @Benchmark
    @OperationsPerInvocation(ITERATIONS)
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public Object notInlined(BenchmarkData data)
    {
        double result = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            result = data.notInlinedMethod(i);
        }
        return result;
    }

    public static void main(String[] args)
            throws RunnerException, IOException
    {
        String profilerOutputDir = profilerOutputDir();

        try {
            benchmark(BenchmarkMethodInlineJfr.class)
                    .withOptions(optionsBuilder -> optionsBuilder
                                    .measurementTime(TimeValue.seconds(10))
//                                    .measurementTime(TimeValue.seconds(1))
//                        .jvmArgsAppend("-XX:ConditionalMoveLimit=10")
//                                    .param("matchRatio", "0.5")
                                    .addProfiler(DTraceAsmProfiler.class, String.format("hotThreshold=0.1;tooBigThreshold=3000;saveLog=true;saveLogTo=%s", profilerOutputDir, profilerOutputDir))
                                    .addProfiler(JavaFlightRecorderProfiler.class, String.format("dir=%s", profilerOutputDir, profilerOutputDir))
                    )
                    .includeMethod("inlined")
                    .run();
        }
        finally {
            File dir = new File(profilerOutputDir);
            if (dir.list().length == 0) {
                Verify.verify(dir.delete(), "%s not deleted", dir);
            }
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
