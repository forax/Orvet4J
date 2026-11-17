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

  static PyRange of(BigInteger start, BigInteger stop, BigInteger step) {
    requireNonNull(start);
    requireNonNull(stop);
    requireNonNull(step);
    if (step.signum() == 0) {
      throw new PyValueError("range() arg 3 must not be zero");
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