package com.github.forax.orvet.rt.intrinsic;

import com.github.forax.orvet.rt.PyDict;
import com.github.forax.orvet.rt.PyNone;
import com.github.forax.orvet.rt.PyType;
import com.github.forax.orvet.rt.error.PyKeyError;
import com.github.forax.orvet.rt.error.PyTypeError;
import com.github.forax.orvet.rt.error.PyValueError;

import java.util.Objects;

/// Python dictionary operations. Storage mechanics live in [PyDict].
public final class DictIntrinsics {
  private DictIntrinsics() {
    throw new AssertionError("no instances");
  }

  public static <K, V> PyDict<K,V> newDict() {
    return new PyDict<>();
  }

  /** Python {@code len(dict)}. */
  @Intrinsic
  public static long $size$(PyDict<?,?> dict) {
    return dict.size();
  }

  /** Python {@code dict[key]}.*/
  @Intrinsic
  public static <V> V $get$(PyDict<?,? extends V> dict, Object key) {
    var value = dict.get(key);
    if (value == null) {
      throw missingKey(key);
    }
    return value;
  }

  /** Python {@code dict[key] = value}; returns Python None. */
  @Intrinsic
  public static <V> Object $set$(PyDict<Object,? super V> dict, Object key, V value) {
    dict.put(key, value);
    return PyNone.INSTANCE;
  }

  /** Python {@code del dict[key]}; returns Python None. */
  @Intrinsic
  public static Object $delete$(PyDict<Object,?> dict, Object key) {
    var value = dict.remove(key);
    if (value == null) {
      throw missingKey(key);
    }
    return PyNone.INSTANCE;
  }

  /** Python membership helper used by {@code ContainmentIntrinsics.$in$}. */
  @Intrinsic
  public static boolean $in$(PyDict<?,?> dict, Object key) {
    return dict.containsKey(key);
  }


  // -- method table

  public static Object _clear_(PyDict<?,?> dict) {
    Objects.requireNonNull(dict);
    dict.clear();
    return PyNone.INSTANCE;
  }

  public static Object _copy_(PyDict<?,?> source) {
    Objects.requireNonNull(source);
    var result = new PyDict<>();
    for (var i = 0; i < source.entryCount(); i++) {
      if (source.isLive(i)) {
        result.put(source.keyAt(i), source.valueAt(i));
      }
    }
    return result;
  }

  public static Object _fromkeys_(PyType type, Object iterable) {
    Objects.requireNonNull(type);
    Objects.requireNonNull(iterable);
    return _fromkeys_(type, iterable, PyNone.INSTANCE);
  }

  public static Object _fromkeys_(PyType type, Object iterable, Object value) {
    Objects.requireNonNull(type);
    Objects.requireNonNull(iterable);
    Objects.requireNonNull(value);
    if (type != PyType.DICT) {
      throw new IllegalArgumentException("invalid type " + type);
    }
    return fromKeys(iterable, value);
  }

  public static Object _get_(PyDict<?,?> dict, Object key) {
    Objects.requireNonNull(dict);
    Objects.requireNonNull(key);
    return _get_(dict, key, PyNone.INSTANCE);
  }
  public static Object _get_(PyDict<?, ?> dict, Object key, Object defaultValue) {
    Objects.requireNonNull(dict);
    Objects.requireNonNull(key);
    Objects.requireNonNull(defaultValue);
    var value = dict.get(key);
    return value == null ? defaultValue : value;
  }

  public static Object _pop_(PyDict<?,?> dict, Object key) {
    Objects.requireNonNull(dict);
    Objects.requireNonNull(key);
    var value = dict.remove(key);
    if (value == null) {
      throw missingKey(key);
    }
    return value;
  }
  public static Object _pop_(PyDict<?,?> dict, Object key, Object defaultValue) {
    Objects.requireNonNull(dict);
    Objects.requireNonNull(key);
    Objects.requireNonNull(defaultValue);
    var value = dict.remove(key);
    return value == null ? defaultValue : value;
  }

