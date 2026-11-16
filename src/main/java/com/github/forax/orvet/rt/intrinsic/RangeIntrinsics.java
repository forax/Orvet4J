package com.github.forax.orvet.rt.intrinsic;

import com.github.forax.orvet.rt.PyRange;
import com.github.forax.orvet.rt.error.PyIndexError;

import java.math.BigInteger;

public final class RangeIntrinsics {
  private RangeIntrinsics() { throw new AssertionError(); }

  @Intrinsic
  public static long $size$(PyRange range) { return range.size(); }

  /// `range[index]` for an index already converted to a long (negative indexes count from the end).
  @Intrinsic
  public static Object $get$(PyRange range, long index) {
    if (range.isSmall()) {
      var length = range.smallLength();
      var i = index < 0 ? index + length : index;
      if (i < 0 || i >= length) {
        throw new PyIndexError("range object index out of range");
      }
      return range.smallStart() + i * range.smallStep();
    }
    return range.getBig(BigInteger.valueOf(index));
  }

  /// O(1) membership for ints. A non-number can never equal an int, so the answer is false.
  @Intrinsic
  public static boolean $in$(PyRange range, Object item) {
    switch (item) {
      case Long l -> { return range.containsLong(l); }
      case Boolean b -> { return range.containsLong(b ? 1L : 0L); }
      case BigInteger b -> { return range.containsBig(b); }
      case Double d -> { return range.containsDouble(d); }
      default -> {
        IntrinsicSupport.checkCanonical(item);
        return false;
      }
    }
  }
}