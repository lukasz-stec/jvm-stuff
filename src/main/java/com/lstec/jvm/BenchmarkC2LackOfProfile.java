package com.lstec.jvm;

import com.google.common.util.concurrent.Uninterruptibles;
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
import org.openjdk.jmh.infra.Blackhole;
import org.openjdk.jmh.profile.DTraceAsmProfiler;

import java.time.Duration;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import static com.lstec.jvm.Benchmarks.benchmark;

/**
 * Benchmark                                                (polluteProfileInSetup)  Mode  Cnt  Score   Error  Units
 * BenchmarkC2LackOfProfile.testVirtualCallInlined                             true  avgt   10  2.501 ± 0.029  ns/op
 * BenchmarkC2LackOfProfile.testVirtualCallInlined                            false  avgt   10  0.203 ± 0.002  ns/op
 * BenchmarkC2LackOfProfile.testVirtualCallNotInlined                          true  avgt   10  3.368 ± 0.033  ns/op
 * BenchmarkC2LackOfProfile.testVirtualCallNotInlined                         false  avgt   10  1.966 ± 0.024  ns/op
 * <p>
 * <p>
 * testVirtualCallInlined polluteProfileInSetup = true
 * 1.92%  ││↗   0x00000001192e9290:   mov    0x8(%rsp),%r11
 * 1.06%  │││   0x00000001192e9295:   mov    %r10,0x18(%rsp)              ;*lload_2 {reexecute=0 rethrow=0 return_oop=0}
 * │││                                                             ; - com.lstec.jvm.BenchmarkC2LackOfProfile$BenchmarkData::sumInlined@12 (line 81)
 * 0.53%  │↘│   0x00000001192e929a:   mov    0x10(%r11,%rbp,4),%r10d
 * 6.54%  │ │   0x00000001192e929f:   mov    %r11,0x8(%rsp)
 * 1.82%  │ │   0x00000001192e92a4:   mov    %r10,%rsi
 * 1.14%  │ │   0x00000001192e92a7:   shl    $0x3,%rsi                    ;*aaload {reexecute=0 rethrow=0 return_oop=0}
 * │ │                                                             ; - com.lstec.jvm.BenchmarkC2LackOfProfile$BenchmarkData::sumInlined@17 (line 81)
 * 0.40%  │ │   0x00000001192e92ab:   xchg   %ax,%ax
 * 6.10%  │ │   0x00000001192e92ad:   movabs $0x80102d368,%rax
 * 2.30%  │ │   0x00000001192e92b7:   callq  0x00000001192e7ca0           ; ImmutableOopMap {[0]=Oop [8]=Oop }
 * │ │                                                             ;*invokevirtual virtualCall {reexecute=0 rethrow=0 return_oop=0}
 * │ │                                                             ; - com.lstec.jvm.BenchmarkC2LackOfProfile$BenchmarkData::outerInlined@1 (line 63)
 * │ │                                                             ; - com.lstec.jvm.BenchmarkC2LackOfProfile$BenchmarkData::sumInlined@18 (line 81)
 * │ │                                                             ;   {virtual_call}
 * 17.96%  │ │   0x00000001192e92bc:   mov    0x18(%rsp),%r10
 * 2.77%  │ │   0x00000001192e92c1:   add    %rax,%r10                    ;*ladd {reexecute=0 rethrow=0 return_oop=0}
 * │ │                                                             ; - com.lstec.jvm.BenchmarkC2LackOfProfile$BenchmarkData::sumInlined@21 (line 81)
 * 0.27%  │ │   0x00000001192e92c4:   inc    %ebp                         ;*iinc {reexecute=0 rethrow=0 return_oop=0}
 * │ │                                                             ; - com.lstec.jvm.BenchmarkC2LackOfProfile$BenchmarkData::sumInlined@23 (line 80)
 * 6.42%  │ │   0x00000001192e92c6:   cmp    0x10(%rsp),%ebp
 * │ ╰   0x00000001192e92ca:   jl     0x00000001192e9290
 * │     0x00000001192e92cc:   mov    %r10,%rax                    ;*if_icmpge {reexecute=0 rethrow=0 return_oop=0}
 * │                                                               ; - com.lstec.jvm.BenchmarkC2LackOfProfile$BenchmarkData::sumInlined@9 (line 80)
 * │  ↗  0x00000001192e92cf:   add    $0x30,%rsp
 * 0.02%  │  │  0x00000001192e92d3:   pop    %rbp
 * │  │  0x00000001192e92d4:   cmp    0x348(%r15),%rsp             ;   {poll_return}
 * │  │  0x00000001192e92db:   ja     0x00000001192e9318
 * │  │  0x00000001192e92e1:   retq
 * <p>
 * <p>
 * testVirtualCallInlined polluteProfileInSetup = false
 * <p>
 * inlined and unrolled
 * 5.55%  │        ↗│     0x0000000122914a40:   mov    0x10(%rdx,%r8,4),%ecx        ;*aaload {reexecute=0 rethrow=0 return_oop=0}
 * │        ││                                                               ; - com.lstec.jvm.BenchmarkC2LackOfProfile$BenchmarkData::sumInlined@17 (line 81)
 * 5.04%  │        ││     0x0000000122914a45:   test   %ecx,%ecx
 * │╭       ││     0x0000000122914a47:   je     0x0000000122914b11           ;*invokevirtual virtualCall {reexecute=0 rethrow=0 return_oop=0}
 * ││       ││                                                               ; - com.lstec.jvm.BenchmarkC2LackOfProfile$BenchmarkData::outerInlined@1 (line 63)
 * ││       ││                                                               ; - com.lstec.jvm.BenchmarkC2LackOfProfile$BenchmarkData::sumInlined@18 (line 81)
 * 4.41%  ││       ││     0x0000000122914a4d:   mov    0x14(%rdx,%r8,4),%ecx        ;*aaload {reexecute=0 rethrow=0 return_oop=0}
 * ││       ││                                                               ; - com.lstec.jvm.BenchmarkC2LackOfProfile$BenchmarkData::sumInlined@17 (line 81)
 * 2.72%  ││       ││     0x0000000122914a52:   test   %ecx,%ecx
 * ││╭      ││     0x0000000122914a54:   je     0x0000000122914b11           ;*invokevirtual virtualCall {reexecute=0 rethrow=0 return_oop=0}
 * │││      ││                                                               ; - com.lstec.jvm.BenchmarkC2LackOfProfile$BenchmarkData::outerInlined@1 (line 63)
 * │││      ││                                                               ; - com.lstec.jvm.BenchmarkC2LackOfProfile$BenchmarkData::sumInlined@18 (line 81)
 * 4.58%  │││      ││     0x0000000122914a5a:   mov    0x18(%rdx,%r8,4),%ecx        ;*aaload {reexecute=0 rethrow=0 return_oop=0}
 * │││      ││                                                               ; - com.lstec.jvm.BenchmarkC2LackOfProfile$BenchmarkData::sumInlined@17 (line 81)
 * 3.23%  │││      ││     0x0000000122914a5f:   nop
 * 3.86%  │││      ││     0x0000000122914a60:   test   %ecx,%ecx
 * │││╭     ││     0x0000000122914a62:   je     0x0000000122914b11           ;*invokevirtual virtualCall {reexecute=0 rethrow=0 return_oop=0}
 * ││││     ││                                                               ; - com.lstec.jvm.BenchmarkC2LackOfProfile$BenchmarkData::outerInlined@1 (line 63)
 * ││││     ││                                                               ; - com.lstec.jvm.BenchmarkC2LackOfProfile$BenchmarkData::sumInlined@18 (line 81)
 * 3.71%  ││││     ││     0x0000000122914a68:   mov    0x1c(%rdx,%r8,4),%ecx        ;*aaload {reexecute=0 rethrow=0 return_oop=0}
 * ││││     ││                                                               ; - com.lstec.jvm.BenchmarkC2LackOfProfile$BenchmarkData::sumInlined@17 (line 81)
 * 3.84%  ││││     ││     0x0000000122914a6d:   test   %ecx,%ecx
 * ││││╭    ││     0x0000000122914a6f:   je     0x0000000122914b11           ;*invokevirtual virtualCall {reexecute=0 rethrow=0 return_oop=0}
 * │││││    ││                                                               ; - com.lstec.jvm.BenchmarkC2LackOfProfile$BenchmarkData::outerInlined@1 (line 63)
 * │││││    ││                                                               ; - com.lstec.jvm.BenchmarkC2LackOfProfile$BenchmarkData::sumInlined@18 (line 81)
 * 3.78%  │││││    ││     0x0000000122914a75:   mov    0x20(%rdx,%r8,4),%ecx        ;*aaload {reexecute=0 rethrow=0 return_oop=0}
 * │││││    ││                                                               ; - com.lstec.jvm.BenchmarkC2LackOfProfile$BenchmarkData::sumInlined@17 (line 81)
 * 3.59%  │││││    ││     0x0000000122914a7a:   test   %ecx,%ecx
 * 3.80%  │││││    ││     0x0000000122914a7c:   nopl   0x0(%rax)
 * 3.23%  │││││╭   ││     0x0000000122914a80:   je     0x0000000122914b11           ;*invokevirtual virtualCall {reexecute=0 rethrow=0 return_oop=0}
 * ││││││   ││                                                               ; - com.lstec.jvm.BenchmarkC2LackOfProfile$BenchmarkData::outerInlined@1 (line 63)
 * ││││││   ││                                                               ; - com.lstec.jvm.BenchmarkC2LackOfProfile$BenchmarkData::sumInlined@18 (line 81)
 * 5.15%  ││││││   ││     0x0000000122914a86:   mov    0x24(%rdx,%r8,4),%ecx        ;*aaload {reexecute=0 rethrow=0 return_oop=0}
 * <p>
 * <p>
 * testVirtualCallNotInlined polluteProfileInSetup = true
 * <p>
 * c2, level 4, com.lstec.jvm.BenchmarkC2LackOfProfile$BenchmarkData::outerNotInlined, version 834 (50 bytes)
 * <p>
 * # parm0:    rdx:rdx   = &apos;com/lstec/jvm/BenchmarkC2LackOfProfile$BenchmarkData$Base&apos;
 * #           [sp+0x20]  (sp of caller)
 * 0x000000011a2315a0:   mov    0x8(%rsi),%r10d
 * 0x000000011a2315a4:   movabs $0x800000000,%r11
 * 0x000000011a2315ae:   add    %r11,%r10
 * 0x000000011a2315b1:   cmp    %r10,%rax
 * 0x000000011a2315b4:   jne    0x0000000112699780           ;   {runtime_call ic_miss_stub}
 * 0x000000011a2315ba:   xchg   %ax,%ax
 * 0x000000011a2315bc:   nopl   0x0(%rax)
 * [Verified Entry Point]
 * 6.39%     0x000000011a2315c0:   mov    %eax,-0x14000(%rsp)
 * 1.22%     0x000000011a2315c7:   push   %rbp
 * 0.15%     0x000000011a2315c8:   sub    $0x10,%rsp                   ;*synchronization entry
 * ; - com.lstec.jvm.BenchmarkC2LackOfProfile$BenchmarkData::outerNotInlined@-1 (line 58)
 * 5.85%     0x000000011a2315cc:   mov    %rdx,%rsi
 * 0.61%     0x000000011a2315cf:   xchg   %ax,%ax
 * 0.19%     0x000000011a2315d1:   movabs $0x80102d580,%rax
 * 5.40%     0x000000011a2315db:   callq  0x00000001126969e0           ; ImmutableOopMap {}
 * ;*invokevirtual virtualCall {reexecute=0 rethrow=0 return_oop=0}
 * ; - com.lstec.jvm.BenchmarkC2LackOfProfile$BenchmarkData::outerNotInlined@1 (line 58)
 * ;   {virtual_call}
 * 0.38%     0x000000011a2315e0:   add    $0x10,%rsp
 * 6.98%     0x000000011a2315e4:   pop    %rbp
 * 0.59%     0x000000011a2315e5:   cmp    0x348(%r15),%rsp             ;   {poll_return}
 * ╭  0x000000011a2315ec:   ja     0x000000011a231600
 * 6.10%  │  0x000000011a2315f2:   retq                                ;*invokevirtual virtualCall {reexecute=0 rethrow=0 return_oop=0}
 * │
 * <p>
 * testVirtualCallNotInlined polluteProfileInSetup = false
 * <p>
 * virtual call in sumNotInlined to outerNotInlined but virtualCall inlined in the outerNotInlined + uncommon trap
 * <p>
 * c2, level 4, com.lstec.jvm.BenchmarkC2LackOfProfile$BenchmarkData::sumNotInlined, version 828 (75 bytes)
 * ...
 * 3.36%  ││↗   0x000000012142b690:   mov    0x8(%rsp),%r11
 * 1.39%  │││   0x000000012142b695:   mov    %r10,0x18(%rsp)              ;*lload_2 {reexecute=0 rethrow=0 return_oop=0}
 * │││                                                             ; - com.lstec.jvm.BenchmarkC2LackOfProfile$BenchmarkData::sumNotInlined@12 (line 71)
 * 5.04%  │↘│   0x000000012142b69a:   mov    0x10(%r11,%rbp,4),%r10d
 * 5.10%  │ │   0x000000012142b69f:   mov    %r11,0x8(%rsp)
 * 2.50%  │ │   0x000000012142b6a4:   mov    %r10,%rdx
 * 1.66%  │ │   0x000000012142b6a7:   shl    $0x3,%rdx                    ;*aaload {reexecute=0 rethrow=0 return_oop=0}
 * │ │                                                             ; - com.lstec.jvm.BenchmarkC2LackOfProfile$BenchmarkData::sumNotInlined@17 (line 71)
 * 5.31%  │ │   0x000000012142b6ab:   mov    (%rsp),%rsi
 * 3.57%  │ │   0x000000012142b6af:   callq  0x000000012142adc0           ; ImmutableOopMap {[0]=Oop [8]=Oop }
 * │ │                                                             ;*invokevirtual outerNotInlined {reexecute=0 rethrow=0 return_oop=0}
 * │ │                                                             ; - com.lstec.jvm.BenchmarkC2LackOfProfile$BenchmarkData::sumNotInlined@18 (line 71)
 * │ │                                                             ;   {optimized virtual_call}
 * 9.80%  │ │   0x000000012142b6b4:   mov    0x18(%rsp),%r10
 * 6.97%  │ │   0x000000012142b6b9:   add    %rax,%r10                    ;*ladd {reexecute=0 rethrow=0 return_oop=0}
 * │ │                                                             ; - com.lstec.jvm.BenchmarkC2LackOfProfile$BenchmarkData::sumNotInlined@21 (line 71)
 * 1.11%  │ │   0x000000012142b6bc:   inc    %ebp                         ;*iinc {reexecute=0 rethrow=0 return_oop=0}
 * │ │                                                             ; - com.lstec.jvm.BenchmarkC2LackOfProfile$BenchmarkData::sumNotInlined@23 (line 70)
 * 5.23%  │ │   0x000000012142b6be:   xchg   %ax,%ax
 * 3.76%  │ │   0x000000012142b6c0:   cmp    0x10(%rsp),%ebp
 * │ ╰   0x000000012142b6c4:   jl     0x000000012142b690
 * <p>
 * ---- speculation with uncommon trap[
 * c2, level 4, com.lstec.jvm.BenchmarkC2LackOfProfile$BenchmarkData::outerNotInlined, version 825 (40 bytes)
 * <p>
 * # parm0:    rdx:rdx   = &apos;com/lstec/jvm/BenchmarkC2LackOfProfile$BenchmarkData$Base&apos;
 * #           [sp+0x20]  (sp of caller)
 * 0x000000012142ada0:   mov    0x8(%rsi),%r10d
 * 0x000000012142ada4:   movabs $0x800000000,%r11
 * 0x000000012142adae:   add    %r11,%r10
 * 0x000000012142adb1:   cmp    %r10,%rax
 * 0x000000012142adb4:   jne    0x0000000119894780           ;   {runtime_call ic_miss_stub}
 * 0x000000012142adba:   xchg   %ax,%ax
 * 0x000000012142adbc:   nopl   0x0(%rax)
 * [Verified Entry Point]
 * 4.37%      0x000000012142adc0:   mov    %eax,-0x14000(%rsp)
 * 9.36%      0x000000012142adc7:   push   %rbp
 * 2.81%      0x000000012142adc8:   sub    $0x10,%rsp                   ;*synchronization entry
 * ; - com.lstec.jvm.BenchmarkC2LackOfProfile$BenchmarkData::outerNotInlined@-1 (line 58)
 * 6.68%      0x000000012142adcc:   test   %rdx,%rdx
 * ╭   0x000000012142adcf:   je     0x000000012142ade9
 * 3.34%  │   0x000000012142add1:   mov    $0x7,%eax
 * 2.52%  │   0x000000012142add6:   add    $0x10,%rsp
 * 1.55%  │   0x000000012142adda:   pop    %rbp
 * 7.22%  │   0x000000012142addb:   cmp    0x348(%r15),%rsp             ;   {poll_return}
 * │╭  0x000000012142ade2:   ja     0x000000012142adf4
 * 5.77%  ││  0x000000012142ade8:   retq
 * ↘│  0x000000012142ade9:   mov    $0xfffffff6,%esi
 * │  0x000000012142adee:   nop
 * │  0x000000012142adef:   callq  0x000000011989a000           ; ImmutableOopMap {}
 * │                                                            ;*invokevirtual virtualCall {reexecute=0 rethrow=0 return_oop=0}
 * │                                                            ; - com.lstec.jvm.BenchmarkC2LackOfProfile$BenchmarkData::outerNotInlined@1 (line 58)
 * │                                                            ;   {runtime_call UncommonTrapBlob}
 */
