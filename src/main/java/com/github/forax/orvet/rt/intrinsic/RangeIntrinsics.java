package com.github.forax.orvet.rt.intrinsic;

import com.github.forax.orvet.rt.PyIterator;
import com.github.forax.orvet.rt.PyNone;
import com.github.forax.orvet.rt.PyRange;
import com.github.forax.orvet.rt.PyRange.PyRangeBig;
import com.github.forax.orvet.rt.PyRange.PyRangeSmall;
import com.github.forax.orvet.rt.PySlice;
import com.github.forax.orvet.rt.PyTuple;
import com.github.forax.orvet.rt.error.PyIndexError;
import com.github.forax.orvet.rt.error.PyOverflowError;
import com.github.forax.orvet.rt.error.PyTypeError;
import com.github.forax.orvet.rt.error.UnimplementedFeatureError;

import java.math.BigDecimal;
import java.math.BigInteger;

import static com.github.forax.orvet.rt.intrinsic.IntrinsicSupport.isInt;
import static com.github.forax.orvet.rt.intrinsic.IntrinsicSupport.normalize;
import static com.github.forax.orvet.rt.intrinsic.IntrinsicSupport.toBigInteger;
import static com.github.forax.orvet.rt.intrinsic.IntrinsicSupport.typeName;

public final class RangeIntrinsics {
  private RangeIntrinsics() { throw new AssertionError(); }

  // ------------------------------------------------------------------ construction (Python view)

  /// `range(start, stop, step)` for arbitrary Python values. Only ints and bools are accepted.
  public static PyRange newRange(Object start, Object stop, Object step) {
    return PyRange.of(asInt(start), asInt(stop), asInt(step));
  }

  private static BigInteger asInt(Object value) {
    if (!isInt(value)) {
      throw new PyTypeError("'" + typeName(value) + "' object cannot be interpreted as an integer");
    }
    return toBigInteger(value);
  }

  // ------------------------------------------------------------------ intrinsics

  /// len(range): an OverflowError if it does not fit a Py_ssize_t, as CPython.
  @Intrinsic
  public static long $size$(PyRange range) {
    switch (range) {
      case PyRangeSmall r -> { return r.length(); }
      case PyRangeBig r -> {
        if (r.length().bitLength() < 64) {
          return r.length().longValue();
        }
        throw new PyOverflowError("Python int too large to convert to C ssize_t");
      }
    }
  }

  @Intrinsic
  public static boolean $isEmpty$(PyRange range) {
    return switch (range) {
      case PyRangeSmall r -> r.length() == 0;
      case PyRangeBig r -> r.length().signum() == 0;
    };
  }

  /// `range[index]` for an index already converted to a long; negative indexes count from the end.
  @Intrinsic
  public static Object $get$(PyRange range, long index) {
    switch (range) {
      case PyRangeSmall r -> { return smallGet(r, index); }
      case PyRangeBig r -> { return bigGet(r, BigInteger.valueOf(index)); }
    }
  }

  /// `item in range`. A non-number can never equal an int, so the answer is false.
  @Intrinsic
  public static boolean $in$(PyRange range, Object item) {
    switch (item) {
      case Long l -> { return containsLong(range, l); }
      case Boolean b -> { return containsLong(range, b ? 1L : 0L); }
      case BigInteger b -> { return containsBig(range, b); }
      case Double d -> { return containsDouble(range, d); }
      default -> {
        IntrinsicSupport.checkCanonical(item);
        return false;
      }
    }
  }

  /// `range[slice]` is a range. Done in BigInteger, `start + r.start() * step` can overflow a long.
  @Intrinsic
  public static PyRange $slice$(PyRange range, PySlice slice) {
    var length = bigLength(range);
    if (length.bitLength() > 63) {
      throw new UnimplementedFeatureError("slicing a range with more than 2**63-1 elements");
    }
    var r = slice.resolve(length.longValue());
    var start = bigStart(range);
    var step = bigStep(range);
    return PyRange.of(
        start.add(BigInteger.valueOf(r.start()).multiply(step)),
        start.add(BigInteger.valueOf(r.stop()).multiply(step)),
        step.multiply(BigInteger.valueOf(r.step())));
  }

  // ------------------------------------------------------------------ operations (not intrinsics)

  /// `range[index]` for an index of any size.
  public static Object getBig(PyRange range, BigInteger index) {
    switch (range) {
      case PyRangeSmall r -> {
        if (index.bitLength() > 63) {
          throw outOfRange();
        }
        return smallGet(r, index.longValue());
      }
      case PyRangeBig r -> { return bigGet(r, index); }
    }
  }

  public static PyIterator iterator(PyRange range) {
    return switch (range) {
      case PyRangeSmall r -> new PyIterator.SmallRangeIterator(r);
      case PyRangeBig r -> new PyIterator.BigRangeIterator(r);
    };
  }

