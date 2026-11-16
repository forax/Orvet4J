package com.github.forax.orvet.rt;

import com.github.forax.orvet.rt.error.PyOverflowError;
import com.github.forax.orvet.rt.error.PyTypeError;
import com.github.forax.orvet.rt.error.PyValueError;
import com.github.forax.orvet.rt.error.PyZeroDivisionError;
import com.github.forax.orvet.rt.error.UnimplementedFeatureError;
import com.github.forax.orvet.rt.intrinsic.ArithmeticIntrinsics;
import com.github.forax.orvet.rt.intrinsic.IntrinsicSupport;
import com.github.forax.orvet.rt.intrinsic.ItemIntrinsics;
import com.github.forax.orvet.rt.intrinsic.IteratorIntrinsics;
import com.github.forax.orvet.rt.intrinsic.StringIntrinsics;
import com.github.forax.orvet.rt.intrinsic.TruthinessIntrinsics;
import com.github.forax.orvet.rt.intrinsic.TupleIntrinsics;
import com.github.forax.orvet.rt.intrinsic.TypeIntrinsics;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.IllegalCharsetNameException;
import java.nio.charset.UnsupportedCharsetException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

final class Builtins {
  private static final Object[] NO_CAPTURE_ARGS = new Object[0];
  private static final BigInteger MAX_ARRAY_SIZE = BigInteger.valueOf(Integer.MAX_VALUE - 8L);

  private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
  static final HashMap<String, PyFunction> BUILTINS;
  static {
    var codes = new PyFunction.Code[] {
        fixed("abs", "x"),
        variadic("bytearray"),
        fixed("bin", "x"),
        specializedVariadic("bool", TypeIntrinsics.class),
        variadic("dict"),
        fixed("divmod", "a", "b"),
        fixed("hex", "x"),
        specializedVariadic("int", TypeIntrinsics.class),
        specialized("len", ItemIntrinsics.class, "obj"),
        fixed("oct", "x"),
        fixed("ord", "c"),
        variadic("pow"),
        variadic("print"),
        variadic("range"),
        specialized("repr", StringIntrinsics.class, "obj"),
        variadic("round")
    };
    var builtins = new HashMap<String, PyFunction>();
    for(var code : codes) {
      builtins.put(code.name(), new PyFunction(code, NO_CAPTURE_ARGS));
    }
    BUILTINS = builtins;
  }

  private static PyFunction.Code fixed(String name, String... parameters) {
    return new PyFunction.Code(name, List.of(parameters), false, 0, null, null);
  }

  private static PyFunction.Code variadic(String name) {
    return new PyFunction.Code(name, List.of(), true, 0, null, null);
  }

  private static PyFunction.Code specialized(String name, Class<?> intrinsicClass, String... parameters) {
    return new PyFunction.Code(name, List.of(parameters), false, 0, new PyFunction.SpecializationContext(intrinsicClass), null);
  }

  private static PyFunction.Code specializedVariadic(String name, Class<?> intrinsicClass) {
    return new PyFunction.Code(name, List.of(), true, 0, new PyFunction.SpecializationContext(intrinsicClass), null);
  }

  /// The entry of a builtin: always a fixed-arity handle. For a variadic builtin the last parameter
  /// is an Object[] that call sites fill explicitly (never through the varargs-collector bit).
  static MethodHandle resolve(PyFunction.Code code) {
    var name = code.name();
    var mh = switch (code.specializationContext()) {
      case PyFunction.SpecializationContext(var intrinsicClass) ->
          SpecializationSupport.specialize("$" + name + "$", builtinType(code), intrinsicClass);
      case null -> {
        try {
          yield LOOKUP.findStatic(Builtins.class, "_" +name + "_", builtinType(code)).asFixedArity();
        } catch (NoSuchMethodException | IllegalAccessException e) {
          throw new AssertionError(e);
        }
      }
    };
    return MethodHandles.dropArguments(mh, 0, PyFunction.class);
  }

  private static MethodType builtinType(PyFunction.Code code) {
    var types = new ArrayList<Class<?>>();
    for (var _ : code.parameters()) {
      types.add(Object.class);
    }
    if (code.isVariadic()) {
      types.add(Object[].class);
    }
    return MethodType.methodType(Object.class, types);
  }


