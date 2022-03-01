package com.lstec.jvm;

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
 * This benchmark goal is to test whether jit can inline base class method into subclasses
 * in case when it would be beneficial e.g. subclass is called with only one specific parameter type
 * thus it would be eligible for inlining the param method body.
 * Benchmark uses base class OperationBaseClass that has one method compute that only returns result from the DataBaseClass.getLong abstract call.
 * DataBaseClass has 3 subclasses with different getLong implementation A, B, C.
 * OperationBaseClass has 3 empty subclasses Aop, Bop, Cop and 3 subclasses AOpCopied, BOpCopied, COpCopied with compute method copied 1 to 1 from OperationBaseClass.
 * <p>
 * Results
 * Benchmark                                                       Mode  Cnt  Score   Error  Units
 * BenchmarkSubclassInlining.baseMethodCopiedInSubClassMega        avgt   10  4.598 ± 0.026  ns/op
 * BenchmarkSubclassInlining.baseMethodCopiedInSubClassOnlyA       avgt   10  2.063 ± 0.031  ns/op
 * BenchmarkSubclassInlining.onlyBaseMethodMega                    avgt   10  6.185 ± 0.166  ns/op
 * BenchmarkSubclassInlining.onlyBaseMethodOnlyA                   avgt   10  2.053 ± 0.046  ns/op
 * <p>
 * The *Mega case shows that jit will not add synthetic method to empty *Op classes so there are 2 virtual calls there.
 * The * OnlyA case shows that it doesn't matter if the method can be inlined in the higher level, not mega-morphic call site.
 */
@State(Scope.Thread)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 30, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 10, time = 500, timeUnit = TimeUnit.MILLISECONDS)
@BenchmarkMode(Mode.AverageTime)
public class BenchmarkSubclassInlining
{
    private static final int ITERATIONS = 1000 * 1000;

    static class OperationBaseClass
    {
        public long compute(DataBaseClass data)
        {
            return data.getLong();
        }
    }

    static abstract class DataBaseClass
    {
        protected final long value;

        DataBaseClass(long value) {this.value = value;}

        public abstract long getLong();
    }

    static class AOp
            extends OperationBaseClass
    {
    }

    static class BOp
            extends OperationBaseClass
    {
    }

    static class COp
            extends OperationBaseClass
    {
    }

    static class AOpCopied
            extends OperationBaseClass
    {
        public long compute(DataBaseClass data)
        {
            return data.getLong();
        }
    }

    static class BOpCopied
            extends OperationBaseClass
    {
        public long compute(DataBaseClass data)
        {
            return data.getLong();
        }
    }

    static class COpCopied
            extends OperationBaseClass
    {
        public long compute(DataBaseClass data)
        {
            return data.getLong();
        }
    }

    static class AData
            extends DataBaseClass
    {
        AData(long value)
        {
            super(value);
        }

        @Override
        public long getLong()
        {
            return value;
        }
    }

    static class BData
            extends DataBaseClass
    {
        BData(long value)
        {
            super(value);
        }

        @Override
        public long getLong()
        {
            return value * 31;
        }
    }

    static class CData
            extends DataBaseClass
    {
        CData(long value)
        {
            super(value);
        }

        @Override
        public long getLong()
        {
            return value / 17;
        }
    }

    @SuppressWarnings("FieldMayBeFinal")
    @State(Scope.Thread)
    public static class BenchmarkData
    {
        private static final Random RANDOM = new Random(23145);
        public final DataBaseClass[] data = new DataBaseClass[ITERATIONS];
        public final OperationBaseClass[] op = new OperationBaseClass[ITERATIONS];
        public final OperationBaseClass[] opCopied = new OperationBaseClass[ITERATIONS];

        public final DataBaseClass[] dataA = new DataBaseClass[ITERATIONS];

        @Setup
        public void setup()
        {
            for (int i = 0; i < ITERATIONS; i++) {
                dataA[i] = new AData(RANDOM.nextLong());
            }

            int i = 0;
            for (; i + 3 < ITERATIONS; i += 3) {
                data[i] = new AData(RANDOM.nextLong());
                op[i] = new AOp();
                opCopied[i] = new AOpCopied();

                data[i + 1] = new BData(RANDOM.nextLong());
                op[i + 1] = new BOp();
                opCopied[i + 1] = new BOpCopied();

                data[i + 2] = new CData(RANDOM.nextLong());
                op[i + 2] = new COp();
                opCopied[i + 2] = new COpCopied();
            }
            if (i < ITERATIONS) {
                data[i] = new AData(RANDOM.nextLong());
                op[i] = new AOp();
                opCopied[i] = new AOpCopied();
                i++;
                if (i < ITERATIONS) {
                    data[i] = new BData(RANDOM.nextLong());
                    op[i] = new BOp();
                    opCopied[i] = new BOpCopied();
                }
            }
        }
    }

    @Benchmark
    @OperationsPerInvocation(ITERATIONS)
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public Object onlyBaseMethodMega(BenchmarkData data)
    {
        return compute(data.op, data.data);
    }

    @Benchmark
    @OperationsPerInvocation(ITERATIONS)
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public Object baseMethodCopiedInSubClassMega(BenchmarkData data)
    {
        return compute(data.opCopied, data.data);
    }

    @Benchmark
    @OperationsPerInvocation(ITERATIONS)
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public Object onlyBaseMethodOnlyA(BenchmarkData data)
    {
        return compute(new AOp(), data.dataA);
    }

    @Benchmark
    @OperationsPerInvocation(ITERATIONS)
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public Object baseMethodCopiedInSubClassOnlyA(BenchmarkData data)
    {
        return compute(new AOpCopied(), data.dataA);
    }

    private long compute(OperationBaseClass[] op, DataBaseClass[] data)
    {
        long result = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            result += op[i].compute(data[i]);
        }
        return result;
    }

    private long compute(OperationBaseClass op, DataBaseClass[] data)
    {
        long result = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            result += op.compute(data[i]);
        }
        return result;
    }

    private long computeNoInlineInLoop(OperationBaseClass op, DataBaseClass[] data)
    {
        long result = 0;
        for (int i = 0; i < ITERATIONS; i++) {
            result += computeNoInline(op, data[i]);
        }
        return result;
    }

    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    private long computeNoInline(OperationBaseClass op, DataBaseClass data)
    {
        return op.compute(data);
    }

    public static void main(String[] args)
            throws RunnerException, IOException
    {
        for (int i = 0; i < 5; i++) {
            BenchmarkData data = new BenchmarkData();
            data.setup();
            new BenchmarkSubclassInlining().onlyBaseMethodMega(data);
            new BenchmarkSubclassInlining().baseMethodCopiedInSubClassMega(data);
        }
        String profilerOutputDir = profilerOutputDir();

        benchmark(BenchmarkSubclassInlining.class)
                .withOptions(optionsBuilder -> optionsBuilder
                        .addProfiler(DTraceAsmProfiler.class, String.format("hotThreshold=0.1;tooBigThreshold=3000;saveLog=true;saveLogTo=%s", profilerOutputDir, profilerOutputDir)))
//                .includeMethod("baseMethodCopiedInSubClassOnlyA")
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
