package com.github.forax.orvet.rt.intrinsic;

import com.github.forax.orvet.rt.PyDict;
import com.github.forax.orvet.rt.PyList;
import com.github.forax.orvet.rt.PyRange;
import com.github.forax.orvet.rt.PySlice;
import com.github.forax.orvet.rt.PyTuple;
import com.github.forax.orvet.rt.error.PyIndexError;
import com.github.forax.orvet.rt.error.PyTypeError;

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
      case PyRange range -> RangeIntrinsics.$size$(range);
      default -> throw new PyTypeError("object of type '" + typeName(value) + "' has no len()");
    };
  }

  @Intrinsic
  public static Object $getItem$(Object container, Object index) {
    switch (container) {
      case PyList<?> list -> {
        switch (index) {
          case PySlice slice -> { return ListIntrinsics.$slice$(list, slice); }
          default -> { return ListIntrinsics.$get$(list, $toIndex$(index, "list")); }
        }
      }
      case PyDict<?, ?> dict -> { return DictIntrinsics.$get$(dict, index); }
      case PyTuple tuple -> {
        switch (index) {
          case PySlice slice -> { return TupleIntrinsics.$slice$(tuple, slice); }
          default -> { return TupleIntrinsics.$get$(tuple, $toIndex$(index, "tuple")); }
        }
      }
      case PyRange range -> { return rangeItem(range, index); }
      case String string -> { return stringItem(string, index); }
      default -> throw new PyTypeError("'" + typeName(container) + "' object is not subscriptable");
    }
  }

  @Intrinsic
  @SuppressWarnings("unchecked")
  public static Object $setItem$(Object container, Object index, Object value) {
    switch (container) {
      case PyList<?> list  -> {
        switch (index) {
          case PySlice slice -> { return ListIntrinsics.$setSlice$((PyList<Object>) list, slice, value); }
          default -> { return ListIntrinsics.$set$((PyList<Object>) list, $toIndex$(index, "list"), value); }
        }
      }
      case PyDict<?,?> dict -> { return DictIntrinsics.$set$((PyDict<Object, Object>) dict, index, value); }
      case PyTuple _ -> throw new PyTypeError("'tuple' object does not support item assignment");
      case PyRange _ -> throw new PyTypeError("'range' object does not support item assignment");
      case String _ -> throw new PyTypeError("'str' object does not support item assignment");
      default -> throw new PyTypeError("'" + typeName(container) + "' object is not subscriptable");
    }
  }

  @Intrinsic
  @SuppressWarnings("unchecked")
  public static Object $deleteItem$(Object container, Object index) {
    switch (container) {
      case PyList<?> list  -> {
        switch (index) {
          case PySlice slice -> { return ListIntrinsics.$deleteSlice$((PyList<Object>) list, slice); }
          default -> { return ListIntrinsics.$delete$((PyList<Object>) list, $toIndex$(index, "list")); }
        }
      }
      case PyDict<?,?> dict -> { return DictIntrinsics.$delete$((PyDict<Object, ?>) dict, index); }
      case PyTuple _ -> throw new PyTypeError("'tuple' doesn't support item deletion");
      case PyRange _ -> throw new PyTypeError("'range' object does not support item deletion");
      case String _ -> throw new PyTypeError("'str' doesn't support item deletion");
      default -> throw new PyTypeError("'" + typeName(container) + "' object is not subscriptable");
    }
  }

  /// Converts Long and Boolean to long.
  /// A BigInteger raises IndexError("cannot fit 'int' into an index-sized integer").
  /// Anything else raises TypeError("list indices must be integers or slices, not str").
  @Intrinsic
  public static long $toIndex$(Object index, String name) {
    return switch (index) {
      case Long l -> l;
      case Boolean b -> b ? 1L : 0L;
      case BigInteger _ -> throw new PyIndexError("cannot fit 'int' into an index-sized integer");
      default -> throw new PyTypeError(name.equals("string")
          ? "string indices must be integers, not '" + typeName(index) + "'"
          : name + " indices must be integers or slices, not " + typeName(index));
    };
  }


  private static Object rangeItem(PyRange range, Object index) {
    return switch (index) {
      case Long l -> RangeIntrinsics.$get$(range, l);
      case Boolean b -> RangeIntrinsics.$get$(range, b ? 1L : 0L);
      case BigInteger big -> RangeIntrinsics.getBig(range, big);   // range[10**20] is legal in CPython
      case PySlice slice -> RangeIntrinsics.$slice$(range, slice);
      default -> throw badIndex("range", index);
    };
  }

  private static Object stringItem(String s, Object index) {
    var codePoints = s.codePoints().toArray();           // O(n); correct for supplementary characters
    if (index instanceof PySlice slice) {
      var r = slice.resolve(codePoints.length);
      var sb = new StringBuilder();
      var pos = r.start();
      for (var i = 0L; i < r.count(); i++, pos += r.step()) {
        sb.appendCodePoint(codePoints[(int) pos]);
      }
      return sb.toString();
    }
    var i = $toIndex$(index, "string");                  // the intrinsic call is rewritten to an indy
    var n = i < 0 ? i + codePoints.length : i;
    if (n < 0 || n >= codePoints.length) {
      throw new PyIndexError("string index out of range");
    }
    return new String(codePoints, (int) n, 1);
  }

  private static PyTypeError badIndex(String name, Object index) {
    return new PyTypeError(name + " indices must be integers or slices, not " + typeName(index));
  }
}