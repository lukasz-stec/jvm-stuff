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

import java.util.Arrays;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static com.lstec.jvm.Benchmarks.benchmark;

/**
 * Benchmark                     (fixedSize)  (nullRate)  (stringMaxSize)  Mode  Cnt   Score   Error  Units
 * BenchmarkBytesCompare.equals         true           0                4  avgt   10   2.714 ± 0.031  ns/op
 * BenchmarkBytesCompare.equals         true           0               10  avgt   10   5.537 ± 0.135  ns/op
 * BenchmarkBytesCompare.equals         true           0               20  avgt   10   3.883 ± 0.054  ns/op
 * BenchmarkBytesCompare.equals         true         0.2                4  avgt   10   8.092 ± 0.517  ns/op
 * BenchmarkBytesCompare.equals         true         0.2               10  avgt   10  11.352 ± 0.170  ns/op
 * BenchmarkBytesCompare.equals         true         0.2               20  avgt   10  12.787 ± 0.352  ns/op
 * BenchmarkBytesCompare.equals         true         0.5                4  avgt   10  12.467 ± 0.064  ns/op
 * BenchmarkBytesCompare.equals         true         0.5               10  avgt   10  15.638 ± 0.214  ns/op
 * BenchmarkBytesCompare.equals         true         0.5               20  avgt   10  15.864 ± 0.695  ns/op
 * BenchmarkBytesCompare.equals        false           0                4  avgt   10  12.143 ± 0.073  ns/op
 * BenchmarkBytesCompare.equals        false           0               10  avgt   10  14.295 ± 1.517  ns/op
 * BenchmarkBytesCompare.equals        false           0               20  avgt   10  14.822 ± 0.069  ns/op
 * BenchmarkBytesCompare.equals        false         0.2                4  avgt   10  12.173 ± 0.125  ns/op
 * BenchmarkBytesCompare.equals        false         0.2               10  avgt   10  13.741 ± 1.477  ns/op
 * BenchmarkBytesCompare.equals        false         0.2               20  avgt   10  14.815 ± 0.093  ns/op
 * BenchmarkBytesCompare.equals        false         0.5                4  avgt   10  11.345 ± 1.231  ns/op
 * BenchmarkBytesCompare.equals        false         0.5               10  avgt   10  14.891 ± 0.121  ns/op
 * BenchmarkBytesCompare.equals        false         0.5               20  avgt   10  13.665 ± 1.513  ns/op
 */
@State(Scope.Thread)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 10, time = 1)
@Measurement(iterations = 10, time = 1)
@BenchmarkMode(Mode.AverageTime)
public class BenchmarkBytesCompare
{

    private static final int POSITIONS = 1024 * 1024;

    @SuppressWarnings("FieldMayBeFinal")
    @State(Scope.Thread)
    public static class BenchmarkData
    {
        private static final Random RANDOM = new Random(12345);
        @Param({"4", "10", "20"})
        private int stringMaxSize = 4;

        @Param({"true", "false"})
        private boolean fixedSize = true;

        @Param({"0", "0.2", "0.5"})
        private float nullRate = 0.2f;
        private VarbinaryBlock block1a;
        private VarbinaryBlock block1b;

        private VarbinaryBlock block2a;
        private VarbinaryBlock block2b;

        private boolean[] match = new boolean[POSITIONS];

        @Setup
        public void setup()
        {
            block1a = createRandomBlock();
            block1b = createRandomBlock();
            block2a = createRandomBlock();
            block2b = createRandomBlock();
        }

        private VarbinaryBlock createRandomBlock()
        {
            VarbinaryBlock block = new VarbinaryBlock();
            block.values = new byte[stringMaxSize * POSITIONS];
            RANDOM.nextBytes(block.values);
            block.offsets = new int[POSITIONS + 1];
            block.isNull = new boolean[POSITIONS];
            if (fixedSize) {
                for (int i = 1; i < block.offsets.length; i++) {
                    block.isNull[i - 1] = RANDOM.nextFloat() <= nullRate;
                    int length = block.isNull[i - 1] ? 0 : stringMaxSize;
                    block.offsets[i] = block.offsets[i - 1] + length;
                }
            }
            else {
                for (int i = 1; i < block.offsets.length; i++) {
                    block.isNull[i - 1] = RANDOM.nextBoolean();
                    int length = block.isNull[i - 1] ? 0 : RANDOM.nextInt(stringMaxSize + 1);
                    block.offsets[i] = block.offsets[i - 1] + length;
                }
            }
            return block;
        }
    }

    @Benchmark
    @OperationsPerInvocation(POSITIONS)
    public Object equals(BenchmarkData data)
    {
        boolean[] match = data.match;

        for (int i = 0; i < POSITIONS; i++) {
            match[i] = data.block1a.positionsEquals(i, data.block1b, i) && data.block2a.positionsEquals(i, data.block2b, i);
        }

        return match;
    }

    public static void main(String[] args)
            throws Exception
    {
        benchmark(BenchmarkBytesCompare.class)
//                .includeMethod("incrementCount_v04")
//                .withOptions((ChainedOptionsBuilder optionsBuilder, String profilerOutputDir) ->
//                        optionsBuilder
//                                .addProfiler(DTraceAsmProfiler.class, format("hotThreshold=0.1;tooBigThreshold=3000;saveLog=true;saveLogTo=%s", profilerOutputDir))
//                                .addProfiler(AsyncProfiler.class, format("dir=%s;output=flamegraph;event=cpu", profilerOutputDir))
//                )
                .withOptions(options -> options.param("stringMaxSize", "50", "100", "250", "1000")
                        .param("nullRate", "0"))
                .run();
    }

    private static class VarbinaryBlock
    {
        private byte[] values;
        private int[] offsets;
        private boolean[] isNull;

        public boolean positionsEquals(int position, VarbinaryBlock other, int otherPosition)
        {
            if (isNull[position] && other.isNull[otherPosition]) {
                return true;
            }

            if (!isNull[position] && other.isNull[otherPosition]) {
                return false;
            }
            if (isNull[position] && !other.isNull[otherPosition]) {
                return false;
            }
            int offset = offsets[position];
            int endOffset = offsets[position + 1];
            int length1 = endOffset - offset;
            int otherOffset = other.offsets[otherPosition];
            int otherEndOffset = other.offsets[otherPosition + 1];
            int length2 = otherEndOffset - otherOffset;
            if (length1 != length2) {
                return false;
            }
            return Arrays.mismatch(values, offset, endOffset, other.values, otherOffset, otherEndOffset) == -1;
        }
    }
}
