package com.github.forax.orvet.rt;

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
}