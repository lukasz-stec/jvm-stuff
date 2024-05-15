package com.lstec.jvm;

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
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;

import java.util.Random;
import java.util.concurrent.TimeUnit;

import static com.lstec.jvm.Benchmarks.benchmark;

/**
 * Benchmark                                 (arraySize)  Mode  Cnt   Score   Error  Units
 * BenchmarkRandomRead.batchRandomRead          10000000  avgt   10  11.135 ± 0.921  ns/op
 * 
 * Benchmark                            (arraySize)  Mode  Cnt   Score   Error  Units
 * BenchmarkRandomRead.batchRandomRead            4  avgt   20   0.442 ± 0.007  ns/op
 * BenchmarkRandomRead.batchRandomRead       100000  avgt   20   1.656 ± 0.027  ns/op
 * BenchmarkRandomRead.batchRandomRead      8000000  avgt   20  11.235 ± 0.515  ns/op
 *
 * MBP Intel(R) Core(TM) i7-4770HQ CPU @ 2.20GHz
 * Benchmark                                     (arraySize)  Mode  Cnt   Score   Error  Units
 * BenchmarkRandomRead.batchRandomRead                     4  avgt   20   0.420 ± 0.022  ns/op
 * BenchmarkRandomRead.batchRandomRead                100000  avgt   20   2.831 ± 0.032  ns/op
 * BenchmarkRandomRead.batchRandomRead               8000000  avgt   20  12.442 ± 2.063  ns/op
 * BenchmarkRandomRead.batchRandomReadUnRolled8            4  avgt   20   0.492 ± 0.028  ns/op
 * BenchmarkRandomRead.batchRandomReadUnRolled8       100000  avgt   20   2.956 ± 0.054  ns/op
 * BenchmarkRandomRead.batchRandomReadUnRolled8      8000000  avgt   20  11.794 ± 1.331  ns/op
 *
 * Benchmark                            (arraySize)  Mode  Cnt   Score   Error  Units
 * BenchmarkRandomRead.batchRandomRead      4000000  avgt   10  10.292 ± 0.164  ns/op
 */
@State(Scope.Thread)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 10, time = 1)
@BenchmarkMode(Mode.AverageTime)
public class BenchmarkRandomRead
{

    private static final int POSITIONS = 1024 * 1024 * 8;

    @State(Scope.Thread)
    public static class BenchmarkData
    {

        private static final Random RANDOM = new Random(6236527);

        @Param({"4", "100000", "8000000"})
        private int arraySize = 4;

        private long[] rows;
        private int[] positions;

        @Setup
        public void setup()
        {
            rows = new long[arraySize];
            for (int i = 0; i < rows.length; i++) {
                rows[i] = RANDOM.nextLong();
            }
            positions = new int[POSITIONS];
            for (int i = 0; i < positions.length; i++) {
                positions[i] = RANDOM.nextInt(arraySize);
            }
        }
    }

    @Benchmark
    @OperationsPerInvocation(POSITIONS)
    public Object batchRandomRead(BenchmarkData data)
    {
        long[] rows = data.rows;
        int[] positions = data.positions;
        long result = 0;
        for (int position : positions) {
            result += rows[position];
        }
        return result;
    }

    @Benchmark
    @OperationsPerInvocation(POSITIONS)
    public Object batchRandomReadUnRolled8(BenchmarkData data)
    {
        long[] rows = data.rows;
        int[] positions = data.positions;
        int i = 0;
        long result0 = 0;
        int result1 = 0;
        int result2 = 0;
        int result3 = 0;
        int result4 = 0;
        int result5 = 0;
        int result6 = 0;
        int result7 = 0;
        for (; i <= (positions.length - 8); i += 8) {
            result0 += rows[positions[i]];
            result1 += rows[positions[i + 1]];
            result2 += rows[positions[i + 2]];
            result3 += rows[positions[i + 3]];
            result4 += rows[positions[i + 4]];
            result5 += rows[positions[i + 5]];
            result6 += rows[positions[i + 6]];
            result7 += rows[positions[i + 7]];
        }
        for (; i < positions.length; i++) {
            result0 += rows[i];
        }
        return result0 + result1 + result2 + result3 + result4 + result5 + result6 + result7;
    }

    public static void main(String[] args)
            throws Exception
    {
        benchmark(BenchmarkRandomRead.class)
                .includeMethod("batchRandomRead")
                .withProfilerOutputBaseDir("jmh/randomRead")
                .withOptions((ChainedOptionsBuilder optionsBuilder, String profilerOutputDir) ->
                                optionsBuilder.forks(1)
                                        .warmupIterations(20)
                                        .param("arraySize", "4000000")
//                                        .addProfiler(LinuxPerfNormProfiler.class)
//                                        .addProfiler(LinuxPerfAsmProfiler.class, String.format("hotThreshold=0.1;tooBigThreshold=3000;saveLog=true;saveLogTo=%s", profilerOutputDir, profilerOutputDir))
//                                        .addProfiler(AsyncProfiler.class, String.format("dir=%s;output=text;output=flamegraph;event=cache-misses;libPath=/home/ec2-user/async-profiler-2.8.3-linux-arm64/build/libasyncProfiler.so", profilerOutputDir))
//                                        .addProfiler(DTraceAsmProfiler.class, String.format("hotThreshold=0.1;tooBigThreshold=3000;saveLog=true;saveLogTo=%s", profilerOutputDir))
//                                        .addProfiler(AsyncProfiler.class, format("dir=%s;output=flamegraph;event=cpu", profilerOutputDir))
                )
                .run();
    }
}