  // -- Builtins

  @SuppressWarnings("unused")
  public static Object _print_(Object... args) {
    var builder = new StringBuilder();
    var separator = "";
    for (var arg : args) {
      builder.append(separator).append(StringIntrinsics.$str$(arg));
      separator = " ";
    }
    builder.append('\n');
    System.out.print(builder);
    return PyNone.INSTANCE;
  }

  /// A range is currently materialized as a list because the runtime does not yet
  /// have a dedicated immutable range type or a range iterator.
  @SuppressWarnings("unused")
  public static Object _range_(Object... args) {
    requireCount("range", args.length, 1, 3);
    BigInteger start;
    BigInteger stop;
    BigInteger step;
    if (args.length == 1) {
      start = BigInteger.ZERO;
      stop = asIndex(args[0]);
      step = BigInteger.ONE;
    } else {
      start = asIndex(args[0]);
      stop = asIndex(args[1]);
      step = args.length == 3 ? asIndex(args[2]) : BigInteger.ONE;
    }
    if (step.signum() == 0) {
      throw new PyValueError("range() arg 3 must not be zero");
    }

    var count = BigInteger.ZERO;
    if (step.signum() > 0 && start.compareTo(stop) < 0) {
      count = stop.subtract(start).subtract(BigInteger.ONE).divide(step).add(BigInteger.ONE);
    } else if (step.signum() < 0 && start.compareTo(stop) > 0) {
      count = start.subtract(stop).subtract(BigInteger.ONE).divide(step.negate()).add(BigInteger.ONE);
    }
    if (count.compareTo(MAX_ARRAY_SIZE) > 0) {
      throw new UnimplementedFeatureError("range larger than the maximum Java array size");
    }

    var result = new PyList<>(count.intValue());
    var value = start;
    for (var i = 0; i < count.intValue(); i++) {
      result.append(normalizeInt(value));
      value = value.add(step);
    }
    return result;
  }

  @SuppressWarnings("unused")
  public static Object _abs_(Object value) {
    return switch (value) {
      case Boolean b -> b ? 1L : 0L;
      case Long l -> l == Long.MIN_VALUE ? BigInteger.valueOf(l).negate() : Math.abs(l);
      case BigInteger b -> normalizeInt(b.abs());
      case Double d -> Math.abs(d);
      default -> throw new PyTypeError("bad operand type for abs(): '" + typeName(value) + "'");
    };
  }

  @SuppressWarnings("unused")
  public static Object _bin_(Object value) {
    return formatInteger(value, 2, "0b");
  }

  @SuppressWarnings("unused")
  public static Object _hex_(Object value) {
    return formatInteger(value, 16, "0x");
  }

  @SuppressWarnings("unused")
  public static Object _oct_(Object value) {
    return formatInteger(value, 8, "0o");
  }

  @SuppressWarnings("unused")
  public static Object _pow_(Object... args) {
    requireCount("pow", args.length, 2, 3);
    if (args.length == 2) {
      return ArithmeticIntrinsics.$pow$(args[0], args[1]);
    }

    if (!isInteger(args[0]) || !isInteger(args[1]) || !isInteger(args[2])) {
      throw new PyTypeError("pow() 3rd argument not allowed unless all arguments are integers");
    }
    var base = asBigInteger(args[0]);
    var exponent = asBigInteger(args[1]);
    var modulus = asBigInteger(args[2]);
    if (modulus.signum() == 0) {
      throw new PyValueError("pow() 3rd argument cannot be 0");
    }

    var positiveModulus = modulus.abs();
    try {
      var result = exponent.signum() < 0
          ? base.modInverse(positiveModulus).modPow(exponent.negate(), positiveModulus)
          : base.modPow(exponent, positiveModulus);
      if (modulus.signum() < 0 && result.signum() != 0) {
        result = result.subtract(positiveModulus);
      }
      return normalizeInt(result);
    } catch (ArithmeticException e) {
      throw new PyValueError("base is not invertible for the given modulus");
    }
  }

