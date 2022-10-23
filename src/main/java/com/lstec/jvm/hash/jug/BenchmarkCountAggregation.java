package com.lstec.jvm.hash.jug;

import org.openjdk.jmh.annotations.AuxCounters;
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
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.profile.AsyncProfiler;
import org.openjdk.jmh.profile.DTraceAsmProfiler;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;

import java.util.Random;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import static com.lstec.jvm.Benchmarks.benchmark;
import static java.lang.String.format;

/**
 *
 */
@State(Scope.Thread)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 10, time = 1)
@BenchmarkMode(Mode.AverageTime)
public class BenchmarkCountAggregation
{

    private static final int POSITIONS = 1024 * 1024 * 16;

    @State(Scope.Thread)
    @AuxCounters(AuxCounters.Type.EVENTS)
    public static class CollisionCounters
    {
        private int collisions;

        public double collisions()
        {
            return (double) collisions / POSITIONS;
        }

        public void addCollisions(int collisions)
        {
            this.collisions = collisions;
        }
    }

    @State(Scope.Thread)
    public static class LongBenchmarkData
    {

        private static final Random RANDOM = new Random(456247);

        @Param({"4", "100000", "3000000"})
        private int groupCount = 4;

        private long[] rows;

        @Setup
        public void setup()
        {
            rows = new long[POSITIONS];
            for (int i = 0; i < rows.length; i++) {
                rows[i] = RANDOM.nextLong(groupCount);
            }
        }
    }

    @SuppressWarnings("FieldMayBeFinal")
    @State(Scope.Thread)
    public static class BenchmarkData
    {
        private static final Random RANDOM = new Random(12345);
        @Param({"4", "100000", "3000000", "4000000"})
        private int groupCount = 4;

        @Param({"int", "long", "string"})
        private String type = "long";

        @Param({"true", "false"})
        private boolean polluted = false;

        private Object[] rows;

        @Setup
        public void setup(Blackhole blackhole)
        {
            rows = generateRows(POSITIONS, type, groupCount);
            if (polluted) {
                pollute(blackhole, CountAggregation_01::new);
                pollute(blackhole, CountAggregation_02::new);
                pollute(blackhole, CountAggregation_03::new);
            }
        }

        private void pollute(Blackhole blackhole, Function<Integer, CountAggregation> mapConstructor)
        {
            for (int i = 0; i < 10; i++) {
                CountAggregation intHashMap = mapConstructor.apply(4);
                intHashMap.batchIncrementCount(generateRows(10_000, "int", 4));
                blackhole.consume(intHashMap);
                CountAggregation longHashMap = mapConstructor.apply(4);
                longHashMap.batchIncrementCount(generateRows(10_000, "long", 4));
                blackhole.consume(longHashMap);
                CountAggregation stringHashMap = mapConstructor.apply(4);
                stringHashMap.batchIncrementCount(generateRows(10_000, "string", 4));
                blackhole.consume(stringHashMap);
            }

        }

        private Object[] generateRows(int positions, String type, int groupCount)
        {
            Object[] rows = new Object[positions];
            for (int i = 0; i < positions; i++) {
                Object row;
                if ("int".equals(type)) {
                    row = RANDOM.nextInt(groupCount);
                }
                else if ("long".equals(type)) {
                    row = RANDOM.nextLong(groupCount);
                }
                else if ("string".equals(type)) {
                    row = String.valueOf(RANDOM.nextLong(groupCount));
                }
                else {
                    throw new IllegalArgumentException("type not supported " + type);
                }
                rows[i] = row;
            }
            return rows;
        }
    }

    @Benchmark
    @OperationsPerInvocation(POSITIONS)
    public Object incrementCount(BenchmarkData data, CollisionCounters collisions)
    {
        CountAggregation hashMap = new CountAggregation_01(data.groupCount);

        hashMap.batchIncrementCount(data.rows);

        collisions.addCollisions(hashMap.getCollisions());
        return hashMap;
    }

    @Benchmark
    @OperationsPerInvocation(POSITIONS)
    public Object incrementCount_v02(BenchmarkData data, CollisionCounters collisions)
    {
        CountAggregation_02 hashMap = new CountAggregation_02(data.groupCount);

        hashMap.batchIncrementCount(data.rows);
        collisions.addCollisions(hashMap.getCollisions());
        return hashMap;
    }

    @Benchmark
    @OperationsPerInvocation(POSITIONS)
    public Object incrementCount_v03(BenchmarkData data, CollisionCounters collisions)
    {
        CountAggregation_03 hashMap = new CountAggregation_03(data.groupCount);

        hashMap.batchIncrementCount(data.rows);
        collisions.addCollisions(hashMap.getCollisions());
        return hashMap;
    }

    @Benchmark
    @OperationsPerInvocation(POSITIONS)
    public Object incrementCount_v04(LongBenchmarkData data, CollisionCounters collisions)
    {
        CountAggregation_04 hashMap = new CountAggregation_04(data.groupCount);

        hashMap.batchIncrementCount(data.rows);
        collisions.addCollisions(hashMap.getCollisions());
        return hashMap;
    }

    @Benchmark
    @OperationsPerInvocation(POSITIONS)
    public Object incrementCount_v05(LongBenchmarkData data, CollisionCounters collisions)
    {
        CountAggregation_05 hashMap = new CountAggregation_05(data.groupCount);

        hashMap.batchIncrementCount(data.rows);
        collisions.addCollisions(hashMap.getCollisions());
        return hashMap;
    }

    @Benchmark
    @OperationsPerInvocation(POSITIONS)
    public Object incrementCount_v06(LongBenchmarkData data, CollisionCounters collisions)
    {
        CountAggregation_06 hashMap = new CountAggregation_06(data.groupCount);

        hashMap.batchIncrementCount(data.rows);
        collisions.addCollisions(hashMap.getCollisions());
        return hashMap;
    }

    public static void main(String[] args)
            throws Exception
    {
        benchmark(BenchmarkCountAggregation.class)
//                .includeMethod("incrementCount_v04")
//                .includeMethod("incrementCount_v05")
                .includeMethod("incrementCount_v06")
//                .withProfilerOutputBaseDir("jmh/map")
                .withProfilerOutputBaseDir("jmh/vector")
                .withOptions((ChainedOptionsBuilder optionsBuilder, String profilerOutputDir) ->
                                optionsBuilder.forks(1)
                                        .warmupIterations(10)
                                        .param("groupCount", "4000000")
                                        .param("type", "long")
                                        .param("polluted", "false")
//                                        .addProfiler(LinuxPerfNormProfiler.class)
//                                        .addProfiler(LinuxPerfAsmProfiler.class, String.format("hotThreshold=0.1;tooBigThreshold=3000;saveLog=true;saveLogTo=%s", profilerOutputDir, profilerOutputDir))
//                                        .addProfiler(AsyncProfiler.class, String.format("dir=%s;output=text;output=flamegraph;event=cache-misses;libPath=/home/ec2-user/async-profiler-2.8.3-linux-arm64/build/libasyncProfiler.so", profilerOutputDir))
                                        .addProfiler(DTraceAsmProfiler.class, format("hotThreshold=0.1;tooBigThreshold=3000;saveLog=true;saveLogTo=%s", profilerOutputDir))
                                        .addProfiler(AsyncProfiler.class, format("dir=%s;output=flamegraph;event=cpu", profilerOutputDir))
                )
                .run();
    }
}
