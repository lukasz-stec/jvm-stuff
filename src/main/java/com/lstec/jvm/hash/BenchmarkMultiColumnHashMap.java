package com.lstec.jvm.hash;

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

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

import static com.lstec.jvm.Benchmarks.benchmark;

/**
 * Benchmark                                   (groupCount)  (polluteProfileInSetup)  (type)  Mode  Cnt   Score   Error  Units
 * BenchmarkMultiColumnHashMap.incrementCount             4                     true     int  avgt   10   9.431 ± 0.155  ns/op
 * BenchmarkMultiColumnHashMap.incrementCount             4                    false     int  avgt   10   4.016 ± 0.096  ns/op
 * BenchmarkMultiColumnHashMap.incrementCount        100000                     true     int  avgt   10  19.698 ± 0.855  ns/op
 * BenchmarkMultiColumnHashMap.incrementCount        100000                    false     int  avgt   10  13.502 ± 0.880  ns/op
 * BenchmarkMultiColumnHashMap.incrementCount       3000000                     true     int  avgt   10  90.815 ± 2.054  ns/op
 * BenchmarkMultiColumnHashMap.incrementCount       3000000                    false     int  avgt   10  57.144 ± 2.867  ns/op
 */
@State(Scope.Thread)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 10, time = 1)
@BenchmarkMode(Mode.AverageTime)
public class BenchmarkMultiColumnHashMap
{

    private static final int POSITIONS = 1024 * 1024 * 8;

    @SuppressWarnings("FieldMayBeFinal")
    @State(Scope.Thread)
    public static class BenchmarkData
    {
        @Param({"4", "100000", "3000000"})
        private int groupCount = 4;

        @Param({"int", "long", "string"})
        private String type = "int";

        @Param({"true", "false"})
        private boolean polluteProfileInSetup = true;

        private Object[] rows;

        @Setup
        public void setup()
        {
            rows = new Object[POSITIONS];
            for (int i = 0; i < POSITIONS; i++) {
                Object row;
                if ("int".equals(type)) {
                    row = ThreadLocalRandom.current().nextInt(groupCount);
                }
                else if ("long".equals(type)) {
                    row = ThreadLocalRandom.current().nextLong(groupCount);
                }
                else if ("string".equals(type)) {
                    row = String.valueOf(ThreadLocalRandom.current().nextLong(groupCount));
                }
                else {
                    throw new IllegalArgumentException("type not supported " + type);
                }
                rows[i] = row;
            }
            if (polluteProfileInSetup) {
                MultiColumnHashMap intHashMap = new MultiColumnHashMap(16);
                for (int i = 0; i < 10_000; i++) {
                    int value = ThreadLocalRandom.current().nextInt(4);
                    intHashMap.incrementCount(value);
                }
                MultiColumnHashMap longHashMap = new MultiColumnHashMap(16);
                for (int i = 0; i < 10_000; i++) {
                    int value = ThreadLocalRandom.current().nextInt(4);
                    longHashMap.incrementCount((long) value);
                }
                MultiColumnHashMap stringHashMap = new MultiColumnHashMap(16);
                for (int i = 0; i < 10_000; i++) {
                    int value = ThreadLocalRandom.current().nextInt(4);
                    stringHashMap.incrementCount(String.valueOf(value));
                }
            }
        }
    }

    @Benchmark
    @OperationsPerInvocation(POSITIONS)
    public Object incrementCount(BenchmarkData data)
    {
        MultiColumnHashMap hashMap = new MultiColumnHashMap(data.groupCount * 2);

        hashMap.incrementCount(data.rows);

        return hashMap;
    }

    public static void main(String[] args)
            throws Exception
    {
        benchmark(BenchmarkMultiColumnHashMap.class)
                .withProfilerOutputBaseDir("jmh")
                .withOptions((optionsBuilder, profilerOutputDir) ->
                                optionsBuilder.forks(1)
                                        .warmupIterations(10)
//                                        .param("groupCount", "1000000")
                                        .param("type", "int")
//                                .param("polluteProfileInSetup", "true")
                                        .addProfiler(DTraceAsmProfiler.class, String.format("hotThreshold=0.1;tooBigThreshold=3000;saveLog=true;saveLogTo=%s", profilerOutputDir, profilerOutputDir))
                )
                .run();
    }
}