@State(Scope.Thread)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Fork(1)
@Warmup(iterations = 50, time = 1)
@Measurement(iterations = 10, time = 1)
@BenchmarkMode(Mode.AverageTime)
public class BenchmarkC2LackOfProfile
{

    @State(Scope.Thread)
    @SuppressWarnings("unused")
    public static class BenchmarkData
    {
        private static final Random random = new Random(1231255);

        @Param({"true", "false"})
        private boolean polluteProfileInSetup = true;
        private Base[] data;

        @CompilerControl(CompilerControl.Mode.DONT_INLINE)
        private static long outerNotInlined(Base b)
        {
            return b.virtualCall() + b.virtualCall() * 3;
//            return b.virtualCall();
        }

        private static long outerInlined(Base b)
        {
            return b.virtualCall() + b.virtualCall() * 3;
//            return b.virtualCall();
        }

        @CompilerControl(CompilerControl.Mode.DONT_INLINE)
        public long sumNotInlined(Base[] data)
        {
            long result = 0;
            for (int i = 0; i < data.length; i++) {
                result += outerNotInlined(data[i]);
            }
            return result;
        }

        @CompilerControl(CompilerControl.Mode.DONT_INLINE)
        public long sumInlined(Base[] data)
        {
            long result = 0;
            for (int i = 0; i < data.length; i++) {
                result += outerInlined(data[i]);
            }
            return result;
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
        public void setup(BenchmarkParams benchmarkParams, Blackhole blackhole)
        {
            int numberOfObjects = 10000;
            if (benchmarkParams != null) {
                benchmarkParams.setOpsPerInvocation(numberOfObjects);
            }

            if (polluteProfileInSetup) {
                Base[] polluted = new Base[11_000];
                for (int i = 0; i < polluted.length; i++) {
                    int selector = random.nextInt(3);
                    switch (selector) {
                        case 0:
                            polluted[i] = new Impl0();
                            break;
                        case 1:
                            polluted[i] = new Impl1();
                            break;
                        case 2:
                            polluted[i] = new Impl2();
                            break;
                        default:
                            throw new RuntimeException();
                    }
                }
                long result = 0;
                for (int i = 0; i < polluted.length; i++) {
                    result += outerNotInlined(polluted[i]);
                }
                for (int i = 0; i < polluted.length; i++) {
                    result += outerInlined(polluted[i]);
                }
                blackhole.consume(result);
                // let the c1 do the work
                Uninterruptibles.sleepUninterruptibly(Duration.ofSeconds(2));
                for (int i = 0; i < polluted.length; i++) {
                    result += outerNotInlined(polluted[i]);
                }
                for (int i = 0; i < polluted.length; i++) {
                    result += outerInlined(polluted[i]);
                }
                blackhole.consume(result);
                // let the c2 do the work
                Uninterruptibles.sleepUninterruptibly(Duration.ofSeconds(2));
            }

            data = new Base[numberOfObjects];
            for (int i = 0; i < data.length; i++) {
                data[i] = new Impl0();
            }
        }
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public Object testVirtualCallNotInlined(BenchmarkData data)
    {
        return data.sumNotInlined(data.data);
    }

    @Benchmark
    @CompilerControl(CompilerControl.Mode.DONT_INLINE)
    public Object testVirtualCallInlined(BenchmarkData data)
    {
        return data.sumInlined(data.data);
    }

    public static void main(String[] args)
            throws Exception
    {
        benchmark(BenchmarkC2LackOfProfile.class)
                .withProfilerOutputBaseDir("jmh")
                .includeMethod("testVirtualCallInlined")
                .withOptions((optionsBuilder, profilerOutputDir) ->
                        optionsBuilder.forks(1)
                                .warmupIterations(10)
                                .param("polluteProfileInSetup", "true")
                                .addProfiler(DTraceAsmProfiler.class, String.format("hotThreshold=0.1;tooBigThreshold=3000;saveLog=true;saveLogTo=%s", profilerOutputDir, profilerOutputDir))
                )
                .run();
    }
}
