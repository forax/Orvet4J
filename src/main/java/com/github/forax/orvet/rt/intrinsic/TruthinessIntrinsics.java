package com.github.forax.orvet.rt.intrinsic;

import com.github.forax.orvet.rt.PyDict;
import com.github.forax.orvet.rt.PyFunction;
import com.github.forax.orvet.rt.PyList;
import com.github.forax.orvet.rt.PyNone;
import com.github.forax.orvet.rt.PyRange;
import com.github.forax.orvet.rt.PySlice;
import com.github.forax.orvet.rt.PyTuple;

import java.math.BigInteger;

/// Python truthiness.
public final class TruthinessIntrinsics {
  private TruthinessIntrinsics() { throw new AssertionError(); }

  @Intrinsic
  public static boolean $truthy$(Object value) {
    switch (value) {
      case Boolean b -> { return b; }
      case Long l -> { return l != 0L; }
      case Double d -> { return d != 0.0; }  // NaN is truthy.
      case String s -> { return !s.isEmpty(); }
      case PyNone _ -> { return false; }
      case BigInteger b -> { return b.signum() != 0; }
      case PyTuple tuple -> { return TupleIntrinsics.$size$(tuple) != 0L; }
      case PyList<?> list -> { return !list.isEmpty(); }
      case PyDict<?,?> dict -> { return !dict.isEmpty(); }
      case PyFunction _ -> { return true; }
      case PyRange range -> { return !range.isEmpty(); }
      case PySlice _ -> { return true; }
      default -> throw notCanonical(value);
    }
  }

  @Intrinsic
  public static boolean $not$(Object value) {
    return !$truthy$(value);
  }

  private static NotCanonicalException notCanonical(Object value) {
    return new NotCanonicalException("no truthiness for a non canonical value", value);
  }
}