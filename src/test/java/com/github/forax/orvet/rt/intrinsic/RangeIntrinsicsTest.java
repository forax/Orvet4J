package com.github.forax.orvet.rt.intrinsic;

import com.github.forax.orvet.rt.PyNone;
import com.github.forax.orvet.rt.PyRange;
import com.github.forax.orvet.rt.PyRange.PyRangeBig;
import com.github.forax.orvet.rt.PyRange.PyRangeSmall;
import com.github.forax.orvet.rt.PySlice;
import com.github.forax.orvet.rt.error.PyIndexError;
import com.github.forax.orvet.rt.error.PyOverflowError;
import com.github.forax.orvet.rt.error.PyTypeError;
import com.github.forax.orvet.rt.error.PyValueError;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class RangeIntrinsicsTest {
  private static PyRange range(long start, long stop, long step) {
    return RangeIntrinsics.newRange(start, stop, step);
  }

  @Test
  public void theShapeDependsOnTheSpan() {
    assertInstanceOf(PyRangeSmall.class, range(0, 10, 3));
    assertInstanceOf(PyRangeSmall.class, PyRange.of(BigInteger.ZERO, BigInteger.TEN, BigInteger.ONE));
    assertInstanceOf(PyRangeBig.class, range(Long.MIN_VALUE, Long.MAX_VALUE, 1));  // span is 2**64 - 1
    assertInstanceOf(PyRangeBig.class, RangeIntrinsics.newRange(0L, BigInteger.TWO.pow(70), 1L));
  }

  @Test
  public void pythonViewValidatesItsArguments() {
    assertThrows(PyValueError.class, () -> range(0, 1, 0));
    assertThrows(PyTypeError.class, () -> RangeIntrinsics.newRange(0L, 1.5, 1L));
  }

  @Test
  public void sizeAndGet() {
    var r = range(0, 10, 3);   // 0 3 6 9
    assertEquals(4L, RangeIntrinsics.$size$(r));
    assertEquals(9L, RangeIntrinsics.$get$(r, -1));
    assertThrows(PyIndexError.class, () -> RangeIntrinsics.$get$(r, 4));
    assertThrows(PyOverflowError.class,
        () -> RangeIntrinsics.$size$(RangeIntrinsics.newRange(0L, BigInteger.TWO.pow(70), 1L)));
  }

  @Test
  public void containment() {
    var r = range(0, 10, 3);
    assertTrue(RangeIntrinsics.$in$(r, 6L));
    assertTrue(RangeIntrinsics.$in$(r, 6.0));
    assertFalse(RangeIntrinsics.$in$(r, 7L));
    assertFalse(RangeIntrinsics.$in$(r, "a"));
  }

  @Test
  public void equalityComparesTheSequenceAcrossShapes() {
    assertTrue(RangeIntrinsics.sameSequence(range(0, 0, 1), range(5, 5, 2)));
    assertTrue(RangeIntrinsics.sameSequence(range(0, 3, 5), range(0, 1, 1)));
    var big = RangeIntrinsics.newRange(0L, BigInteger.TWO.pow(70), BigInteger.TWO.pow(70));
    assertInstanceOf(PyRangeBig.class, big);
    assertTrue(RangeIntrinsics.sameSequence(big, range(0, 1, 1)));       // [0] and [0]
    assertEquals(RangeIntrinsics.canonicalTuple(big), RangeIntrinsics.canonicalTuple(range(0, 1, 1)));
    assertFalse(RangeIntrinsics.sameSequence(range(0, 3, 1), range(0, 3, 2)));
  }

  @Test
  public void slicing() {
    var sliced = RangeIntrinsics.$slice$(range(0, 10, 1), PySlice.of(2L, 8L, 2L));
    assertTrue(RangeIntrinsics.sameSequence(sliced, range(2, 8, 2)));

    var reversed = RangeIntrinsics.$slice$(range(0, 10, 1),
        PySlice.of(PyNone.INSTANCE, PyNone.INSTANCE, -1L));
    assertEquals(10L, RangeIntrinsics.$size$(reversed));
    assertEquals(9L, RangeIntrinsics.$get$(reversed, 0));
  }

  @Test
  public void bigRangeGetAndContainment() {
    var range = PyRange.of(
        Long.MIN_VALUE,
        Long.MAX_VALUE,
        Long.MAX_VALUE);

    assertInstanceOf(PyRangeBig.class, range);

    assertEquals(Long.MIN_VALUE, RangeIntrinsics.$get$(range, 0));
    assertEquals(-1L, RangeIntrinsics.$get$(range, 1));
    assertEquals(Long.MAX_VALUE - 1, RangeIntrinsics.$get$(range, 2));

    assertTrue(RangeIntrinsics.$in$(range, -1L));
    assertFalse(RangeIntrinsics.$in$(range, 0L));
  }
}