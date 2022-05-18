package com.lstec.jvm;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.CompilerControl;
import org.openjdk.jmh.annotations.Fork;
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
import java.util.Comparator;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static com.lstec.jvm.Benchmarks.benchmark;

/**
 * Benchmark                                       (usedBaseTypes)  Mode  Cnt   Score   Error  Units
 * BenchmarkVirtualCall.testVirtualCallInlined                   1  avgt   10   0.378 ± 0.008  ns/op
 * BenchmarkVirtualCall.testVirtualCallInlined                   2  avgt   10   0.735 ± 0.021  ns/op
 * BenchmarkVirtualCall.testVirtualCallInlined                   3  avgt   10  11.063 ± 0.381  ns/op
 * BenchmarkVirtualCall.testVirtualCallNotInlined                1  avgt   10   2.363 ± 0.013  ns/op
 * BenchmarkVirtualCall.testVirtualCallNotInlined                2  avgt   10   6.751 ± 0.160  ns/op
 * BenchmarkVirtualCall.testVirtualCallNotInlined                3  avgt   10  11.421 ± 0.113  ns/op
 */
@State(Scope.Thread)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 10, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 10, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@BenchmarkMode(Mode.AverageTime)
public class BenchmarkVirtualCall
{

    @State(Scope.Thread)
    @SuppressWarnings("unused")
    public static class BenchmarkData
    {
        private static final Random random = new Random(1231255);

        @Param({"1", "2", "3"})
        private int usedBaseTypes = 1;
        private Base[] usedInOuterFunction;
        private Base[] allTypes;

        @CompilerControl(CompilerControl.Mode.DONT_INLINE)
        private long outerNotInlined(Base b)
        {
            return b.virtualCall();
        }

        private long outerInlined(Base b)
        {
            return b.virtualCall();
        }

        static abstract class Base
        {
            public abstract long virtualCall();
        }

        static class Impl0
                extends Base
        {

            @Override
            public long virtualCall()
            {
                return 7;
            }
        }

        static class Impl1
                extends Base
        {

            @Override
            public long virtualCall()
            {
                return 9;
            }
        }

        static class Impl2
                extends Base
        {

            @Override
            public long virtualCall()
            {
                return 11;
            }
        }

        @Setup
        public void setup(BenchmarkParams benchmarkParams)
        {
            int numberOfObjects = 1000;
            if (benchmarkParams != null) {
                benchmarkParams.setOpsPerInvocation(numberOfObjects);
            }
            allTypes = new Base[3];
            allTypes[0] = new Impl0();
            allTypes[1] = new Impl1();
            allTypes[2] = new Impl2();

            usedInOuterFunction = new Base[numberOfObjects];
            for (int i = 0; i < usedInOuterFunction.length; i++) {
                int selector = random.nextInt(usedBaseTypes);
                switch (selector) {
                    case 0:
                        usedInOuterFunction[i] = new Impl0();
                        break;
                    case 1:
                        usedInOuterFunction[i] = new Impl1();
                        break;
                    case 2:
                        usedInOuterFunction[i] = new Impl2();
                        break;
                    default:
                        throw new RuntimeException();
                }
            }
        }
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public Object testVirtualCallInlined(BenchmarkData data)
    {
        long result = 0;
        for (int i = 0; i < data.usedInOuterFunction.length; i++) {
            result += data.outerInlined(data.usedInOuterFunction[i]);
        }
        return result;
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public Object testVirtualCallNotInlined(BenchmarkData data)
    {
        long result = 0;
        for (int i = 0; i < data.usedInOuterFunction.length; i++) {
            result += data.outerNotInlined(data.usedInOuterFunction[i]);
        }
        return result;
    }

    public static void main(String[] args) throws Exception
    {
        String profilerOutputDir = profilerOutputDir();

        benchmark(BenchmarkVirtualCall.class)
                .withOptions(optionsBuilder ->
                                optionsBuilder.forks(1)
//                                .addProfiler(DTraceAsmProfiler.class, String.format("hotThreshold=0.1;tooBigThreshold=3000;saveLog=true;saveLogTo=%s", profilerOutputDir, profilerOutputDir))
                )
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
