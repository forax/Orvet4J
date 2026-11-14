package com.github.forax.orvet.perf;

import com.github.forax.orvet.compiler.Compiler;
import com.github.forax.orvet.rt.PyFunction;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.concurrent.TimeUnit;

@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgsAppend = {"--enable-preview"})
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class LookupBenchmark {

  private static final MethodHandle LOOKUP_LOCAL_MH, LOOKUP_DICT_MH;
  static {
    var lookupLocalSource = """
        def lookup_local():
            x = 42
            return x + x
        """;
    var lookupLocal = (PyFunction) Compiler.run(lookupLocalSource).getGlobal("lookup_local");
    LOOKUP_LOCAL_MH = lookupLocal.createDirectMethodHandle(MethodType.methodType(long.class));

    var lookupDictSource = """
        def lookup_dict():
            d = {"value": 42}
            return d["value"] + d["value"]
        """;
    var lookupDict = (PyFunction) Compiler.run(lookupDictSource).getGlobal("lookup_dict");
    LOOKUP_DICT_MH = lookupDict.createDirectMethodHandle(MethodType.methodType(long.class));
  }

  @Benchmark
  public long lookup_local() throws Throwable {
    return (long) LOOKUP_LOCAL_MH.invokeExact();
  }

  @Benchmark
  public long lookup_dict() throws Throwable {
    return (long) LOOKUP_DICT_MH.invokeExact();
  }

  static void main() throws RunnerException {
    var opt = new OptionsBuilder()
        .include(LookupBenchmark.class.getSimpleName())
        .build();
    new Runner(opt).run();
  }
}