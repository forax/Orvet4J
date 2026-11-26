package com.github.forax.orvet.rt;

import com.github.forax.orvet.rt.error.PyAttributeError;
import com.github.forax.orvet.rt.error.PyTypeError;
import com.github.forax.orvet.rt.error.UnimplementedFeatureError;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import java.lang.reflect.UndeclaredThrowableException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

/// Tests the method-call bootstrap, its receiver guards and intrinsic lookup.
public final class MethodSupportTest {
  private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

  private static MutableCallSite methodCallSite(String name, int parameterCount) {
    var type = MethodType.genericMethodType(parameterCount);
    return assertInstanceOf(MutableCallSite.class,
        RT.bsmMethodCall(LOOKUP, name, type, parameterCount));
  }

  private static Object invoke(CallSite callSite, Object... args)  {
    var mh = callSite.dynamicInvoker();
    try {
      return mh.invokeWithArguments(args);
    } catch (RuntimeException | Error e) {
      throw e;
    } catch (Throwable e) {
      throw new UndeclaredThrowableException(e);
    }
  }


  @Test
  public void aStringMethodCallSiteLinksAndKeepsItsMonomorphicTarget() {
    var site = methodCallSite("upper", 1);
    var initialTarget = site.getTarget();

    assertEquals("ORVET", invoke(site, "orvet"));
    var linkedTarget = site.getTarget();
    assertNotSame(initialTarget, linkedTarget);

    for (var i = 0; i < 20; i++) {
      assertEquals("ORVET", invoke(site, "orvet"));
    }
    assertSame(linkedTarget, site.getTarget());
  }

  @Test
  public void stringOverloadsAreSelectedFromTheCallSiteArity() {
    assertEquals(1L, invoke(methodCallSite("find", 2), "banana", "a"));
    assertEquals(3L, invoke(methodCallSite("find", 3), "banana", "a", 2L));
    assertEquals(1L, invoke(methodCallSite("find", 4), "banana", "a", 0L, 3L));
  }

  @Test
  public void listAndDictionaryIntrinsicsCanShareOneCallSite() {
    var copy = methodCallSite("copy", 1);

    var list = new PyList<>(1L, 2L);
    var listCopy = assertInstanceOf(PyList.class, invoke(copy, list));
    assertNotSame(list, listCopy);
    assertEquals(list, listCopy);

    var dictionary = new PyDict<Object, Object>();
    dictionary.put("answer", 42L);
    var dictionaryCopy = assertInstanceOf(PyDict.class, invoke(copy, dictionary));
    assertNotSame(dictionary, dictionaryCopy);
    assertEquals(dictionary, dictionaryCopy);
  }

  @Test
  public void overloadedPopAndGetMethodsAreResolvedForTheirArgumentCounts() {
    var listPop = methodCallSite("pop", 1);
    assertEquals(20L, invoke(listPop, new PyList<>(10L, 20L)));

    // The same (receiver, argument) call site handles list.pop(index) and dict.pop(key).
    var pop = methodCallSite("pop", 2);
    var list = new PyList<>(10L, 20L);
    assertEquals(10L, invoke(pop, list, 0L));
    assertEquals(20L, invoke(pop, list, 0L));

    var dictionary = new PyDict<Object, Object>();
    dictionary.put("answer", 42L);
    assertEquals(42L, invoke(pop, dictionary, "answer"));
    assertEquals(-1L, invoke(methodCallSite("pop", 3), dictionary, "missing", -1L));

    var get = methodCallSite("get", 2);
    assertEquals(PyNone.INSTANCE, invoke(get, dictionary, "missing"));
    assertEquals("fallback", invoke(methodCallSite("get", 3), dictionary, "missing", "fallback"));
  }

  @Test
  public void listIndexOverloadsAreSelectedFromTheCallSiteArity() {
    var list = new PyList<>(10L, 20L, 10L);

    assertEquals(0L, invoke(methodCallSite("index", 2), list, 10L));
    assertEquals(2L, invoke(methodCallSite("index", 3), list, 10L, 1L));
    assertEquals(2L, invoke(methodCallSite("index", 4), list, 10L, 1L, 3L));
  }

  @Test
  public void dictionaryTypeMethodsUseAnIdentityGuardAndSupportOverloads() {
    var keys = new PyList<>("alpha", "beta");
    var fromKeys = methodCallSite("fromkeys", 2);

    var defaultValues = assertInstanceOf(PyDict.class, invoke(fromKeys, PyType.DICT, keys));
    assertEquals(2, defaultValues.size());
    assertSame(PyNone.INSTANCE, defaultValues.get("alpha"));
    assertSame(PyNone.INSTANCE, defaultValues.get("beta"));

    var explicitValues = assertInstanceOf(PyDict.class,
        invoke(methodCallSite("fromkeys", 3), PyType.DICT, keys, 99L));
    assertEquals(99L, explicitValues.get("alpha"));
    assertEquals(99L, explicitValues.get("beta"));

    var error = assertThrows(PyAttributeError.class,
        () -> invoke(fromKeys, PyType.INT, keys));
    assertEquals("'int' has no attribute 'fromkeys'", error.getMessage());

    // A failed receiver check must not change the already linked dict type path.
    var secondResult = assertInstanceOf(PyDict.class, invoke(fromKeys, PyType.DICT, keys));
    assertEquals(2, secondResult.size());
  }

  @Test
  public void unsupportedReceiversAndMissingMethodsRaiseAttributeError() {
    var upper = methodCallSite("upper", 1);
    var initialTarget = upper.getTarget();

    var error = assertThrows(PyAttributeError.class,
        () -> invoke(upper, 42L));
    assertEquals("'int' object has no attribute 'upper'", error.getMessage());
    assertSame(initialTarget, upper.getTarget());

    assertEquals("ORVET", invoke(upper, "orvet"));
    error = assertThrows(PyAttributeError.class,
        () -> invoke(upper, 42L));
    assertEquals("'int' object has no attribute 'upper'", error.getMessage());

    var missing = methodCallSite("not_a_method", 1);
    error = assertThrows(PyAttributeError.class,
        () -> invoke(missing, "orvet"));
    assertEquals("'str' object has no attribute 'not_a_method'", error.getMessage());
  }

  @Test
  public void methodCallsWithKeywordArgumentsFailAtBootstrapTime() throws Throwable {
    var type = MethodType.genericMethodType(2);
    var site = RT.bsmMethodCall(LOOKUP, "get", type, 1, "default");
    assertInstanceOf(ConstantCallSite.class, site);

    var error = assertThrows(UnimplementedFeatureError.class,
        () -> invoke(site, new PyDict<>(), "missing"));
    assertEquals("method calls with keyword arguments are not supported yet", error.getMessage());
  }

  @Test
  @Disabled
  public void existingMethodWithWrongArityShouldRaiseTypeError() {
    var upper = methodCallSite("upper", 2);

    var exception = assertThrows(PyTypeError.class,
        () -> invoke(upper, "orvet", "unexpected"));
    assertEquals("upper expected at most 1 arguments, got 2", exception.getMessage());
  }
}
