package com.github.forax.orvet.rt.intrinsic;

import com.github.forax.orvet.rt.PyDict;
import com.github.forax.orvet.rt.PyList;
import com.github.forax.orvet.rt.PyRange;
import com.github.forax.orvet.rt.PyTuple;
import com.github.forax.orvet.rt.error.PyTypeError;

import static com.github.forax.orvet.rt.intrinsic.IntrinsicSupport.typeName;

/// Implements {@code item in container}. Operands keep their source order.
public final class ContainmentIntrinsics {
  private ContainmentIntrinsics() { throw new AssertionError(); }

  @Intrinsic
  public static boolean $in$(Object item, Object container) {
    switch (container) {
      case String s -> {
        switch (item) {
          case String t -> { return s.contains(t); }
          default -> throw new PyTypeError(
              "'in <string>' requires string as left operand, not " + typeName(item));
        }
      }
      case PyTuple tuple -> { return TupleIntrinsics.$in$(tuple, item); }
      case PyList<?> list -> { return ListIntrinsics.$in$(list, item); }
      case PyDict<?,?> dict -> { return DictIntrinsics.$in$(dict, item); }
      case PyRange range -> { return RangeIntrinsics.$in$(range, item); }
      default -> throw new PyTypeError("argument of type '" + typeName(container) + "' is not iterable");
    }
  }
}