  public static Object _popitem_(PyDict<?,?> dict) {
    Objects.requireNonNull(dict);
    for (var i = dict.entryCount() - 1; i >= 0; i--) {
      if (!dict.isLive(i)) {
        continue;
      }
      var key = dict.keyAt(i);
      var value = dict.valueAt(i);
      dict.remove(key);
      return TupleIntrinsics.fromArray(key, value);
    }
    throw new PyKeyError("'popitem(): dictionary is empty'");
  }

  public static <K> Object _setdefault_(PyDict<? super K,Object> dict, K key) {
    Objects.requireNonNull(dict);
    Objects.requireNonNull(key);
    return _setdefault_(dict, key, PyNone.INSTANCE);
  }

  public static <K,V> Object _setdefault_(PyDict<? super K,? super V> dict, K key, V defaultValue) {
    Objects.requireNonNull(dict);
    Objects.requireNonNull(key);
    var value = dict.get(key);
    if (value != null) {
      return value;
    }
    dict.put(key, defaultValue);
    return defaultValue;
  }

  public static Object _update_(PyDict<Object, Object> dict, Object other) {
    Objects.requireNonNull(dict);
    Objects.requireNonNull(other);
    updateFrom(dict, other);
    return PyNone.INSTANCE;
  }

  // -- helpers

  private static void updateFrom(PyDict<Object, Object> dict, Object other) {
    switch (other) {
      // Case 1: another dictionary.
      case PyDict<?, ?> source -> {
        for (var i = 0; i < source.entryCount(); i++) {
          if (source.isLive(i)) {
            dict.put(source.keyAt(i), source.valueAt(i));
          }
        }
        return;
      }

      // Case 2: an iterable of pairs.
      default -> {
        var iterator = IteratorIntrinsics.$iterator$(other);
        Object pair;
        var index = 0;
        while ((pair = IteratorIntrinsics.$next$(iterator)) != null) {
          updateFromPair(dict, pair, index++);
        }
        return;
      }
    }
  }

  private static void updateFromPair(PyDict<Object, Object> dict, Object pair, int index) {
    Object iterator;
    try {
      iterator = IteratorIntrinsics.$iterator$(pair);
    } catch (PyTypeError e) {
      throw new PyTypeError("cannot convert dictionary update sequence element #" + index + " to a sequence");
    }
    var key = IteratorIntrinsics.$next$(iterator);
    var value = IteratorIntrinsics.$next$(iterator);
    if (key == null || value == null) {
      throw new PyValueError("dictionary update sequence element #" + index +
              " has length " + (key == null ? 0 : 1) + "; 2 is required");
    }
    if (IteratorIntrinsics.$next$(iterator) != null) {
      var length = 3;
      while (IteratorIntrinsics.$next$(iterator) != null) {
        length++;
      }
      throw new PyValueError("dictionary update sequence element #" + index +
              " has length " + length + "; 2 is required");
    }
    dict.put(key, value);
  }

  private static PyDict<Object, Object> fromKeys(Object iterable, Object value) {
    var result = new PyDict<>();
    var iterator = IteratorIntrinsics.$iterator$(iterable);
    Object key;
    while ((key = IteratorIntrinsics.$next$(iterator)) != null) {
      result.put(key, value);
    }
    return result;
  }

  /**
   * Dictionary equality helper called by the generic equality intrinsic. Key order is irrelevant.
   * The identity shortcut matches Python's container comparison behavior, including NaN keys.
   */
  public static boolean equals(PyDict<?,?> left, PyDict<?,?> right) {
    if (left == right) {
      return true;
    }
    if (left.size() != right.size()) {
      return false;
    }

    for (var i = 0; i < left.entryCount(); i++) {
      if (!left.isLive(i)) {
        continue;
      }
      var key = left.keyAt(i);
      var value = left.valueAt(i);
      var otherValue = right.get(key);
      if (otherValue == null || !ComparisonIntrinsics.$sameOrEq$(value, otherValue)) {
        return false;
      }
    }
    return true;
  }

  private static PyKeyError missingKey(Object key) {
    return new PyKeyError((String) StringIntrinsics.$repr$(key));
  }
}
