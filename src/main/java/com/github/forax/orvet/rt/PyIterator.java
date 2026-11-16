package com.github.forax.orvet.rt;

import com.github.forax.orvet.rt.error.PyRuntimeError;
import com.github.forax.orvet.rt.intrinsic.IntrinsicSupport;
import com.github.forax.orvet.rt.intrinsic.TupleIntrinsics;

import java.math.BigInteger;

/// Internal iterators used by {@code for}; they are never visible to Python code.
public sealed interface PyIterator {
  final class TupleIterator implements PyIterator {
    private final PyTuple tuple;
    private final long size;
    private long index;

    public TupleIterator(PyTuple tuple) {
      this.tuple = tuple;
      this.size = TupleIntrinsics.$size$(tuple);
    }

    public Object next() {
      return index < size ? TupleIntrinsics.$get$(tuple, index++) : null;
    }
  }

  /// Index-based iterator: reads the list's current size on every next() call.
  final class ListIterator implements PyIterator {
    private final PyList<?> list;
    private int index;

    public ListIterator(PyList<?> list) {
      this.list = list;
      super();
    }

    public Object next() {
      if (index >= list.size()) {  // may change at runtime
        return null;
      }
      return list.get(index++);
    }
  }

  final class StringIterator implements PyIterator {
    private final String string;
    private int index;

    public StringIterator(String string) {
      this.string = string;
      super();
    }

    public Object next() {
      if (index >= string.length()) {
        return null;
      }
      var end = index + Character.charCount(string.codePointAt(index));
      var item = string.substring(index, end);
      index = end;
      return item;
    }
  }

  /// Key iterator. Value replacement is allowed; size changes and detected key-set changes fail.
  final class DictIterator implements PyIterator {
    private final PyDict<?,?> dict;
    private final int expectedSize;
    private final long expectedLayoutVersion;
    private int position;
    private int remaining;
    private String failure;
    private boolean exhausted;

    public DictIterator(PyDict<?,?> dict) {
      var expectedSize = dict.size();
      this.dict = dict;
      this.expectedLayoutVersion = dict.layoutVersion();
      this.expectedSize = expectedSize;
      this.remaining = expectedSize;
      super();
    }

    public Object next() {
      if (failure != null) {
        throw new PyRuntimeError(failure);
      }
      if (exhausted) {
        return null;
      }
      if (dict.size() != expectedSize) {
        return fail("dictionary changed size during iteration");
      }
      if (dict.layoutVersion() != expectedLayoutVersion) {
        return fail("dictionary keys changed during iteration");
      }

      while (position < dict.entryCount()) {
        var key = dict.keyAt(position++);
        if (key == null) {
          continue;
        }
        if (remaining == 0) {
          return fail("dictionary keys changed during iteration");
        }
        remaining--;
        return key;
      }

      exhausted = true;
      return null;
    }

    private Object fail(String message) {
      failure = message;
      throw new PyRuntimeError(message);
    }
  }

  final class RangeIterator implements PyIterator {
    private final boolean small;
    private long current, remaining, step;                   // small mode
    private BigInteger bigCurrent, bigStep, bigRemaining;    // big mode

    public RangeIterator(PyRange range) {
      if (range.isSmall()) {
        small = true;
        current = range.smallStart();
        step = range.smallStep();
        remaining = range.smallLength();
      } else {
        small = false;
        bigCurrent = PyRange.big(range.start());
        bigStep = PyRange.big(range.step());
        bigRemaining = range.length();
      }
    }

    public Object next() {
      if (small) {
        if (remaining == 0) {
          return null;
        }
        var value = current;
        if (--remaining != 0) {
          current += step;               // only computed when the next element exists: no overflow
        }
        return value;
      }
      if (bigRemaining.signum() == 0) {
        return null;
      }
      var value = bigCurrent;
      bigRemaining = bigRemaining.subtract(BigInteger.ONE);
      bigCurrent = bigCurrent.add(bigStep);
      return IntrinsicSupport.normalize(value);
    }
  }
}
