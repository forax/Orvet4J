package com.github.forax.orvet.rt;

import com.github.forax.orvet.rt.error.PyValueError;

import java.math.BigInteger;

import static java.util.Objects.requireNonNull;

/// An immutable arithmetic progression. All Python operations are in RangeIntrinsics.
///
/// PyRangeSmall: the span |stop - start| fits in a long, so every element and every `index * step`
/// fits in a long. PyRangeBig: everything else.
/// Both can represent the same sequence (range(0, 2**70, 2**70) and range(0, 1) are both [0]).
public sealed interface PyRange {

  static PyRange of(long start, long stop, long step) {
    if (step == 0) {
      throw new IllegalArgumentException("step is zero");
    }
    // Compute the directed span without allowing overflow.
    long span;
    try {
      span = step > 0
          ? Math.subtractExact(stop, start)
          : Math.subtractExact(start, stop);
    } catch (ArithmeticException e) {
      // The span doesn't fit in a signed long: use the big representation.
      return of(
          BigInteger.valueOf(start),
          BigInteger.valueOf(stop),
          BigInteger.valueOf(step));
    }

    // Compute the length without overflowing.
    long length;
    if (span <= 0) {
      length = 0;
    } else if (step == Long.MIN_VALUE) {
      // abs(Long.MIN_VALUE) is 2**63, which doesn't fit in a long.
      // Since span <= Long.MAX_VALUE, there can only be one element.
      length = 1;
    } else {
      var absStep = Math.abs(step);
      length = (span - 1) / absStep + 1;
    }

    return new PyRangeSmall(start, stop, step, length);
  }

  static PyRange of(BigInteger start, BigInteger stop, BigInteger step) {
    requireNonNull(start);
    requireNonNull(stop);
    requireNonNull(step);
    if (step.signum() == 0) {
      throw new IllegalArgumentException("step is zero");
    }
    var span = step.signum() > 0 ? stop.subtract(start) : start.subtract(stop);
    var length = span.signum() <= 0
        ? BigInteger.ZERO
        : span.subtract(BigInteger.ONE).divide(step.abs()).add(BigInteger.ONE);
    if (start.bitLength() < 64 && stop.bitLength() < 64 && step.bitLength() < 64 && span.bitLength() < 64) {
      return new PyRangeSmall(start.longValue(), stop.longValue(), step.longValue(), length.longValue());
    }
    return new PyRangeBig(start, stop, step, length);
  }

  record PyRangeSmall(long start, long stop, long step, long length) implements PyRange {
    public PyRangeSmall {
      if (step == 0 || length < 0) {
        throw new IllegalArgumentException("invalid range: step=" + step + ", length=" + length);
      }
    }
  }

  record PyRangeBig(BigInteger start, BigInteger stop, BigInteger step, BigInteger length) implements PyRange {
    public PyRangeBig {
      requireNonNull(start);
      requireNonNull(stop);
      requireNonNull(step);
      requireNonNull(length);
      if (step.signum() == 0 || length.signum() < 0) {
        throw new IllegalArgumentException("invalid range: step=" + step + ", length=" + length);
      }
    }
  }
}