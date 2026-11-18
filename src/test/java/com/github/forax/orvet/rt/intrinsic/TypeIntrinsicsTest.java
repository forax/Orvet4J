package com.github.forax.orvet.rt.intrinsic;

import com.github.forax.orvet.engine.Engine;
import com.github.forax.orvet.rt.ModuleContext;
import com.github.forax.orvet.rt.PyDict;
import com.github.forax.orvet.rt.PyList;
import com.github.forax.orvet.rt.PyNone;
import com.github.forax.orvet.rt.PyRange;
import com.github.forax.orvet.rt.PySlice;
import com.github.forax.orvet.rt.PyTuple;
import com.github.forax.orvet.rt.PyType;
import com.github.forax.orvet.rt.error.PyOverflowError;
import com.github.forax.orvet.rt.error.PyTypeError;
import com.github.forax.orvet.rt.error.PyValueError;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static com.github.forax.orvet.rt.intrinsic.TypeIntrinsics.$bool$;
import static com.github.forax.orvet.rt.intrinsic.TypeIntrinsics.$int$;
import static com.github.forax.orvet.rt.intrinsic.TypeIntrinsics.$type$;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class TypeIntrinsicsTest {
  @Test
  public void typeRecognizesTheCurrentlySupportedRuntimeTypes() {
    assertSame(PyType.INT, $type$(42L));
    assertSame(PyType.INT, $type$(BigInteger.ONE.shiftLeft(100)));
    assertSame(PyType.BOOL, $type$(true));
    assertSame(PyType.STR, $type$("hello"));
    assertSame(PyType.NONE_TYPE, $type$(PyNone.INSTANCE));
    assertSame(PyType.DICT, $type$(new PyDict<>()));
    assertSame(PyType.LIST, $type$(new PyList<>()));
    assertSame(PyType.TUPLE, $type$(new PyTuple.PyTuple1(1L)));
    assertSame(PyType.RANGE, $type$(PyRange.of(0, 10, 1)));
    assertSame(PyType.SLICE, $type$(new PySlice(0L, 10L, 1L)));

    var context = new ModuleContext();
    Engine.run(context, "def f(): pass");
    var function = context.getGlobal("f");
    assertSame(PyType.FUNCTION, $type$(function));
  }

  @Test
  @Disabled
  public void typeRecognizesOtherCanonicalRuntimeRepresentations() {
    // These are canonical runtime values too (IntrinsicSupport.isCanonical),
    // although TypeIntrinsics currently has no cases for them.
    assertDoesNotThrow(() -> $type$(2.5d));
  }

  @Test
  public void typeRejectsAnObjectOutsideTheRuntimeRepresentation() {
    assertThrows(NotCanonicalException.class, () -> $type$(new Object()));
  }

  @Test
  public void boolWithoutAnArgumentIsFalse() {
    assertFalse((Boolean) $bool$());
  }

  @Test
  public void boolUsesPythonTruthiness() {
    assertFalse((Boolean) $bool$(false));
    assertFalse((Boolean) $bool$(0L));
    assertFalse((Boolean) $bool$(BigInteger.ZERO));
    assertFalse((Boolean) $bool$(0.0d));
    assertFalse((Boolean) $bool$(""));
    assertFalse((Boolean) $bool$(PyNone.INSTANCE));
    assertFalse((Boolean) $bool$(new PyList<>()));
    assertFalse((Boolean) $bool$(new PyTuple.PyTupleN(new Object[0])));
    assertFalse((Boolean) $bool$(new PyDict<>()));

    assertTrue((Boolean) $bool$(true));
    assertTrue((Boolean) $bool$(-1L));
    assertTrue((Boolean) $bool$(BigInteger.ONE));
    assertTrue((Boolean) $bool$(Double.NaN));
    assertTrue((Boolean) $bool$("x"));
    assertTrue((Boolean) $bool$(new PyList<>(1L)));
    assertTrue((Boolean) $bool$(new PyTuple.PyTuple1(1L)));
    var dict = new PyDict<String, Object>();
    dict.put("x", 1L);
    assertTrue((Boolean) $bool$(dict));
  }

  @Test
  public void boolRejectsMoreThanOneArgumentWithTheBoolName() {
    var error = assertThrows(PyTypeError.class, () -> $bool$(true, false));
    assertEquals("bool() expected at most 1 argument, got 2", error.getMessage());
  }

  @Test
  public void intConvertsNumbersAndNormalizesBigIntegers() {
    assertEquals(0L, $int$());
    assertEquals(1L, $int$(true));
    assertEquals(0L, $int$(false));
    assertEquals(-42L, $int$(-42L));
    assertEquals(3L, $int$(3.9d));
    assertEquals(-3L, $int$(-3.9d));
    assertEquals(Long.MIN_VALUE, $int$(BigInteger.valueOf(Long.MIN_VALUE)));
    assertEquals(BigInteger.ONE.shiftLeft(100), $int$(BigInteger.ONE.shiftLeft(100)));
  }

  @Test
  public void intRejectsNonFiniteFloats() {
    assertThrows(PyValueError.class, () -> $int$(Double.NaN));
    assertThrows(PyOverflowError.class, () -> $int$(Double.POSITIVE_INFINITY));
    assertThrows(PyOverflowError.class, () -> $int$(Double.NEGATIVE_INFINITY));
  }

  @Test
  public void intParsesDecimalStringsAndWhitespace() {
    assertEquals(42L, $int$("  +42  "));
    assertEquals(-42L, $int$("-42"));
    assertEquals(1_000L, $int$("1_000"));
    assertEquals(12L, $int$("١٢"));
  }

  @Test
  public void intParsesExplicitBasesAndPrefixes() {
    assertEquals(5L, $int$("101", 2L));
    assertEquals(255L, $int$("ff", 16L));
    assertEquals(255L, $int$("0x_FF", 0L));
    assertEquals(10L, $int$("10", false));  // False is base zero.
    assertEquals(35L, $int$("z", 36L));
  }

  @Test
  public void intRejectsInvalidLiteralsAndBases() {
    assertThrows(PyValueError.class, () -> $int$("1__0"));
    assertThrows(PyValueError.class, () -> $int$("010", 0L));
    assertThrows(PyValueError.class, () -> $int$("10", -1L));
    assertThrows(PyValueError.class, () -> $int$("10", 1L));
    assertThrows(PyValueError.class, () -> $int$("10", 37L));
  }

  @Test
  public void explicitBaseRequiresAStringEvenForBaseTen() {
    assertThrows(PyTypeError.class, () -> $int$(42L, 16L));
    // Regression case: the base-10 fast path currently bypasses the string check.
    assertThrows(PyTypeError.class, () -> $int$(42L, 10L));
  }

  @Test
  public void intRejectsMoreThanTwoArgumentsWithTheCorrectMaximum() {
    var error = assertThrows(PyTypeError.class, () -> $int$(1L, 2L, 3L));
    assertEquals("int() expected at most 2 arguments, got 3", error.getMessage());
  }
}
