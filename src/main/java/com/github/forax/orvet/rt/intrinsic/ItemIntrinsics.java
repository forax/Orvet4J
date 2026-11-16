package com.github.forax.orvet.rt.intrinsic;

import com.github.forax.orvet.rt.PyDict;
import com.github.forax.orvet.rt.PyList;
import com.github.forax.orvet.rt.PyTuple;
import com.github.forax.orvet.rt.error.PyIndexError;
import com.github.forax.orvet.rt.error.PyTypeError;
import com.github.forax.orvet.rt.error.UnimplementedFeatureError;

import java.math.BigInteger;

import static com.github.forax.orvet.rt.intrinsic.IntrinsicSupport.typeName;

public final class ItemIntrinsics {
  private ItemIntrinsics() {
    throw new AssertionError();
  }

  @Intrinsic
  public static Object $len$(Object value) {
    return switch (value) {
      case String string -> (long) string.codePointCount(0, string.length());
      case PyTuple tuple -> TupleIntrinsics.$size$(tuple);
      case PyList<?> list -> (long) list.size();
      case PyDict<?,?> dictionary -> DictIntrinsics.$size$(dictionary);
      default -> throw new PyTypeError("object of type '" + typeName(value) + "' has no len()");
    };
  }

  @Intrinsic
  public static Object $getItem$(Object container, Object index) {
    Object result;
    switch (container) {
      case PyList<?> l  -> result = ListIntrinsics.$get$(l, $toIndex$(index, "list"));
      case PyDict<?,?> d -> result = DictIntrinsics.$get$(d, index);
      case PyTuple t    -> result = TupleIntrinsics.$get$(t, $toIndex$(index, "tuple"));
      case String _  -> throw new UnimplementedFeatureError("str indexing not yet implemented");
      default -> throw new PyTypeError("'" + typeName(container) + "' object is not subscriptable");
    }
    return result;
  }

  @Intrinsic
  @SuppressWarnings("unchecked")
  public static Object $setItem$(Object container, Object index, Object value) {
    Object result;
    switch (container) {
      case PyList<?> list  -> result = ListIntrinsics.$set$((PyList<Object>) list, $toIndex$(index, "list"), value);
      case PyDict<?,?> dict -> result = DictIntrinsics.$set$((PyDict<Object, Object>) dict, index, value);
      case PyTuple _ -> throw new PyTypeError("'tuple' object does not support item assignment");
      case String _ -> throw new PyTypeError("'str' object does not support item assignment");
      default -> throw new PyTypeError("'" + typeName(container) + "' object is not subscriptable");
    }
    return result;
  }

  @Intrinsic
  @SuppressWarnings("unchecked")
  public static Object $deleteItem$(Object container, Object index) {
    Object result;
    switch (container) {
      case PyList<?> list  -> result = ListIntrinsics.$delete$(list, $toIndex$(index, "list"));
      case PyDict<?,?> dict -> result = DictIntrinsics.$delete$((PyDict<Object, ?>) dict, index);
      case PyTuple _ -> throw new PyTypeError("'tuple' doesn't support item deletion");
      case String _ -> throw new PyTypeError("'str' doesn't support item deletion");
      default -> throw new PyTypeError("'" + typeName(container) + "' object is not subscriptable");
    }
    return result;
  }

  /// Converts Long and Boolean to long.
  /// A BigInteger raises IndexError("cannot fit 'int' into an index-sized integer").
  /// Anything else raises TypeError("list indices must be integers or slices, not str").
  @Intrinsic
  public static long $toIndex$(Object index, String name) {
    return switch (index) {
      case Long l -> l;
      case Boolean b -> b ? 1L : 0L;
      case BigInteger b -> throw new PyIndexError("cannot fit 'int' into an index-sized integer");
      default -> throw new PyTypeError(name + " indices must be integers or slices, not str");
    };
  }
}