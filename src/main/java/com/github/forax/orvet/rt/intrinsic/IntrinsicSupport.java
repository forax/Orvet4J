package com.github.forax.orvet.rt.intrinsic;

import com.github.forax.orvet.rt.PyDict;
import com.github.forax.orvet.rt.PyFunction;
import com.github.forax.orvet.rt.PyList;
import com.github.forax.orvet.rt.PyNone;
import com.github.forax.orvet.rt.PyRange;
import com.github.forax.orvet.rt.PySlice;
import com.github.forax.orvet.rt.PyTuple;
import com.github.forax.orvet.rt.error.PyOverflowError;
import com.github.forax.orvet.rt.error.PyTypeError;

import java.math.BigInteger;

/// Non-intrinsic helpers shared by the intrinsic classes of this package.
/// Nothing here is specialized: these methods are plain static calls.
public final class IntrinsicSupport {
  private IntrinsicSupport() {
    throw new AssertionError();
  }

  /// int and bool (a bool is an int in every numeric operation).
  static boolean isInt(Object value) {
    return value instanceof Long || value instanceof BigInteger || value instanceof Boolean;
  }

  static boolean isNumber(Object value) {
    return isInt(value) || value instanceof Double;
  }

  /// Valid for a Long or a Boolean only.
  static long toLong(Object value) {
    return switch (value) {
      case Long l -> l;
      case Boolean b -> b ? 1L : 0L;
      default -> throw new AssertionError("expected Long/Boolean " + value);
    };
  }

  static BigInteger toBigInteger(Object value) {
    return switch (value) {
      case Long l -> BigInteger.valueOf(l);
      case BigInteger b -> b;
      case Boolean b -> b ? BigInteger.ONE : BigInteger.ZERO;
      default -> throw new AssertionError("not an int: " + value);
    };
  }

  /// Python's int-to-float conversion (an int too large for a double raises OverflowError).
  static double toDouble(Object value) {
    return switch (value) {
      case Double d -> d;
      case Long l -> l.doubleValue();
      case Boolean b -> b ? 1.0 : 0.0;
      case BigInteger b -> {
        var d = b.doubleValue();  // correctly rounded, infinite if out of range
        if (Double.isInfinite(d)) {
          throw new PyOverflowError("int too large to convert to float");
        }
        yield d;
      }
      default -> throw new AssertionError("not a number: " + value);
    };
  }

  /// The name that type(value).__name__ would report. Raises NotCanonicalException for a non-canonical value.
  //public static String typeName(Object value) {
  //  return TypeIntrinsics.$type$(value).name();
  //}

  /// Class check only: whether the value is one of the runtime's representation classes.
  /// (The "BigInteger never fits in a long" invariant is not checked here.)
  public static boolean isCanonical(Object v) {
    return v instanceof Long || v instanceof BigInteger || v instanceof Boolean || v instanceof Double
        || v instanceof String || v instanceof PyNone || v instanceof PyTuple || v instanceof PyList<?>
        || v instanceof PyDict<?, ?> || v instanceof PyFunction
        || v instanceof PyRange || v instanceof PySlice;
  }

  /// For "answer is false" paths that never look at a value's class.
  public static void checkCanonical(Object value) {
    if (!isCanonical(value)) {
      throw new NotCanonicalException("not canonical", value);
    }
  }

  /// Keeps the invariant that a BigInteger never holds a value that fits in a long.
  public static Object normalize(BigInteger value) {
    if (value.bitLength() < 64) {
      return value.longValue();
    }
    return value;
  }

  /// The name that type(value).__name__ would report.
  public static String typeName(Object value) {
    return switch (value) {
      case Long _, BigInteger _ -> "int";
      case Boolean _ -> "bool";
      case Double _ -> "float";
      case String _ -> "str";
      case PyNone _ -> "NoneType";
      case PyTuple _ -> "tuple";
      case PyList<?> _ -> "list";
      case PyDict<?,?> _ -> "dict";
      case PyFunction _ -> "function";
      default -> "java_type(" + value.getClass().getName() + ')';
    };
  }

  static PyTypeError unsupportedOperands(String symbol, Object left, Object right) {
    return new PyTypeError("unsupported operand type(s) for " + symbol + ": '"
        + typeName(left) + "' and '" + typeName(right) + "'");
  }

  static PyTypeError badUnaryOperand(String symbol, Object operand) {
    return new PyTypeError("bad operand type for unary " + symbol + ": '" + typeName(operand) + "'");
  }
}