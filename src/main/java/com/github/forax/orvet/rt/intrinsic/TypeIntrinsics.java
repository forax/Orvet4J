package com.github.forax.orvet.rt.intrinsic;

import com.github.forax.orvet.rt.PyDict;
import com.github.forax.orvet.rt.PyFunction;
import com.github.forax.orvet.rt.PyList;
import com.github.forax.orvet.rt.PyNone;
import com.github.forax.orvet.rt.PyTuple;
import com.github.forax.orvet.rt.PyType;

import java.math.BigInteger;

public final class TypeIntrinsics {
  private TypeIntrinsics() { throw new AssertionError(); }

  @Intrinsic
  public static PyType $type$(Object value) {
    switch (value) {
      case Long _, BigInteger _ -> { return PyType.INT; }
      case Boolean _ -> { return PyType.BOOL; }
      case Double _ -> { return PyType.FLOAT; }
      case String _ -> { return PyType.STR; }
      case PyNone _ -> { return PyType.NONE_TYPE; }
      case PyTuple _ -> { return PyType.TUPLE; }
      case PyList<?> _ -> { return PyType.LIST; }
      case PyDict<?, ?> _ -> { return PyType.DICT; }
      //case PyRange _ -> { return PyType.RANGE; }
      //case PySlice _ -> { return PyType.SLICE; }
      case PyType _ -> { return PyType.TYPE; }
      case PyFunction f -> { return f.isBuiltin() ? PyType.BUILTIN_FUNCTION : PyType.FUNCTION; }
      default -> throw new NotCanonicalException("not canonical", value);
    }
  }

  @Intrinsic
  public static boolean $isInstance$(Object value, PyType type) {
    return $type$(value).isSubtypeOf(type);
  }
}