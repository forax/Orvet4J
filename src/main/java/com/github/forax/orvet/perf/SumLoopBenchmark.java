package com.github.forax.orvet.perf;

import com.github.forax.orvet.engine.Engine;
import com.github.forax.orvet.rt.ModuleContext;
import com.github.forax.orvet.rt.PyFunction;
import com.github.forax.orvet.rt.intrinsic.ArithmeticIntrinsics;
import com.github.forax.orvet.rt.intrinsic.ComparisonIntrinsics;
import org.openjdk.jmh.annotations.*;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.OptionsBuilder;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodType;
import java.util.concurrent.TimeUnit;

// Java 28 preview
//Benchmark                              Mode  Cnt   Score   Error  Units
//SumLoopBenchmark.java_boxed_Long       avgt    5   6.516 ± 0.272  us/op
//SumLoopBenchmark.java_boxed_hand_made  avgt    5   7.120 ± 0.031  us/op
//SumLoopBenchmark.java_primitive_long   avgt    5   3.041 ± 0.007  us/op
//SumLoopBenchmark.python_generic_int    avgt    5  26.564 ± 0.816  us/op
//SumLoopBenchmark.python_int            avgt    5   6.494 ± 0.095  us/op

// Java 27
//Benchmark                              Mode  Cnt    Score   Error  Units
//SumLoopBenchmark.java_boxed_Long       avgt    5   20.646 ± 0.226  us/op
//SumLoopBenchmark.java_boxed_hand_made  avgt    5   20,839 ± 0.714  us/op
//SumLoopBenchmark.java_primitive_long   avgt    5    3.039 ± 0.004  us/op
//SumLoopBenchmark.python_generic_int    avgt    5   21.669 ± 0.539  us/op
//SumLoopBenchmark.python_int            avgt    5   22.833 ± 0.079  us/op

// Python 3.14.8
//Benchmark                              Mode  Cnt    Score   Error  Units
//                                       avgt    5  223.183 ± 0.711  us/op

@Warmup(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Measurement(iterations = 5, time = 1, timeUnit = TimeUnit.SECONDS)
@Fork(value = 1, jvmArgsAppend = {"--enable-preview"})
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.MICROSECONDS)
@State(Scope.Benchmark)
public class SumLoopBenchmark {

  private static ModuleContext run(String source) {
    var context = new ModuleContext();
    Engine.run(context, source);
    return context;
  }

  private static final long LIMIT = 10_000;

  // Compile the Python loop once and obtain a direct MethodHandle adapted to ()long
  private static final MethodHandle PYTHON_LOOP_MH;
  static {
    var source = """
        def loop():
            i = 0
            sum = 0
            while i < LIMIT:
                sum = sum + i
                i = i + 1
            return sum
        """.replace("LIMIT", "" + LIMIT);
    var fn = (PyFunction) run(source).getGlobal("loop");
    PYTHON_LOOP_MH = fn.createDirectMethodHandle(MethodType.methodType(long.class));
  }

  @Benchmark
  public long java_primitive_long() {
    var i = 0L;
    var sum = 0L;
    while (i < LIMIT) {
      sum = sum + i;
      i = i + 1;
    }
    return sum;
  }

  @Benchmark
  public Long java_boxed_Long() {
    var i = (Long) 0L;
    var sum = (Long) 0L;
    while (i < LIMIT) {
      sum = sum + i; // Unboxes, adds, and boxes back to Long
      i = i + 1;     // Unboxes, increments, and boxes back to Long
    }
    return sum;
  }

  @Benchmark
  public Long java_boxed_hand_made() {
    var i = (Long) 0L;
    var sum = (Long) 0L;
    while (i < LIMIT) {
      try {
        sum = Math.addExact(sum, i);
      } catch (ArithmeticException e) {
        throw new AssertionError(e);
      }
      try {
        i = Math.addExact(i, 1);
      } catch (ArithmeticException e) {
        throw new AssertionError(e);
      }
    }
    return sum;
  }

  @Benchmark
  public long python_int() throws Throwable {
    return (long) PYTHON_LOOP_MH.invokeExact();
  }

  @Benchmark
  public Object python_generic_int() {
    var i = (Object) 0L;
    var sum = (Object) 0L;
    while (ComparisonIntrinsics.$lt$(i, LIMIT)) {
      sum = ArithmeticIntrinsics.$add$(sum, i);
      i = ArithmeticIntrinsics.$add$(i, 1L);
    }
    return sum;
  }

  static void main() throws RunnerException {
    var opt = new OptionsBuilder()
        .include(SumLoopBenchmark.class.getSimpleName())
        .build();
    new Runner(opt).run();
  }
}