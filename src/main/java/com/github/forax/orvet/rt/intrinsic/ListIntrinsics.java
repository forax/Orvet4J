package com.github.forax.orvet.rt.intrinsic;

import com.github.forax.orvet.rt.PyList;
import com.github.forax.orvet.rt.PyNone;
import com.github.forax.orvet.rt.PySlice;
import com.github.forax.orvet.rt.error.PyIndexError;
import com.github.forax.orvet.rt.error.PyValueError;
import com.github.forax.orvet.rt.error.UnimplementedFeatureError;

import java.util.Arrays;
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
    var resolved = slice.resolve(list.size());
    var result = new PyList<E>((int) resolved.count());
    var pos = resolved.start();
    for (var i = 0L; i < resolved.count(); i++, pos += resolved.step()) {
      result.append(list.get((int) pos));
    }
    return result;
  }

  @Intrinsic
  public static Object $setSlice$(PyList<Object> list, PySlice slice, Object iterable) {
    var items = toItems(iterable);                       // snapshot: l[:] = l is safe
    var resolved = slice.resolve(list.size());
    if (resolved.step() == 1) {
      var from = (int) resolved.start();
      list.splice(from, from + (int) resolved.count(), items);  // count == 0 inserts at `from`, as CPython
    } else {
      if (items.length != resolved.count()) {
        throw new PyValueError("attempt to assign sequence of size " + items.length
            + " to extended slice of size " + resolved.count());
      }
      var pos = resolved.start();
      for (var i = 0; i < items.length; i++, pos += resolved.step()) {
        list.set((int) pos, items[i]);
      }
    }
    return PyNone.INSTANCE;
  }

  @Intrinsic
  @SuppressWarnings("unchecked")
  public static <E> Object $deleteSlice$(PyList<E> list, PySlice slice) {
    var resolved = slice.resolve(list.size());
    if (resolved.count() == 0) {
      return PyNone.INSTANCE;
    }
    if (resolved.step() == 1) {
      list.splice((int) resolved.start(), (int) (resolved.start() + resolved.count()), (E[]) new Object[0]);
      return PyNone.INSTANCE;
    }
    var items = (E[]) list.toArray();
    var removed = new boolean[items.length];
    var pos = resolved.start();
    for (var i = 0L; i < resolved.count(); i++, pos += resolved.step()) {
      removed[(int) pos] = true;
    }
    var kept = (E[]) new Object[items.length - (int) resolved.count()];
    for (int i = 0, k = 0; i < items.length; i++) {
      if (!removed[i]) {
        kept[k++] = items[i];
      }
    }
    list.splice(0, items.length, kept);
    return PyNone.INSTANCE;
  }


  // -- method table

  public static <E> Object _append_(PyList<E> list, E item) {
    Objects.requireNonNull(item);
    list.append(item);
    return PyNone.INSTANCE;
  }

  public static Object _clear_(PyList<?> list) {
    Objects.requireNonNull(list);
    list.clear();
    return PyNone.INSTANCE;
  }

  public static Object _copy_(PyList<?> list) {
    Objects.requireNonNull(list);
    var newList = new PyList<>();
    newList.appendAll(list);
    return newList;
  }

  public static Object _count_(PyList<?> list, Object value) {
    Objects.requireNonNull(list);
    Objects.requireNonNull(value);
    var count = 0L;
    for (var i = 0; i < list.size(); i++) {
      if (ComparisonIntrinsics.$sameOrEq$(list.get(i), value)) {
        count++;
      }
    }
    return count;
  }

  public static Object _extend_(PyList<Object> list, Object iterable) {
    Objects.requireNonNull(list);
    Objects.requireNonNull(iterable);
    $iadd$(list, iterable);
    return PyNone.INSTANCE;
  }

  public static Object _index_(PyList<?> list, Object value) {
    Objects.requireNonNull(list);
    Objects.requireNonNull(value);
    return index(list, value, 0L, list.size());
  }
  public static Object _index_(PyList<?> list, Object value, Object start) {
    Objects.requireNonNull(list);
    Objects.requireNonNull(value);
    Objects.requireNonNull(start);
    return index(list, value,
        ItemIntrinsics.$toIndex$(start, "list"),
        list.size());
  }
  public static Object _index_(PyList<?> list, Object value, Object start, Object stop) {
    return index(list, value,
        ItemIntrinsics.$toIndex$(start, "list"),
        ItemIntrinsics.$toIndex$(stop, "list"));
  }

  public static <E> Object _insert_(PyList<? super E> list, Object indexObject, E value) {
    Objects.requireNonNull(list);
    Objects.requireNonNull(indexObject);
    Objects.requireNonNull(value);
    var index = ItemIntrinsics.$toIndex$(indexObject, "list");
    var size = list.size();
    var position = index < 0
        ? Math.max(0L, index + size)
        : Math.min(index, size);
    list.insert((int) position, value);
    return PyNone.INSTANCE;
  }

  public static Object _pop_(PyList<?> list) {
    Objects.requireNonNull(list);
    return pop(list, -1L);
  }
  public static Object _pop_(PyList<?> list, Object indexObject) {
    Objects.requireNonNull(list);
    Objects.requireNonNull(indexObject);
    return pop(list, ItemIntrinsics.$toIndex$(indexObject, "list"));
  }

  public static Object _remove_(PyList<?> list, Object value) {
    Objects.requireNonNull(list);
    Objects.requireNonNull(value);
    for (var i = 0; i < list.size(); i++) {
      if (ComparisonIntrinsics.$sameOrEq$(list.get(i), value)) {
        list.remove(i);
        return PyNone.INSTANCE;
      }
    }
    throw new PyValueError("list.remove(x): " + StringIntrinsics.$repr$(value) + " not in list");
  }

  public static <E> Object _reverse_(PyList<E> list) {
    Objects.requireNonNull(list);
    for (int left = 0, right = list.size() - 1; left < right; left++, right--) {
      var a = list.get(left);
      var b = list.get(right);
      list.set(left, b);
      list.set(right, a);
    }
    return PyNone.INSTANCE;
  }

  public static Object _sort_(PyList<?> list) {
    Objects.requireNonNull(list);
    list.sort((left, right) -> {
      if (ComparisonIntrinsics.$lt$(left, right)) {
        return -1;
      }
      if (ComparisonIntrinsics.$lt$(right, left)) {
        return 1;
      }
      return 0;
    });
    return PyNone.INSTANCE;
  }


  // -- helpers

  private static Object pop(PyList<?> list, long index) {
    var size = list.size();
    if (size == 0) {
      throw new PyIndexError("pop from empty list");
    }
    var normalized = index < 0 ? index + size : index;
    if (normalized < 0 || normalized >= size) {
      throw new PyIndexError("pop index out of range");
    }
    return list.remove((int) normalized);
  }

  private static Object index(PyList<?> list, Object value, long start, long stop) {
    var size = list.size();
    var from = normalizeBound(start, size);
    var to = normalizeBound(stop, size);
    for (var i = from; i < to; i++) {
      if (ComparisonIntrinsics.$sameOrEq$(list.get(i), value)) {
        return (long) i;
      }
    }
    throw new PyValueError(StringIntrinsics.$repr$(value) + " is not in list");
  }

  private static int normalizeBound(long index, int size) {
    var normalized = index < 0 ? index + size : index;
    return (int) Math.max(0L, Math.min((long) size, normalized));
  }

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
