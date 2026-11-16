package com.github.forax.orvet.rt;

import com.github.forax.orvet.rt.error.PyTypeError;
import com.github.forax.orvet.rt.error.PyValueError;

import java.math.BigInteger;
import java.util.Objects;

public final class PySlice {
  /// Result of PySlice_AdjustIndices: `start`/`stop` are clamped as CPython does, `count` is the length of the selection.
  public record Resolved(long start, long stop, long step, long count) {}

  private final Object start, stop, step;

  public PySlice(Object start, Object stop, Object step) {
    this.start = Objects.requireNonNull(start);
    this.stop = Objects.requireNonNull(stop);
    this.step = Objects.requireNonNull(step);
  }

  public Object start() { return start; }
  public Object stop() { return stop; }
  public Object step() { return step; }

  /// Equality, hashing and ordering are those of the (start, stop, step) tuple.
  public PyTuple asTuple() { return new PyTuple.PyTuple3(start, stop, step); }

  public Resolved resolve(long length) {
    var stepValue = step == PyNone.INSTANCE ? 1L : index(step);
    if (stepValue == 0) {
      throw new PyValueError("slice step cannot be zero");
    }
    if (stepValue == Long.MIN_VALUE) {
      stepValue = -Long.MAX_VALUE;                       // as CPython: -step must not overflow
    }
    var lower = stepValue < 0 ? -1L : 0L;
    var upper = stepValue < 0 ? length - 1 : length;
    var from = bound(start, stepValue < 0 ? upper : lower, length, lower, upper);
    var to = bound(stop, stepValue < 0 ? lower : upper, length, lower, upper);
    long count;
    if (stepValue < 0) {
      count = from > to ? (from - to - 1) / -stepValue + 1 : 0;
    } else {
      count = from < to ? (to - from - 1) / stepValue + 1 : 0;
    }
    return new Resolved(from, to, stepValue, count);
  }

  private static long bound(Object value, long defaultValue, long length, long lower, long upper) {
    if (value == PyNone.INSTANCE) {
      return defaultValue;
    }
    var v = index(value);
    if (v < 0) {
      v += length;                                       // v < 0 <= length: no overflow
      return Math.max(v, lower);
    }
    return Math.min(v, upper);
  }

  /// A BigInteger is clamped: any value that large is beyond every length anyway.
  private static long index(Object value) {
    return switch (value) {
      case Long l -> l;
      case Boolean b -> b ? 1L : 0L;
      case BigInteger big -> big.signum() < 0 ? Long.MIN_VALUE : Long.MAX_VALUE;
      default -> throw new PyTypeError("slice indices must be integers or None or have an __index__ method");
    };
  }
}