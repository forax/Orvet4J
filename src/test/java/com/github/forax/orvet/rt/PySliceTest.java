package com.github.forax.orvet.rt;

import com.github.forax.orvet.rt.error.PyTypeError;
import com.github.forax.orvet.rt.error.PyValueError;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

public final class PySliceTest {
  @Test
  public void positiveStepClampsBounds() {
    assertEquals(
        new PySlice.Resolved(1, 6, 2, 3),
        PySlice.of(1L, 100L, 2L).resolve(6));
  }

  @Test
  public void negativeStepDefaultsToTheLastElement() {
    assertEquals(
        new PySlice.Resolved(5, -1, -1, 6),
        PySlice.of(PyNone.INSTANCE, PyNone.INSTANCE, -1L).resolve(6));
  }

  @Test
  public void arbitrarySizeBoundsAreClamped() {
    var huge = BigInteger.TEN.pow(100);

    assertEquals(
        new PySlice.Resolved(0, 6, 1, 6),
        PySlice.of(huge.negate(), huge, 1L).resolve(6));
  }

  @Test
  public void minimumLongStepDoesNotOverflow() {
    assertEquals(
        new PySlice.Resolved(5, -1, -Long.MAX_VALUE, 1),
        PySlice.of(PyNone.INSTANCE, PyNone.INSTANCE, Long.MIN_VALUE)
            .resolve(6));
  }

  @Test
  public void zeroStepIsRejected() {
    assertThrows(PyValueError.class,
        () -> PySlice.of(PyNone.INSTANCE, PyNone.INSTANCE, 0L).resolve(6));
  }

  @Test
  public void nonIntegerIndicesAreRejected() {
    assertThrows(PyTypeError.class,
        () -> PySlice.of(1.5, 5L, 1L).resolve(6));
  }
}