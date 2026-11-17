package com.github.forax.orvet.rt;

import com.github.forax.orvet.ast.ASTBuilder;
import com.github.forax.orvet.engine.Engine;
import com.github.forax.orvet.rt.error.PyNameError;
import com.github.forax.orvet.rt.error.PyTypeError;
import org.junit.jupiter.api.Test;

import java.lang.invoke.MethodType;
import java.lang.reflect.UndeclaredThrowableException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public final class GlobalCallTest {

  private static void runIn(ModuleContext context, String source) {
    var main = Engine.compile(context, ASTBuilder.createScript(source));
    try {
      main.invokeExact();
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Throwable e) {
      throw new UndeclaredThrowableException(e);
    }
  }

  @Test
  public void topLevelFunctionDirectCall() {
    var ctx = new ModuleContext();
    runIn(ctx, """
        def add(a, b):
            return a + b
        res = add(10, 20)
        """);
    assertEquals(30L, ctx.getGlobal("res"));
  }

  @Test
  public void topLevelRecursiveFunction() {
    var ctx = new ModuleContext();
    runIn(ctx, """
        def fibo(n):
            if n < 2:
                return 1
            return fibo(n - 1) + fibo(n - 2)
        res = fibo(7)
        """);
    assertEquals(21L, ctx.getGlobal("res"));
  }

  @Test
  public void builtinGlobalCall() {
    var ctx = new ModuleContext();
    runIn(ctx, """
        res = len("hello")
        """);
    assertEquals(5L, ctx.getGlobal("res"));
  }

  @Test
  public void globalCallWithKeywords() {
    var ctx = new ModuleContext();
    runIn(ctx, """
        def sub(a, b):
            return a - b
        res = sub(b=5, a=20)
        """);
    assertEquals(15L, ctx.getGlobal("res"));
  }

  @Test
  public void closureAssignedToGlobal() {
    var ctx = new ModuleContext();
    runIn(ctx, """
        def make_adder(x):
            def add(y):
                return x + y
            return add
        adder = make_adder(100)
        res = adder(42)
        """);
    assertEquals(142L, ctx.getGlobal("res"));
  }

  @Test
  public void rebindGlobalFunctionMultipleTimes() {
    var ctx = new ModuleContext();
    runIn(ctx, """
        def f(x):
            return x + 1
        def call_f(x):
            return f(x)
        r0 = call_f(10)
        """);
    assertEquals(11L, ctx.getGlobal("r0"));

    runIn(ctx, """
        def f(x):
            return x + 2
        r1 = call_f(10)
        """);
    assertEquals(12L, ctx.getGlobal("r1"));

    runIn(ctx, """
        def f(x):
            return x + 3
        r2 = call_f(10)
        """);
    assertEquals(13L, ctx.getGlobal("r2"));

    runIn(ctx, """
        def f(x):
            return x + 4
        r3 = call_f(10)
        """);
    assertEquals(14L, ctx.getGlobal("r3"));

    runIn(ctx, """
        def f(x):
            return x + 5
        r4 = call_f(10)
        """);
    assertEquals(15L, ctx.getGlobal("r4"));

    // 5th invalidation degrades to foldArguments + megamorphic
    runIn(ctx, """
        def f(x):
            return x + 6
        r5 = call_f(10)
        """);
    assertEquals(16L, ctx.getGlobal("r5"));

    // After degradation, further rebinds continue to work seamlessly
    runIn(ctx, """
        def f(x):
            return x + 7
        r6 = call_f(10)
        """);
    assertEquals(17L, ctx.getGlobal("r6"));
  }

  @Test
  public void undefinedGlobalCallRaisesNameErrorThenRecovers() {
    var ctx = new ModuleContext();
    assertThrows(PyNameError.class, () -> runIn(ctx, """
        def runner():
            return target_fn(10)
        runner()
        """));

    runIn(ctx, """
        def target_fn(x):
            return x * 3
        res = runner()
        """);
    assertEquals(30L, ctx.getGlobal("res"));
  }

  @Test
  public void nonCallableGlobalRaisesTypeErrorThenRecovers() {
    var ctx = new ModuleContext();
    runIn(ctx, """
        target_fn = 42
        def runner():
            return target_fn(10)
        """);
    assertThrows(PyTypeError.class, () -> runIn(ctx, "runner()"));

    runIn(ctx, """
        def target_fn(x):
            return x * 2
        res = runner()
        """);
    assertEquals(20L, ctx.getGlobal("res"));
  }

  @Test
  public void localVariableShadowingGlobalDoesNotUseGlobalCall() {
    var ctx = new ModuleContext();
    runIn(ctx, """
        def f(x):
            return x + 1
        def caller():
            def local_f(x):
                return x * 10
            f = local_f
            return f(5)
        res = caller()
        """);
    assertEquals(50L, ctx.getGlobal("res"));
  }
}
