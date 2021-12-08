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
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.VerboseMode;
import org.testng.annotations.Test;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.Random;

import static java.lang.String.format;
import static java.time.format.DateTimeFormatter.ISO_DATE_TIME;
import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static java.util.concurrent.TimeUnit.NANOSECONDS;
import static org.testng.Assert.assertEquals;

@State(Scope.Thread)
@OutputTimeUnit(NANOSECONDS)
@Fork(1)
@Warmup(iterations = 10, time = 500, timeUnit = MILLISECONDS)
@Measurement(iterations = 10, time = 500, timeUnit = MILLISECONDS)
@BenchmarkMode(Mode.AverageTime)
public class BenchmarkBitMask
{

    @State(Scope.Thread)
    @SuppressWarnings("unused")
    public static class BenchmarkData
    {
        public boolean[] booleanMask;
        public SimpleBitSet bitMask;

        //        @Param({"268435456"})
//        @Param({"67108864"})
        @Param({"1024", "1048576", "4194304", "16777216", "67108864"})
        private int maskLength = 1024;

        //        @Param({"0", "0.1", "0.2", "0.5", "0.9", "0.9", "1"})
        @Param({"0.5"})
        private double fillRate = 0.5F;

        private static final Random random = new Random(1231255);
        private int[] indexes;