  /// Equality compares the sequences, not the arguments: range(0) == range(1, 1), range(0, 3, 5) == range(0, 1).
  public static boolean sameSequence(PyRange left, PyRange right) {
    if (left == right) {
      return true;
    }
    if (left instanceof PyRangeSmall l && right instanceof PyRangeSmall r) {
      return l.length() == r.length()
          && (l.length() == 0 || l.start() == r.start() && (l.length() == 1 || l.step() == r.step()));
    }
    var length = bigLength(left);
    if (!length.equals(bigLength(right))) {
      return false;
    }
    return length.signum() == 0
        || bigStart(left).equals(bigStart(right))
        && (length.equals(BigInteger.ONE) || bigStep(left).equals(bigStep(right)));
  }

  /// CPython hashes (len, start, step), dropping what carries no information.
  public static PyTuple canonicalTuple(PyRange range) {
    var length = bigLength(range);
    if (length.signum() == 0) {
      return TupleIntrinsics.fromArray(0L, PyNone.INSTANCE, PyNone.INSTANCE);
    }
    if (length.equals(BigInteger.ONE)) {
      return TupleIntrinsics.fromArray(1L, normalize(bigStart(range)), PyNone.INSTANCE);
    }
    return TupleIntrinsics.fromArray(
        normalize(length), normalize(bigStart(range)), normalize(bigStep(range)));
  }

  // ------------------------------------------------------------------ helpers

  private static Object smallGet(PyRangeSmall r, long index) {
    var n = index < 0 ? index + r.length() : index;   // no overflow: length >= 0
    if (n < 0 || n >= r.length()) {
      throw outOfRange();
    }
    return r.start() + n * r.step();                  // |n * step| <= span: fits
  }

  private static Object bigGet(PyRangeBig r, BigInteger index) {
    var i = index.signum() < 0 ? index.add(r.length()) : index;
    if (i.signum() < 0 || i.compareTo(r.length()) >= 0) {
      throw outOfRange();
    }
    return normalize(r.start().add(i.multiply(r.step())));
  }

  private static boolean containsLong(PyRange range, long x) {
    return switch (range) {
      case PyRangeSmall r -> smallContains(r, x);
      case PyRangeBig r -> bigContains(r, BigInteger.valueOf(x));
    };
  }

  private static boolean containsBig(PyRange range, BigInteger x) {
    return switch (range) {
      case PyRangeSmall r -> x.bitLength() < 64 && smallContains(r, x.longValue());
      case PyRangeBig r -> bigContains(r, x);
    };
  }

  /// `1.0 in range(3)` is True: an integral float is tested as the int it equals.
  private static boolean containsDouble(PyRange range, double d) {
    if (!Double.isFinite(d) || d != Math.rint(d)) {
      return false;
    }
    return containsBig(range, new BigDecimal(d).toBigIntegerExact());
  }

  private static boolean smallContains(PyRangeSmall r, long x) {
    if (r.length() == 0) {
      return false;
    }
    var last = r.start() + (r.length() - 1) * r.step();   // fits: see PyRange
    if (x < Math.min(r.start(), last) || x > Math.max(r.start(), last)) {
      return false;
    }
    return (x - r.start()) % r.step() == 0;               // x and start are both between start and last
  }

  private static boolean bigContains(PyRangeBig r, BigInteger x) {
    if (r.length().signum() == 0) {
      return false;
    }
    var past = r.step().signum() > 0
        ? x.compareTo(r.stop()) >= 0 || x.compareTo(r.start()) < 0
        : x.compareTo(r.stop()) <= 0 || x.compareTo(r.start()) > 0;
    return !past && x.subtract(r.start()).remainder(r.step()).signum() == 0;
  }

  static BigInteger bigStart(PyRange range) {
    return switch (range) {
      case PyRangeSmall r -> BigInteger.valueOf(r.start());
      case PyRangeBig r -> r.start();
    };
  }

  static BigInteger bigStop(PyRange range) {
    return switch (range) {
      case PyRangeSmall r -> BigInteger.valueOf(r.stop());
      case PyRangeBig r -> r.stop();
    };
  }

  static BigInteger bigStep(PyRange range) {
    return switch (range) {
      case PyRangeSmall r -> BigInteger.valueOf(r.step());
      case PyRangeBig r -> r.step();
    };
  }

  private static BigInteger bigLength(PyRange range) {
    return switch (range) {
      case PyRangeSmall r -> BigInteger.valueOf(r.length());
      case PyRangeBig r -> r.length();
    };
  }

  private static PyIndexError outOfRange() {
    return new PyIndexError("range object index out of range");
  }
}