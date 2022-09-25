/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.lstec.jvm;

import org.intellij.lang.annotations.Language;
import org.openjdk.jmh.results.RunResult;
import org.openjdk.jmh.results.format.ResultFormatType;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.ChainedOptionsBuilder;
import org.openjdk.jmh.runner.options.OptionsBuilder;
import org.openjdk.jmh.runner.options.VerboseMode;
import org.openjdk.jmh.runner.options.WarmupMode;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.Comparator;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.stream.Stream;

import static java.lang.String.format;
import static java.time.format.DateTimeFormatter.ISO_DATE_TIME;
import static java.util.Objects.requireNonNull;

public final class Benchmarks
{
    private Benchmarks() {}

    public static BenchmarkBuilder benchmark(Class<?> benchmarkClass)
    {
        ChainedOptionsBuilder optionsBuilder = new OptionsBuilder()
                .verbosity(VerboseMode.NORMAL)
                .resultFormat(ResultFormatType.JSON)
                .result(format("%s/%s-result-%s.json", System.getProperty("java.io.tmpdir"), benchmarkClass.getSimpleName(), ISO_DATE_TIME.format(LocalDateTime.now())));
        return new BenchmarkBuilder(optionsBuilder, benchmarkClass);
    }

    public static BenchmarkBuilder benchmark(Class<?> benchmarkClass, WarmupMode warmupMode)
    {
        return benchmark(benchmarkClass)
                .withOptions(optionsBuilder -> optionsBuilder.warmupMode(warmupMode));
    }

    public static class BenchmarkBuilder
    {
        private final ChainedOptionsBuilder optionsBuilder;
        private final Class<?> benchmarkClass;
        private String profilerOutputDir;

        private BenchmarkBuilder(ChainedOptionsBuilder optionsBuilder, Class<?> benchmarkClass)
        {
            this.optionsBuilder = requireNonNull(optionsBuilder, "optionsBuilder is null");
            this.benchmarkClass = requireNonNull(benchmarkClass, "benchmarkClass is null");
        }

        public BenchmarkBuilder withOptions(Consumer<ChainedOptionsBuilder> optionsConsumer)
        {
            optionsConsumer.accept(optionsBuilder);
            return this;
        }

        public BenchmarkBuilder withOptions(BiConsumer<ChainedOptionsBuilder, String> optionsConsumer)
        {
            optionsConsumer.accept(optionsBuilder, requireNonNull(profilerOutputDir, "profilerOutputDir is null"));
            return this;
        }

        public BenchmarkBuilder includeAll()
        {
            optionsBuilder.include("^\\Q" + benchmarkClass.getName() + ".\\E");
            return this;
        }

        public BenchmarkBuilder includeMethod(@Language("RegExp") String benchmarkMethod)
        {
            optionsBuilder.include("^\\Q" + benchmarkClass.getName() + ".\\E(" + benchmarkMethod + ")$");
            return this;
        }

        public Collection<RunResult> run()
                throws RunnerException
        {
            if (optionsBuilder.build().getIncludes().isEmpty()) {
                includeAll();
            }
            try {
                if (profilerOutputDir != null) {
                    new File(profilerOutputDir).mkdirs();
                }
                return new Runner(optionsBuilder.build()).run();
            }
            finally {
                if (profilerOutputDir != null) {
                    File dir = new File(profilerOutputDir);
                    if (dir.list().length == 0) {
                        dir.delete();
                    }
                }
            }
        }

        public BenchmarkBuilder withProfilerOutputBaseDir(String profilerOutputBaseDir)
        {
            this.profilerOutputDir = profilerOutputDir(profilerOutputBaseDir);
            return this;
        }

        private static String profilerOutputDir(String profileOutputBaseDir)
        {
            requireNonNull(profileOutputBaseDir, "profileOutputBaseDir is null");
            try {
                Stream<Path> files = Files.exists(Path.of(profileOutputBaseDir)) ? Files.list(Paths.get(profileOutputBaseDir)) : Stream.empty();
                String subDirName = String.valueOf(files
                        .map(path -> path.getFileName().toString())
                        .filter(path -> path.matches("\\d+"))
                        .map(path -> Integer.parseInt(path) + 1)
                        .sorted(Comparator.reverseOrder())
                        .findFirst().orElse(0));

                return profileOutputBaseDir + "/" + subDirName;
            }
            catch (IOException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
