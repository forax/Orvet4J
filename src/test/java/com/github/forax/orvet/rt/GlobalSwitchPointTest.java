package com.github.forax.orvet.rt;

import com.github.forax.orvet.engine.Engine;
import com.github.forax.orvet.rt.error.PyNameError;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.lang.invoke.MethodHandle;
import java.lang.ref.WeakReference;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.ArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class GlobalSwitchPointTest {

  private static Object read(MethodHandle handle) {
    try {
      return (Object) handle.invokeExact();
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Throwable t) {
      throw new UndeclaredThrowableException(t);
    }
  }

  private static MethodHandle getter(ModuleContext context, String name) {
    return GlobalSupport.getGlobal(context, name).dynamicInvoker();
  }

  @Test
  public void aSiteLinkedWhileUnboundSeesTheLaterBinding() {
    var context = new ModuleContext();
    var site = getter(context, "x");
    assertThrows(PyNameError.class, () -> read(site));

    context.setGlobal("x", 1L);
    assertEquals(1L, read(site));
  }

  @Test
  public void aFoldedSiteSeesEveryRebinding() {
    var context = new ModuleContext();
    context.setGlobal("x", 1L);
    var before = getter(context, "x");
    assertEquals(1L, read(before));

    context.setGlobal("x", 2L);
    var after = getter(context, "x");
    assertEquals(2L, read(before));
    assertEquals(2L, read(after));

    context.setGlobal("x", 3L);
    assertEquals(3L, read(before));
    assertEquals(3L, read(after));
  }

  @Test
  public void storesThroughTheCallSiteInvalidateToo() throws Throwable {
    var context = new ModuleContext();
    context.setGlobal("x", 1L);

    var get = getter(context, "x");
    var set = GlobalSupport.setGlobal(context, "x").dynamicInvoker();
    assertEquals(1L, read(get));

    set.invokeExact((Object) 7L);
    assertEquals(7L, read(get));
  }

  @Test
  public void aGlobalShadowsABuiltinEvenForSitesThatFoldedTheBuiltin() {
    var context = new ModuleContext();
    var len = Builtins.BUILTINS.get("len");
    var site = getter(context, "len");
    assertSame(len, read(site));

    context.setGlobal("len", 5L);
    assertEquals(5L, read(site));

    var other = new ModuleContext();  // cells are per module
    assertSame(len, read(getter(other, "len")));
  }

  @Test
  public void compiledCodeSeesRebindingAfterTheCallSiteFolded() {
    var context = new ModuleContext();
    Engine.run(context, """
        def read():
            return x
        x = 1
        a = read()
        x = 2
        b = read()
        x = 3
        c = read()
        x = 4
        d = read()
        x = 5
        e = read()
        """);
    assertEquals(1L, context.getGlobal("a"));
    assertEquals(2L, context.getGlobal("b"));
    assertEquals(3L, context.getGlobal("c"));
    assertEquals(4L, context.getGlobal("d"));
    assertEquals(5L, context.getGlobal("e"));
  }

  @Test
  @Timeout(60)
  public void readersNeverSeeAValueGoingBackwards() throws Exception {
    var context = new ModuleContext();
    context.setGlobal("counter", 0L);
    var linkedEarly = getter(context, "counter");
    var done = new AtomicBoolean();

    try (var pool = Executors.newFixedThreadPool(4)) {
      var futures = new ArrayList<Future<?>>();
      for (var task = 0; task < 4; task++) {
        var relink = task % 2 == 1;  // half of the readers also link new sites while the writer runs
        futures.add(pool.submit(() -> {
          var last = -1L;
          while (!done.get()) {
            var handle = relink ? getter(context, "counter") : linkedEarly;
            var value = (Long) read(handle);
            assertTrue(value >= last, value + " after " + last);
            last = value;
          }
        }));
      }
      for (var i = 1L; i <= 20_000; i++) {
        context.setGlobal("counter", i);
      }
      done.set(true);
      for (var future : futures) {
        future.get();
      }
    }
    assertEquals(20_000L, context.getGlobal("counter"));
  }

  @Test
  public void globalDoesNotKeepReferenceOnPreviousValue() {
    var dict = new PyDict<>();
    var weak = new WeakReference<>(dict);

    var context = new ModuleContext();
    context.setGlobal("dict", dict);
    dict = null;

    var site = getter(context, "dict");
    read(site);
    read(site);

    context.setGlobal("dict", new PyDict<>());
    read(site);

    System.gc();

    assertTrue(weak.refersTo(null));
  }
}