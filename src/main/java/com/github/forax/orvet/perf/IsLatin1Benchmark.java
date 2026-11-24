package com.github.forax.orvet.perf;

import com.github.forax.orvet.rt.intrinsic.StringIntrinsics;
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
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.concurrent.TimeUnit;

@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgsAppend = {"--enable-preview"})
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@State(Scope.Benchmark)
public class IsLatin1Benchmark {

  static final MethodHandle IS_LATIN1;
  static {
    var lookup = MethodHandles.lookup();
    try {
      var teleport = MethodHandles.privateLookupIn(StringIntrinsics.class, lookup);
      IS_LATIN1 = teleport.findStatic(StringIntrinsics.class, "isLatin1", MethodType.methodType(boolean.class, String.class));
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new AssertionError(e);
    }
  }

  public IsLatin1Benchmark() {
    this.string = "abc";
  }

  String string;

  @Benchmark
  public boolean isLatin1() throws Throwable {
    return (boolean) IS_LATIN1.invokeExact(string);
  }

  static void main() throws RunnerException {
    var opt = new OptionsBuilder()
        .include(IsLatin1Benchmark.class.getSimpleName())
        .build();
    new Runner(opt).run();
  }
}