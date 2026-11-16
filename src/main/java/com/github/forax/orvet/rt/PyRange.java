package com.github.forax.orvet.rt;

import com.github.forax.orvet.rt.error.PyIndexError;
import com.github.forax.orvet.rt.error.PyOverflowError;
import com.github.forax.orvet.rt.error.PyValueError;
import com.github.forax.orvet.rt.error.UnimplementedFeatureError;
import com.github.forax.orvet.rt.intrinsic.IntrinsicSupport;
import com.github.forax.orvet.rt.intrinsic.TupleIntrinsics;

import java.math.BigDecimal;
import java.math.BigInteger;

/// An immutable arithmetic progression. start/stop/step are canonical ints (Long, or BigInteger if they don't fit).
/// "Small" ranges, where the span `stop - start` fits in a long and step != Long.MIN_VALUE, use long arithmetic only:
/// then every element and every `index * step` fits in a long. The others fall back to BigInteger.
public final class PyRange {
  private final Object start, stop, step;
  private final boolean small;
  private final long smallStart, smallStep, smallLength;   // valid iff small
  private final BigInteger bigLength;                      // valid iff !small

  public static PyRange of(Object start, Object stop, Object step) {
    if (step instanceof Long l && l == 0L) {
      throw new PyValueError("range() arg 3 must not be zero");
    }
    return new PyRange(start, stop, step);
  }

  private PyRange(Object start, Object stop, Object step) {
    this.start = start;
    this.stop = stop;
    this.step = step;
    var length = -1L;
    if (start instanceof Long s && stop instanceof Long e && step instanceof Long t && t != Long.MIN_VALUE) {
      length = computeSmallLength(s, e, t);
    }
    if (length >= 0) {
      this.small = true;
      this.smallStart = (Long) start;
      this.smallStep = (Long) step;
      this.smallLength = length;
      this.bigLength = null;
    } else {
      this.small = false;
      this.smallStart = 0;
      this.smallStep = 0;
      this.smallLength = 0;
      this.bigLength = computeBigLength(big(start), big(stop), big(step));
    }
  }

  // -- accessors
  public Object start() { return start; }
  public Object stop() { return stop; }
  public Object step() { return step; }
  public boolean isSmall() { return small; }
  public long smallStart() { return smallStart; }
  public long smallStep() { return smallStep; }
  public long smallLength() { return smallLength; }
  public boolean isEmpty() { return small ? smallLength == 0 : bigLength.signum() == 0; }
  public BigInteger length() { return small ? BigInteger.valueOf(smallLength) : bigLength; }

  /// len(range): OverflowError if it does not fit a Py_ssize_t, as CPython.
  public long size() {
    if (small) {
      return smallLength;
    }
    if (bigLength.bitLength() < 64) {
      return bigLength.longValue();
    }
    throw new PyOverflowError("Python int too large to convert to C ssize_t");
  }

  // -- elements
  /// Python index in [-len, len), any size.
  public Object getBig(BigInteger index) {
    var len = length();
    var i = index.signum() < 0 ? index.add(len) : index;
    if (i.signum() < 0 || i.compareTo(len) >= 0) {
      throw new PyIndexError("range object index out of range");
    }
    return IntrinsicSupport.normalize(big(start).add(i.multiply(big(step))));
  }

  public boolean containsLong(long x) {
    if (!small) {
      return containsBig(BigInteger.valueOf(x));
    }
    if (smallLength == 0) {
      return false;
    }
    var last = smallStart + (smallLength - 1) * smallStep;            // fits: see class comment
    if (x < Math.min(smallStart, last) || x > Math.max(smallStart, last)) {
      return false;
    }
    return (x - smallStart) % smallStep == 0;
  }

  public boolean containsBig(BigInteger x) {
    if (isEmpty()) {
      return false;
    }
    var s = big(start);
    var t = big(step);
    var past = t.signum() > 0 ? x.compareTo(big(stop)) >= 0 || x.compareTo(s) < 0
                              : x.compareTo(big(stop)) <= 0 || x.compareTo(s) > 0;
    return !past && x.subtract(s).remainder(t).signum() == 0;
  }

  /// `1.0 in range(3)` is True: an integral float is tested as the int it equals.
  public boolean containsDouble(double d) {
    if (Double.isNaN(d) || Double.isInfinite(d) || d != Math.rint(d)) {
      return false;
    }
    return containsBig(new BigDecimal(d).toBigIntegerExact());
  }

  // -- Python semantics
  /// Equality compares the sequences, not the arguments: range(0) == range(1, 1), range(0, 3, 5) == range(0, 1).
  public boolean sameSequence(PyRange o) {
    if (this == o) {
      return true;
    }
    if (small && o.small) {
      return smallLength == o.smallLength
          && (smallLength == 0 || smallStart == o.smallStart && (smallLength == 1 || smallStep == o.smallStep));
    }
    var len = length();
    if (!len.equals(o.length())) {
      return false;
    }
    return len.signum() == 0
        || big(start).equals(big(o.start)) && (len.equals(BigInteger.ONE) || big(step).equals(big(o.step)));
  }

  /// CPython hashes (len, start, step) with start/step normalized away when they carry no information.
  public PyTuple canonicalTuple() {
    if (isEmpty()) {
      return TupleIntrinsics.fromArray(0L, PyNone.INSTANCE, PyNone.INSTANCE);
    }
    var len = IntrinsicSupport.normalize(length());
    if (len.equals(1L)) {
      return TupleIntrinsics.fromArray(1L, start, PyNone.INSTANCE);
    }
    return TupleIntrinsics.fromArray(len, start, step);
  }

  /// range[slice] is a range. Only ranges of at most 2**63-1 elements are sliceable for now.
  public PyRange slice(PySlice slice) {
    if (!small) {
      throw new UnimplementedFeatureError("slicing a range with more than 2**63-1 elements");
    }
    var r = slice.resolve(smallLength);
    var s0 = BigInteger.valueOf(smallStart);
    var st = BigInteger.valueOf(smallStep);
    var newStart = s0.add(BigInteger.valueOf(r.start()).multiply(st));
    var newStop = s0.add(BigInteger.valueOf(r.stop()).multiply(st));
    var newStep = st.multiply(BigInteger.valueOf(r.step()));
    return PyRange.of(IntrinsicSupport.normalize(newStart),
        IntrinsicSupport.normalize(newStop),
        IntrinsicSupport.normalize(newStep));
  }

  // -- helpers
  static BigInteger big(Object canonicalInt) {
    return canonicalInt instanceof Long l ? BigInteger.valueOf(l) : (BigInteger) canonicalInt;
  }

  /// -1 when the span overflows a long (the caller then uses the BigInteger path).
  private static long computeSmallLength(long start, long stop, long step) {
    long span;
    try {
      span = step > 0 ? Math.subtractExact(stop, start) : Math.subtractExact(start, stop);
    } catch (ArithmeticException _) {
      return -1;
    }
    if (span <= 0) {
      return 0;
    }
    return (span - 1) / (step > 0 ? step : -step) + 1;
  }

  private static BigInteger computeBigLength(BigInteger start, BigInteger stop, BigInteger step) {
    var span = step.signum() > 0 ? stop.subtract(start) : start.subtract(stop);
    return span.signum() <= 0 ? BigInteger.ZERO : span.subtract(BigInteger.ONE).divide(step.abs()).add(BigInteger.ONE);
  }
}