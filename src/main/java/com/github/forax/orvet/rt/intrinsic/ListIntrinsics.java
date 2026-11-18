package com.github.forax.orvet.rt.intrinsic;

import com.github.forax.orvet.rt.PyList;
import com.github.forax.orvet.rt.PyNone;
import com.github.forax.orvet.rt.PySlice;
import com.github.forax.orvet.rt.error.PyIndexError;
import com.github.forax.orvet.rt.error.PyValueError;
import com.github.forax.orvet.rt.error.UnimplementedFeatureError;

import java.util.Objects;

/// Python-list operations.
///
/// These methods are the semantic boundary between PyList's storage operations and the rest of
/// the runtime. Index arguments here are already converted to a Java long; converting arbitrary
/// Python objects (including bool and BigInteger) belongs in the generic item-operation layer.
public final class ListIntrinsics {
  private ListIntrinsics() {
    throw new AssertionError("no instances");
  }

  public static <E> PyList<E> newList() {
    return new PyList<>();
  }

  /// Python len(list).
  @Intrinsic
  public static long $size$(PyList<?> list) {
    return list.size();
  }

  /// Python `list[index]` for an index already represented as a long.
  @Intrinsic
  public static <E> E $get$(PyList<E> list, long index) {
    return list.get(checkedIndex(index, list.size()));
  }

  /// Python `list[index] = value`.
  /// Returns Python None for use by the generic assignment intrinsic.
  @Intrinsic
  public static <E> Object $set$(PyList<E> list, long index, E value) {
    list.set(checkedAssignmentIndex(index, list.size()), value);
    return PyNone.INSTANCE;
  }

  /// Python `del list[index]`.
  /// Returns Python None for use by the generic deletion intrinsic.
  @Intrinsic
  public static Object $delete$(PyList<?> list, long index) {
    list.remove(checkedAssignmentIndex(index, list.size()));
    return PyNone.INSTANCE;
  }

  /// Python list + list. The result is always a distinct mutable list.
  @Intrinsic
  public static <E> PyList<E> $concat$(PyList<? extends E> left, PyList<? extends E> right) {
    var totalSize = (long) left.size() + right.size();
    if (totalSize > Integer.MAX_VALUE) {
      throw tooLarge();
    }
    var result = new PyList<E>((int) totalSize);
    result.appendAll(left);
    result.appendAll(right);
    return result;
  }

  /// Python list * count. The result is always a distinct mutable list, including count == 1.
  @Intrinsic
  public static <E> PyList<E> $repeat$(PyList<? extends E> list, long count) {
    if (count <= 0 || list.isEmpty()) {
      return new PyList<>();
    }
    if (count > Integer.MAX_VALUE / (long) list.size()) {
      throw tooLarge();
    }
    var totalSize = (int) (list.size() * count);
    var result = new PyList<E>(totalSize);
    for (var repetition = 0; repetition < (int) count; repetition++) {
      result.appendAll(list);
    }
    return result;
  }

  /// Python list += iterable. The receiver is mutated and returned.
  ///
  /// PyList is handled directly so self-extension takes a finite snapshot. Other currently
  /// supported iterables use the shared iterator protocol (tuple and str today).
  @Intrinsic
  public static PyList<Object> $iadd$(PyList<Object> list, Object iterable) {
    switch (iterable) {
      case PyList<?> other -> list.appendAll(other);
      default -> {
        var iterator = IteratorIntrinsics.$iterator$(iterable);
        Object item;
        while ((item = IteratorIntrinsics.$next$(iterator)) != null) {
          list.append(item);
        }
      }
    }
    return list;
  }

  /// Python list *= count. The receiver is mutated and returned.
  @Intrinsic
  public static <E> PyList<E> $imul$(PyList<E> list, long count) {
    list.repeatInPlace(count);
    return list;
  }

  @Intrinsic
  public static boolean $in$(PyList<?> list, Object item) {
    var index = 0;
    // Do not snapshot size: the list may grow while membership is being evaluated.
    while (index < list.size()) {
      if (ComparisonIntrinsics.$sameOrEq$(list.get(index), item)) {
        return true;
      }
      index++;
    }
    return false;
  }

  @Intrinsic
  public static <E> PyList<E> $slice$(PyList<E> list, PySlice slice) {
    var r = slice.resolve(list.size());
    var result = new PyList<E>((int) r.count());
    var pos = r.start();
    for (var i = 0L; i < r.count(); i++, pos += r.step()) {
      result.append(list.get((int) pos));
    }
    return result;
  }

  @Intrinsic
  public static Object $setSlice$(PyList<Object> list, PySlice slice, Object iterable) {
    var items = toItems(iterable);                       // snapshot: l[:] = l is safe
    var r = slice.resolve(list.size());
    if (r.step() == 1) {
      var from = (int) r.start();
      list.splice(from, from + (int) r.count(), items);  // count == 0 inserts at `from`, as CPython
    } else {
      if (items.length != r.count()) {
        throw new PyValueError("attempt to assign sequence of size " + items.length
            + " to extended slice of size " + r.count());
      }
      var pos = r.start();
      for (var i = 0; i < items.length; i++, pos += r.step()) {
        list.set((int) pos, items[i]);
      }
    }
    return PyNone.INSTANCE;
  }

  @Intrinsic
  @SuppressWarnings("unchecked")
  public static <E> Object $deleteSlice$(PyList<E> list, PySlice slice) {
    var r = slice.resolve(list.size());
    if (r.count() == 0) {
      return PyNone.INSTANCE;
    }
    if (r.step() == 1) {
      list.splice((int) r.start(), (int) (r.start() + r.count()), (E[]) new Object[0]);
      return PyNone.INSTANCE;
    }
    var items = (E[]) list.toArray();
    var removed = new boolean[items.length];
    var pos = r.start();
    for (var i = 0L; i < r.count(); i++, pos += r.step()) {
      removed[(int) pos] = true;
    }
    var kept = (E[]) new Object[items.length - (int) r.count()];
    for (int i = 0, k = 0; i < items.length; i++) {
      if (!removed[i]) {
        kept[k++] = items[i];
      }
    }
    list.splice(0, items.length, kept);
    return PyNone.INSTANCE;
  }


  // -- method table

  @SuppressWarnings("unchecked")
  public static <E> Object _append_(PyList<E> list, E item) {
    Objects.requireNonNull(item);
    list.append(item);
    return PyNone.INSTANCE;
  }


  // -- helpers

  private static Object[] toItems(Object iterable) {
    if (iterable instanceof PyList<?> l) {
      return l.toArray();
    }
    var out = new PyList<Object>();
    var it = IteratorIntrinsics.$iterator$(iterable);
    Object item;
    while ((item = IteratorIntrinsics.$next$(it)) != null) {
      out.append(item);
    }
    return out.toArray();
  }


  private static int checkedIndex(long index, int size) {
    // Adding size to a negative long cannot overflow: size is non-negative and at most int-sized.
    var normalized = index < 0 ? index + size : index;
    if (normalized < 0 || normalized >= size) {
      throw new PyIndexError("list index out of range");
    }
    return (int) normalized;
  }

  private static int checkedAssignmentIndex(long index, int size) {
    // Adding size to a negative long cannot overflow: size is non-negative and at most int-sized.
    var normalized = index < 0 ? index + size : index;
    if (normalized < 0 || normalized >= size) {
      throw new PyIndexError("list assignment index out of range");
    }
    return (int) normalized;
  }

  private static UnimplementedFeatureError tooLarge() {
    return new UnimplementedFeatureError("list larger than the maximum Java array size");
  }
}