  @SuppressWarnings("unused")
  public static Object _round_(Object... args) {
    requireCount("round", args.length, 1, 2);
    var value = args[0];
    var hasNdigits = args.length == 2 && args[1] != PyNone.INSTANCE;
    if (!hasNdigits) {
      return roundWithoutNdigits(value);
    }
    var ndigits = asIndex(args[1]);
    return switch (value) {
      case Boolean _, Long _, BigInteger _ -> roundInteger(asBigInteger(value), ndigits);
      case Double d -> roundFloat(d, ndigits);
      default -> throw new PyTypeError("type " + typeName(value) + " doesn't define __round__ method");
    };
  }

  /// A bytearray is represented by PyList<long> for now. Each element is an unsigned
  /// byte in the range 0..255; this keeps it iterable and mutable with the current runtime.</long>
  @SuppressWarnings("unused")
  public static Object _bytearray_(Object... args) {
    if (args.length > 3) {
      throw new PyTypeError("bytearray() takes at most 3 arguments (" + args.length + " given)");
    }
    if (args.length == 0) {
      return new PyList<Long>();
    }

    var source = args[0];
    if (source instanceof String string) {
      if (args.length < 2) {
        throw new PyTypeError("string argument without an encoding");
      }
      if (!(args[1] instanceof String encoding)) {
        throw new PyTypeError("bytearray() argument 'encoding' must be str, not " + typeName(args[1]));
      }
      if (args.length == 3 && !(args[2] instanceof String)) {
        throw new PyTypeError("bytearray() argument 'errors' must be str, not " + typeName(args[2]));
      }
      if (args.length == 3 && !args[2].equals("strict")) {
        throw new PyValueError("only the 'strict' error handler is supported by bytearray()");
      }
      try {
        var encoder = Charset.forName(encoding).newEncoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT);
        var buffer = encoder.encode(CharBuffer.wrap(string));
        var bytes = new byte[buffer.remaining()];
        buffer.get(bytes);
        return bytesToList(bytes);
      } catch (IllegalCharsetNameException | UnsupportedCharsetException e) {
        throw new PyValueError("unknown encoding: " + encoding);
      } catch (CharacterCodingException e) {
        throw new PyValueError("bytearray() encoding failed for encoding '" + encoding + "'", e);
      }
    }

    if (args.length > 1) {
      throw new PyTypeError("encoding or errors without a string argument");
    }
    if (isInteger(source)) {
      var size = asIndex(source);
      if (size.signum() < 0) {
        throw new PyValueError("negative count");
      }
      if (size.compareTo(MAX_ARRAY_SIZE) > 0) {
        throw new UnimplementedFeatureError("bytearray larger than the maximum Java array size");
      }
      return new PyList<Long>(size.intValue());
    }