        @Setup
        public void setup()
        {
            booleanMask = new boolean[maskLength];
            bitMask = new SimpleBitSet(maskLength);
            indexes = new int[maskLength];
            for (int i = 0; i < maskLength; i++) {
                double value = random.nextDouble();
                boolean set = value < fillRate;
                booleanMask[i] = set;
                bitMask.set(i, set);
                indexes[i] = random.nextInt(maskLength);
            }
        }
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public long booleanMask(BenchmarkData data)
    {
        long sum = 0;
        boolean[] booleanMask = data.booleanMask;
        for (int i = 0; i < booleanMask.length; i++) {
            if (booleanMask[i]) {
                sum += i;
            }
        }
        return sum;
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public long booleanMaskRandom(BenchmarkData data)
    {
        long sum = 0;
        boolean[] booleanMask = data.booleanMask;
        int[] indexes = data.indexes;
        for (int i = 0; i < booleanMask.length; i++) {
            if (booleanMask[indexes[i]]) {
                sum += i;
            }
        }
        return sum;
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public long bitMask(BenchmarkData data)
    {
        long sum = 0;
        SimpleBitSet bitMask = data.bitMask;
        for (int i = 0; i < bitMask.length(); i++) {
            if (bitMask.get(i)) {
                sum += i;
            }
        }
        return sum;
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public long bitMaskRandom(BenchmarkData data)
    {
        long sum = 0;
        SimpleBitSet bitMask = data.bitMask;
        int[] indexes = data.indexes;
        for (int i = 0; i < bitMask.length(); i++) {
            if (bitMask.get(indexes[i])) {
                sum += i;
            }
        }
        return sum;
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public long bitMaskUnrolled(BenchmarkData data)
    {
        long sum = 0;
        long sum1 = 0;
        long sum2 = 0;
        long sum3 = 0;
        long sum4 = 0;
        long sum5 = 0;
        long sum6 = 0;
        long sum7 = 0;
        SimpleBitSet bitMask = data.bitMask;
        int i = 0;
        for (; i < bitMask.length() - 8; i += 8) {
            if (bitMask.get(i)) {
                sum += i;
            }
            if (bitMask.get(i + 1)) {
                sum1 += i + 1;
            }
            if (bitMask.get(i + 2)) {
                sum2 += i + 2;
            }
            if (bitMask.get(i + 3)) {
                sum3 += i + 3;
            }
            if (bitMask.get(i + 4)) {
                sum4 += i + 4;
            }
            if (bitMask.get(i + 5)) {
                sum5 += i + 5;
            }
            if (bitMask.get(i + 6)) {
                sum6 += i + 6;
            }
            if (bitMask.get(i + 7)) {
                sum7 += i + 7;
            }
        }

        for (; i < bitMask.length(); i++) {
            if (bitMask.get(i)) {
                sum += i;
            }
        }
        sum += sum1 + sum2 + sum3 + sum4 + sum5 + sum6 + sum7;
        return sum;
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public long bitMaskUnrolled4(BenchmarkData data)
    {
        long sum = 0;
        long sum1 = 0;
        long sum2 = 0;
        long sum3 = 0;
        SimpleBitSet bitMask = data.bitMask;
        int i = 0;
        for (; i < bitMask.length() - 4; i += 4) {
            if (bitMask.get(i)) {
                sum += i;
            }
            if (bitMask.get(i + 1)) {
                sum1 += i + 1;
            }
            if (bitMask.get(i + 2)) {
                sum2 += i + 2;
            }
            if (bitMask.get(i + 3)) {
                sum3 += i + 3;
            }
        }

        for (; i < bitMask.length(); i++) {
            if (bitMask.get(i)) {
                sum += i;
            }
        }
        sum += sum1 + sum2 + sum3;
        return sum;
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public long bitMaskUnrolledDirect(BenchmarkData data)
    {
        long sum = 0;
        long sum1 = 0;
        long sum2 = 0;
        long sum3 = 0;
        long sum4 = 0;
        long sum5 = 0;
        long sum6 = 0;
        long sum7 = 0;
        SimpleBitSet bitMask = data.bitMask;
        int i = 0;

        int end = bitMask.length() - 16;
        for (; i < end; i += 16) {
            long word = bitMask.bits[i >> 6];
            if ((word & (1L << i)) != 0) {
                sum += i;
            }
            if ((word & (1L << (i + 1))) != 0) {
                sum1 += i + 1;
            }
            if ((word & (1L << (i + 2))) != 0) {
                sum2 += i + 2;
            }
            if ((word & (1L << (i + 3))) != 0) {
                sum3 += i + 3;
            }
            if ((word & (1L << (i + 4))) != 0) {
                sum4 += i + 4;
            }
            if ((word & (1L << (i + 5))) != 0) {
                sum5 += i + 5;
            }
            if ((word & (1L << (i + 6))) != 0) {
                sum6 += i + 6;
            }
            if ((word & (1L << (i + 7))) != 0) {
                sum7 += i + 7;
            }

            if ((word & (1L << (i + 8))) != 0) {
                sum += i + 8;
            }
            if ((word & (1L << (i + 9))) != 0) {
                sum1 += i + 9;
            }
            if ((word & (1L << (i + 10))) != 0) {
                sum2 += i + 10;
            }
            if ((word & (1L << (i + 11))) != 0) {
                sum3 += i + 11;
            }
            if ((word & (1L << (i + 12))) != 0) {
                sum4 += i + 12;
            }
            if ((word & (1L << (i + 13))) != 0) {
                sum5 += i + 13;
            }
            if ((word & (1L << (i + 14))) != 0) {
                sum6 += i + 14;
            }
            if ((word & (1L << (i + 15))) != 0) {
                sum7 += i + 15;
            }
        }

        for (; i < bitMask.length(); i++) {
            if (bitMask.get(i)) {
                sum += i;
            }
        }
        sum += sum1 + sum2 + sum3 + sum4 + sum5 + sum6 + sum7;
        return sum;
    }

    static class SimpleBitSet
    {
        private final long[] bits;

        SimpleBitSet(int length)
        {
            this.bits = new long[length / 64];
        }

        public int length()
        {
            return bits.length * 64;
        }

        public boolean get(int index)
        {
            int wordIndex = index >> 6;
            return (bits[wordIndex] & (1L << index)) != 0;
        }

        public void set(int index, boolean value)
        {
            int wordIndex = index >> 6;
            if (value) {
                bits[wordIndex] |= (1L << index);
            }
            else {
                bits[wordIndex] &= ~(1L << index);
            }
        }
    }

    @Test
    public void test()
    {
        BenchmarkData data = new BenchmarkData();
        data.setup();

        BenchmarkBitMask benchmark = new BenchmarkBitMask();
        assertEquals(benchmark.booleanMask(data), benchmark.bitMaskUnrolledDirect(data));
    }

    public static void main(String[] args)
            throws Exception
    {
        Class<BenchmarkBitMask> benchmarkClass = BenchmarkBitMask.class;
        ChainedOptionsBuilder optionsBuilder = new OptionsBuilder()
                .verbosity(VerboseMode.NORMAL)
                .include("^" + benchmarkClass.getName() + ".bitMaskRandom$")
                .include("^" + benchmarkClass.getName() + ".booleanMaskRandom$")
                .include("^" + benchmarkClass.getName() + ".bitMask$")
                .include("^" + benchmarkClass.getName() + ".booleanMask$")
                .resultFormat(ResultFormatType.JSON)
                .result(format("%s/%s-result-%s.json", System.getProperty("java.io.tmpdir"), benchmarkClass.getSimpleName(), ISO_DATE_TIME.format(LocalDateTime.now())))
//                .addProfiler(DTraceAsmProfiler.class)
//                                        .addProfiler(AsyncProfiler.class, String.format("dir=%s;output=text;output=flamegraph", asyncProfilerDir()))
                ;
        new Runner(optionsBuilder.build()).run();
    }

    private static String asyncProfilerDir()
    {
        try {
            String jmhDir = "jmh";
            new File(jmhDir).mkdirs();
            return jmhDir + "/" + String.valueOf(Files.list(Paths.get(jmhDir))
                    .map(path -> Integer.parseInt(path.getFileName().toString()) + 1)
                    .sorted(Comparator.reverseOrder())
                    .findFirst().orElse(0));
        }
        catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
