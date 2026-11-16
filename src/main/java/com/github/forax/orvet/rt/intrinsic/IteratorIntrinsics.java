package com.github.forax.orvet.rt.intrinsic;

import com.github.forax.orvet.rt.PyDict;
import com.github.forax.orvet.rt.PyIterator;
import com.github.forax.orvet.rt.PyIterator.DictIterator;
import com.github.forax.orvet.rt.PyIterator.ListIterator;
import com.github.forax.orvet.rt.PyIterator.RangeIterator;
import com.github.forax.orvet.rt.PyIterator.StringIterator;
import com.github.forax.orvet.rt.PyIterator.TupleIterator;
import com.github.forax.orvet.rt.PyList;
import com.github.forax.orvet.rt.PyRange;
import com.github.forax.orvet.rt.PyTuple;
import com.github.forax.orvet.rt.error.PyTypeError;

import static com.github.forax.orvet.rt.intrinsic.IntrinsicSupport.typeName;

/// The protocol of {@code for}: {@code $iterator$} once, then {@code $next$} until Java null.
public final class IteratorIntrinsics {
  private IteratorIntrinsics() { throw new AssertionError(); }

  @Intrinsic
  public static Object $iterator$(Object iterable) {
    switch (iterable) {
      case PyTuple tuple -> { return new TupleIterator(tuple); }
      case String string -> { return new StringIterator(string); }
      case PyList<?> list -> { return new ListIterator(list); }
      case PyDict<?,?> dict -> { return new DictIterator(dict); }
      case PyRange range -> { return new RangeIterator(range); }
      default -> throw new PyTypeError("'" + typeName(iterable) + "' object is not iterable");
    }
  }

  @Intrinsic
  public static Object $next$(Object iterator) {
    switch (iterator) {
      case StringIterator it -> { return it.next(); }
      case TupleIterator it -> { return it.next(); }
      case ListIterator it -> { return it.next(); }
      case DictIterator it -> { return it.next(); }
      case RangeIterator it -> { return it.next(); }
      default -> throw new PyTypeError("'" + typeName(iterator) + "' object has no next");
    }
  }
}