    var result = new PyList<Long>();
    var iterator = IteratorIntrinsics.$iterator$(source);
    Object item;
    while ((item = IteratorIntrinsics.$next$(iterator)) != null) {
      var value = asIndex(item);
      if (value.signum() < 0 || value.compareTo(BigInteger.valueOf(255)) > 0) {
        throw new PyValueError("byte must be in range(0, 256)");
      }
      result.append(value.longValue());
    }
    return result;
  }

  @SuppressWarnings("unused")
  public static Object _ord_(Object value) {
    if (!(value instanceof String string)) {
      throw new PyTypeError("ord() expected string of length 1, but " + typeName(value) + " found");
    }
    var length = string.codePointCount(0, string.length());
    if (length != 1) {
      throw new PyTypeError("ord() expected a character, but string of length " + length + " found");
    }
    return (long) string.codePointAt(0);
  }

  @SuppressWarnings("unused")
  public static Object _dict_(Object... args) {
    requireCount("dict", args.length, 0, 1);
    var result = new PyDict<>();
    if (args.length == 0) {
      return result;
    }

    var source = args[0];
    if (source instanceof PyDict<?,?> dictionary) {
      for (var i = 0; i < dictionary.entryCount(); i++) {
        if (dictionary.isLive(i)) {
          result.put(dictionary.keyAt(i), dictionary.valueAt(i));
        }
      }
      return result;
    }

    var iterator = IteratorIntrinsics.$iterator$(source);
    Object element;
    var index = 0;
    while ((element = IteratorIntrinsics.$next$(iterator)) != null) {
      var size = pairSize(element);
      if (size < 0) {
        throw new PyTypeError("cannot convert dictionary update sequence element #" + index + " to a sequence");
      }
      if (size != 2) {
        throw new PyValueError("dictionary update sequence element #" + index
            + " has length " + size + "; 2 is required");
      }
      result.put(pairAt(element, 0), pairAt(element, 1));
      index++;
    }
    return result;
  }

  /*@SuppressWarnings("unused")
  public static Object _int_(Object... args) {
    requireCount("int", args.length, 0, 2);
    if (args.length == 0) {
      return 0L;
    }
    if (args.length == 2) {
      if (!(args[0] instanceof String string)) {
        throw new PyTypeError("int() can't convert non-string with explicit base");
      }
      var base = asIndex(args[1]);
      if (base.compareTo(BigInteger.ZERO) < 0 || base.compareTo(BigInteger.valueOf(36)) > 0
          || base.equals(BigInteger.ONE)) {
        throw new PyValueError("int() base must be >= 2 and <= 36, or 0");
      }
      return parseInteger(string, base.intValue());
    }

    var value = args[0];
    return switch (value) {
      case Boolean b -> b ? 1L : 0L;
      case Long l -> l;
      case BigInteger b -> normalizeInt(b);
      case Double d -> {
        if (!Double.isFinite(d)) {
          throw new PyOverflowError(Double.isNaN(d)
              ? "cannot convert float NaN to integer"
              : "cannot convert float infinity to integer");
        }
        yield normalizeInt(new BigDecimal(d).toBigInteger());
      }
      case String string -> parseInteger(string, 10);
      default -> throw new PyTypeError("int() argument must be a string, a bytes-like object or a real number, not '"
          + typeName(value) + "'");
    };
  }*/

  @SuppressWarnings("unused")
  public static Object _divmod_(Object a, Object b) {
    if (!isNumeric(a) || !isNumeric(b)) {
      throw new PyTypeError(
          "unsupported operand type(s) for divmod(): '"
              + IntrinsicSupport.typeName(a) + "' and '"
              + IntrinsicSupport.typeName(b) + "'");
    }

    if (isZero(b)) {
      var message = a instanceof Double || b instanceof Double
          ? "float divmod()"
          : "integer division or modulo by zero";
      throw new PyZeroDivisionError(message);
    }

    var quotient = ArithmeticIntrinsics.$floorDiv$(a, b);
    var remainder = ArithmeticIntrinsics.$mod$(a, b);

    return new PyTuple.PyTuple2(quotient, remainder);
  }


  // -- Helpers

  private static boolean isNumeric(Object value) {
    return value instanceof Boolean ||
        value instanceof Long ||
        value instanceof BigInteger ||
        value instanceof Double;
  }

  private static boolean isZero(Object value) {
    return switch (value) {
      case Boolean b -> !b;
      case Long l -> l == 0L;
      case BigInteger b -> b.signum() == 0;
      case Double d -> d == 0.0;
      default -> false;
    };
  }

  private static Object formatInteger(Object value, int radix, String prefix) {
    if (!isInteger(value)) {
      throw new PyTypeError("'" + typeName(value) + "' object cannot be interpreted as an integer");
    }
    var integer = asBigInteger(value);
    var sign = integer.signum() < 0 ? "-" : "";
    return sign + prefix + integer.abs().toString(radix);
  }

  private static void requireCount(String name, int count, int minimum, int maximum) {
    if (count < minimum || count > maximum) {
      if (minimum == maximum) {
        throw new PyTypeError(name + "() takes " + minimum + " argument"
            + (minimum == 1 ? "" : "s") + " (" + count + " given)");
      }
      throw new PyTypeError(name + " expected " + (minimum == 0 ? "at most " : "from ")
          + (minimum == 0 ? maximum : minimum + " to " + maximum)
          + " argument" + (maximum == 1 ? "" : "s") + ", got " + count);
    }
  }

  private static boolean isInteger(Object value) {
    return value instanceof Boolean || value instanceof Long || value instanceof BigInteger;
  }

  private static BigInteger asIndex(Object value) {
    if (isInteger(value)) {
      return asBigInteger(value);
    }
    throw new PyTypeError("'" + typeName(value) + "' object cannot be interpreted as an integer");
  }

  private static BigInteger asBigInteger(Object value) {
    return switch (value) {
      case Boolean b -> b ? BigInteger.ONE : BigInteger.ZERO;
      case Long l -> BigInteger.valueOf(l);
      case BigInteger b -> b;
      default -> throw new AssertionError("not an integer: " + value);
    };
  }

  private static Object normalizeInt(BigInteger value) {
    return value.bitLength() < 64 ? value.longValue() : value;
  }

  private static PyList<Long> bytesToList(byte[] bytes) {
    var result = new PyList<Long>(bytes.length);
    for (var value : bytes) {
      result.append((long) Byte.toUnsignedInt(value));
    }
    return result;
  }

  private static Object roundWithoutNdigits(Object value) {
    return switch (value) {
      case Boolean _, Long _, BigInteger _ -> normalizeInt(asBigInteger(value));
      case Double d -> {
        if (!Double.isFinite(d)) {
          throw new PyOverflowError(Double.isNaN(d)
              ? "cannot convert float NaN to integer"
              : "cannot convert float infinity to integer");
        }
        var rounded = Math.rint(d);
        yield normalizeInt(new BigDecimal(rounded).toBigIntegerExact());
      }
      default -> throw new PyTypeError("type " + typeName(value) + " doesn't define __round__ method");
    };
  }

  private static Object roundInteger(BigInteger value, BigInteger ndigits) {
    if (ndigits.signum() >= 0 || value.signum() == 0) {
      return normalizeInt(value);
    }
    var places = ndigits.negate();
    var decimalDigits = value.abs().toString().length();
    if (places.compareTo(BigInteger.valueOf(decimalDigits)) > 0) {
      return 0L;
    }
    var divisor = BigInteger.TEN.pow(places.intValueExact());
    var parts = value.abs().divideAndRemainder(divisor);
    var comparison = parts[1].shiftLeft(1).compareTo(divisor);
    var quotient = parts[0];
    if (comparison > 0 || comparison == 0 && quotient.testBit(0)) {
      quotient = quotient.add(BigInteger.ONE);
    }
    var rounded = quotient.multiply(divisor);
    return normalizeInt(value.signum() < 0 ? rounded.negate() : rounded);
  }

  private static Object roundFloat(double value, BigInteger ndigits) {
    if (!Double.isFinite(value)) {
      return value;
    }
    if (ndigits.compareTo(BigInteger.valueOf(324)) >= 0) {
      return value;
    }
    if (ndigits.compareTo(BigInteger.valueOf(-324)) <= 0) {
      return Double.doubleToRawLongBits(value) < 0 ? -0.0d : 0.0d;
    }
    var scale = ndigits.intValueExact();
    var rounded = new BigDecimal(value).setScale(scale, RoundingMode.HALF_EVEN).doubleValue();
    if (rounded == 0.0 && Double.doubleToRawLongBits(value) < 0) {
      return -0.0d;
    }
    return rounded;
  }



  private static int pairSize(Object pair) {
    return switch (pair) {
      case PyTuple tuple -> (int) TupleIntrinsics.$size$(tuple);
      case PyList<?> list -> list.size();
      case String string -> string.codePointCount(0, string.length());
      default -> -1;
    };
  }

  private static Object pairAt(Object pair, int index) {
    return switch (pair) {
      case PyTuple tuple -> TupleIntrinsics.$get$(tuple, index);
      case PyList<?> list -> list.get(index);
      case String string -> {
        var offset = string.offsetByCodePoints(0, index);
        var end = string.offsetByCodePoints(offset, 1);
        yield string.substring(offset, end);
      }
      default -> throw new AssertionError("not a pair sequence: " + pair);
    };
  }

  private static String typeName(Object value) {
    return IntrinsicSupport.typeName(value);
  }
}
