package com.github.forax.orvet.rt;

import com.github.forax.orvet.rt.PyRange.PyRangeBig;
import com.github.forax.orvet.rt.PyRange.PyRangeSmall;
import com.github.forax.orvet.rt.intrinsic.RangeIntrinsics;
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
}