package com.github.forax.orvet.rt;

import com.github.forax.orvet.rt.PyRange.PyRangeBig;
import com.github.forax.orvet.rt.PyRange.PyRangeSmall;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

public final class PyRangeTest {

  @Test
  public void longFactoryMatchesBigIntegerFactoryAtBoundaries() {
    var values = new long[] {
        Long.MIN_VALUE,
        Long.MIN_VALUE + 1,
        -1,
        0,
        1,
        Long.MAX_VALUE - 1,
        Long.MAX_VALUE
    };

    var steps = new long[] {
        Long.MIN_VALUE,
        -1,
        1,
        Long.MAX_VALUE
    };

    for (var start : values) {
      for (var stop : values) {
        for (var step : steps) {
          var expected = PyRange.of(
              BigInteger.valueOf(start),
              BigInteger.valueOf(stop),
              BigInteger.valueOf(step));

          var actual = PyRange.of(start, stop, step);

          assertEquals(expected, actual,
              "range(" + start + ", " + stop + ", " + step + ")");
        }
      }
    }
  }

  @Test
  public void zeroStepIsRejected() {
    assertThrows(IllegalArgumentException.class,
        () -> PyRange.of(1, 10, 0));
    assertThrows(IllegalArgumentException.class,
        () -> PyRange.of(BigInteger.valueOf(1), BigInteger.valueOf(10), BigInteger.valueOf(0)));
  }

  @Test
  public void spanBoundary() {
    // Span = Long.MAX_VALUE: small.
    assertInstanceOf(PyRangeSmall.class,
        PyRange.of(Long.MIN_VALUE, -1, 1));

    // Span = Long.MAX_VALUE + 1: big.
    assertInstanceOf(PyRangeBig.class,
        PyRange.of(Long.MIN_VALUE, 0, 1));

    // Span = Long.MIN_VALUE: fits in a signed long.
    // This is an empty range.
    assertInstanceOf(PyRangeSmall.class,
        PyRange.of(0, Long.MIN_VALUE, 1));
  }

  @Test
  public void minimumStep() {
    var range = PyRange.of(0, -1, Long.MIN_VALUE);

    assertEquals(
        new PyRangeSmall(0, -1, Long.MIN_VALUE, 1),
        range);
  }

  @Test
  public void lengthCalculation() {
    assertEquals(
        new PyRangeSmall(0, 10, 3, 4),
        PyRange.of(0, 10, 3));

    assertEquals(
        new PyRangeSmall(10, 0, -3, 4),
        PyRange.of(10, 0, -3));

    assertEquals(
        new PyRangeSmall(0, 10, 10, 1),
        PyRange.of(0, 10, 10));

    assertEquals(
        new PyRangeSmall(0, 10, 11, 1),
        PyRange.of(0, 10, 11));

    assertEquals(
        new PyRangeSmall(0, 10, -1, 0),
        PyRange.of(0, 10, -1));

    assertEquals(
        new PyRangeSmall(10, 0, 1, 0),
        PyRange.of(10, 0, 1));
  }

  @Test
  public void negativeSpanOverflow() {
    var range = PyRange.of(Long.MAX_VALUE, Long.MIN_VALUE, -1);

    assertInstanceOf(PyRangeBig.class, range);
    assertEquals(
        BigInteger.ONE.shiftLeft(64).subtract(BigInteger.ONE),
        ((PyRangeBig) range).length());
  }

  @Test
  public void bigIntegerRangeOutsideLongBoundaries() {
    var start = BigInteger.ONE.shiftLeft(70);
    var stop = start.add(BigInteger.TEN);
    var step = BigInteger.valueOf(3);

    var range = PyRange.of(start, stop, step);

    assertEquals(
        new PyRangeBig(start, stop, step, BigInteger.valueOf(4)),
        range);
  }

  @Test
  public void minimumStepWithLargeSpan() {
    var range = PyRange.of(
        Long.MAX_VALUE,
        Long.MIN_VALUE,
        Long.MIN_VALUE);

    assertInstanceOf(PyRangeBig.class, range);
    assertEquals(BigInteger.TWO, ((PyRangeBig) range).length());
  }

  @Test
  public void rangeRecordsRejectInvalidValues() {
    assertThrows(IllegalArgumentException.class,
        () -> new PyRangeSmall(0, 1, 0, 0));

    assertThrows(IllegalArgumentException.class,
        () -> new PyRangeSmall(0, 1, 1, -1));

    assertThrows(IllegalArgumentException.class,
        () -> new PyRangeBig(
            BigInteger.ZERO, BigInteger.ONE,
            BigInteger.ZERO, BigInteger.ZERO));

    assertThrows(IllegalArgumentException.class,
        () -> new PyRangeBig(
            BigInteger.ZERO, BigInteger.ONE,
            BigInteger.ONE, BigInteger.valueOf(-1)));
  }

  @Test
  public void bigIntegerRangeWithStopOutsideLongBoundary() {
    var start = BigInteger.ZERO;
    var stop = BigInteger.ONE.shiftLeft(63);
    var step = BigInteger.ONE;

    var range = PyRange.of(start, stop, step);

    assertEquals(
        new PyRangeBig(start, stop, step, stop),
        range);
  }

  @Test
  public void bigIntegerRangeWithStepOutsideLongBoundary() {
    var start = BigInteger.ZERO;
    var stop = BigInteger.TEN;
    var step = BigInteger.ONE.shiftLeft(63);

    var range = PyRange.of(start, stop, step);

    assertEquals(
        new PyRangeBig(start, stop, step, BigInteger.ONE),
        range);
  }
}