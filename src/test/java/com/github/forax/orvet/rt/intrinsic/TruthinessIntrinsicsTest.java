package com.github.forax.orvet.rt.intrinsic;

import com.github.forax.orvet.rt.PyDict;
import com.github.forax.orvet.rt.PyList;
import com.github.forax.orvet.rt.PyNone;
import com.github.forax.orvet.rt.PyRange;
import com.github.forax.orvet.rt.PySlice;
import com.github.forax.orvet.rt.PyType;
import com.github.forax.orvet.rt.RT;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class TruthinessIntrinsicsTest {
  private static boolean truthy(Object value) {
    return TruthinessIntrinsics.$truthy$(value);
  }

  @Test
  public void falsyValues() {
    for (var value : new Object[]{
        false, 0L, BigInteger.ZERO, 0.0, -0.0, "", PyNone.INSTANCE,
        new PyList<Object>(), TupleIntrinsics.empty(), new PyDict<Object, Object>()}) {
      assertFalse(truthy(value), String.valueOf(value));
      assertTrue(TruthinessIntrinsics.$not$(value), String.valueOf(value));
    }
  }

  @Test
  public void truthyValues() {
    var dict = new PyDict<Object, Object>();
    dict.put("k", PyNone.INSTANCE);
    for (var value : new Object[]{
        true, 1L, -1L, Long.MIN_VALUE, BigInteger.ONE.shiftLeft(100), BigInteger.TEN.negate(),
        0.5, -0.5, Double.MIN_VALUE, Double.NaN, Double.POSITIVE_INFINITY,
        " ", "0", "False", "None",
        new PyList<Object>(0L), new PyList<Object>(PyNone.INSTANCE),
        TupleIntrinsics.fromArray(0L), TupleIntrinsics.fromArray(PyNone.INSTANCE),
        dict,
        PyType.INT.function()}) {
      assertTrue(truthy(value), String.valueOf(value));
      assertFalse(TruthinessIntrinsics.$not$(value), String.valueOf(value));
    }
  }

  @Test
  public void nonCanonicalObjectsAreRejected() {
    assertThrows(NotCanonicalException.class, () -> truthy(new Object()));
    assertThrows(NotCanonicalException.class, () -> truthy(1));      // Integer is not Long
    assertThrows(NotCanonicalException.class, () -> truthy('c'));
    assertThrows(NullPointerException.class, () -> truthy(null));
  }

  @Test
  public void rangesAreTruthyWhenNotEmpty() {
    assertFalse(truthy(PyRange.of(0L, 0L, 1L)));
    assertFalse(truthy(PyRange.of(5L, 0L, 1L)));
    assertTrue(truthy(PyRange.of(0L, 1L, 1L)));
    assertTrue(truthy(PyRange.of(BigInteger.valueOf(0L), BigInteger.ONE.shiftLeft(70), BigInteger.valueOf(1L))));
  }

  @Test
  public void slicesAreAlwaysTruthy() {
    assertTrue(truthy(PySlice.of(PyNone.INSTANCE, PyNone.INSTANCE, PyNone.INSTANCE)));
  }
}