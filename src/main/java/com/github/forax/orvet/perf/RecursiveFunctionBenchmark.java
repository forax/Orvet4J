package com.github.forax.orvet.perf;

import com.github.forax.orvet.compiler.Compiler;
import com.github.forax.orvet.rt.ModuleContext;
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

// Java 28 preview
// Benchmark                               Mode  Cnt   Score   Error  Units
//RecursiveFunctionBenchmark.java_fibo    avgt    5   29.509 ± 0.414  us/op
//RecursiveFunctionBenchmark.python_fibo  avgt    5   70.379 ± 5.540  us/op

// Python 3.14.8
//                                        avgt    5  840.929 ± 9.449  us/op

@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgsAppend = {"--enable-preview"})
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
public class RecursiveFunctionBenchmark {

  private static ModuleContext run(String source) {
    var context = new ModuleContext();
    Compiler.run(context, source);
    return context;
  }

  private static final MethodHandle FIBO_MH;
  static {
    var source = """
        def fibo(n):
            if (n < 2):
              return 1
            return fibo(n - 1) + fibo(n - 2)
        """;
    var fibo = (PyFunction) run(source).getGlobal("fibo");
    FIBO_MH = fibo.createDirectMethodHandle(MethodType.methodType(long.class, long.class));
  }

  @Benchmark
  public long python_fibo() throws Throwable {
    return (long) FIBO_MH.invokeExact(21L);
  }

  private static long fibo(long n) {
    if (n < 2) {
      return 1;
    }
    return fibo(n - 1) + fibo(n - 2);
  }

  @Benchmark
  public long java_fibo() throws Throwable {
    return fibo(21L);
  }

  static void main() throws RunnerException {
    var opt = new OptionsBuilder()
        .include(RecursiveFunctionBenchmark.class.getSimpleName())
        .build();
    new Runner(opt).run();
  }
}