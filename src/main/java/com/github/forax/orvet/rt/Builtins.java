package com.github.forax.orvet.rt;

import com.github.forax.orvet.rt.PyFunction.Code;
import com.github.forax.orvet.rt.error.PyOverflowError;
import com.github.forax.orvet.rt.error.PyTypeError;
import com.github.forax.orvet.rt.error.PyValueError;
import com.github.forax.orvet.rt.error.PyZeroDivisionError;
import com.github.forax.orvet.rt.error.UnimplementedFeatureError;
import com.github.forax.orvet.rt.intrinsic.ArithmeticIntrinsics;
import com.github.forax.orvet.rt.intrinsic.ComparisonIntrinsics;
import com.github.forax.orvet.rt.intrinsic.IntrinsicSupport;
import com.github.forax.orvet.rt.intrinsic.ItemIntrinsics;
import com.github.forax.orvet.rt.intrinsic.IteratorIntrinsics;
import com.github.forax.orvet.rt.intrinsic.RangeIntrinsics;
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
  // There is a circular dependency between Builtins and PyTuple.
  // PyTuple function (constructor) are initialized from builtin codes.
  // Builtin PyTypes need to be stored in the BUILTINS MAP.
  // This class solved that issue by being the source of truth for both
  // Builtins and PyTuple.
  // While this class is syntactically declared inside Builtins,
  // it should *never* access fields/methods from Builtins
  static final class CodeInitialization {
    private static final Object[] NO_CAPTURE_ARGS = new Object[0];
    static final HashMap<String, PyFunction> CONSTRUCTOR_FUNCTIONS;
    static final Code[] BUILTINS_CODES;
    static {
      var constructorCodes = new Code[]{
          specializedVariadic("bool", TypeIntrinsics.class),
          specializedVariadic("int", TypeIntrinsics.class),
          specializedVariadic("float", TypeIntrinsics.class),
          specialized("str", StringIntrinsics.class, "object"),
          variadic("tuple"),
          variadic("list"),
          variadic("dict"),
          variadic("range"),
          variadic("slice"),
          fixed("NoneType"),
          fixed("function")
      };
      var constructorFunctions = new HashMap<String, PyFunction>();
      for (var code : constructorCodes) {
        constructorFunctions.put(code.name(), new PyFunction(code, NO_CAPTURE_ARGS));
      }
      CONSTRUCTOR_FUNCTIONS = constructorFunctions;

      BUILTINS_CODES = new Code[]{
          fixed("abs", "x"),
          fixed("all", "iterable"),
          fixed("any", "iterable"),
          fixed("ascii", "object"),
          fixed("bin", "x"),
          fixed("callable", "object"),
          fixed("chr", "i"),
          fixed("divmod", "a", "b"),
          fixed("hash", "object"),
          fixed("hex", "x"),
          fixed("isinstance", "obj", "classinfo"),
          fixed("issubclass", "cls", "classinfo"),
          specialized("len", ItemIntrinsics.class, "object"),
          variadic("max"),
          variadic("min"),
          fixed("oct", "x"),
          fixed("ord", "c"),
          variadic("pow"),
          variadic("print"),
          specialized("repr", StringIntrinsics.class, "object"),
          variadic("range"),
          variadic("round"),
          variadic("sum"),
          specialized("type", TypeIntrinsics.class, "object"),

          // -- Not yet implemented --

          // Asynchronous and iterator-related
          variadic("aiter"),
          variadic("anext"),
          variadic("enumerate"),
          variadic("filter"),
          variadic("iter"),
          variadic("map"),
          variadic("next"),
          variadic("reversed"),
          variadic("zip"),

          // Types
          variadic("bytearray"),
          variadic("bytes"),
          variadic("complex"),
          variadic("function"),
          variadic("frozenset"),
          variadic("frozendict"),
          variadic("memoryview"),
          variadic("object"),
          variadic("set"),

          // Class and descriptor mechanisms
          variadic("classmethod"),
          variadic("property"),
          variadic("staticmethod"),
          variadic("super"),

          // Attribute and introspection
          variadic("delattr"),
          variadic("dir"),
          variadic("getattr"),
          variadic("globals"),
          variadic("hasattr"),
          variadic("locals"),
          variadic("setattr"),
          variadic("vars"),

          // Execution, I/O and other
          variadic("breakpoint"),
          variadic("compile"),
          variadic("eval"),
          variadic("exec"),
          variadic("format"),
          variadic("help"),
          variadic("id"),
          variadic("input"),
          variadic("open"),
          variadic("__import__"),
          variadic("sentinel")
      };
    }

    private static Code fixed(String name, String... parameters) {
      return new Code(name, List.of(parameters), false, 0, null, null);
    }

    private static Code variadic(String name) {
      return new Code(name, List.of(), true, 0, null, null);
    }

    private static Code specialized(String name, Class<?> intrinsicClass, String... parameters) {
      return new Code(name, List.of(parameters), false, 0, new PyFunction.SpecializationContext(intrinsicClass), null);
    }

    private static Code specializedVariadic(String name, Class<?> intrinsicClass) {
      return new Code(name, List.of(), true, 0, new PyFunction.SpecializationContext(intrinsicClass), null);
    }
  }

  private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();
  static final HashMap<String, Object> BUILTINS;
  static {
    var builtins = new HashMap<String, Object>();
    var types = PyType.ALL;  // force initialization
    for(var type : types) {
      switch (type) {
        case NONE_TYPE, FUNCTION -> { continue; }  // those are not builtins
      }
      builtins.put(type.function().name(), type);
    }
    for(var code : CodeInitialization.BUILTINS_CODES) {
      builtins.put(code.name(), new PyFunction(code, CodeInitialization.NO_CAPTURE_ARGS));
    }
    BUILTINS = builtins;
  }

  private static final BigInteger MAX_ARRAY_SIZE = BigInteger.valueOf(Integer.MAX_VALUE - 8L);


  /// The entry of a builtin: always a fixed-arity handle. For a variadic builtin the last parameter
  /// is an Object[] that call sites fill explicitly (never through the varargs-collector bit).
  static MethodHandle resolve(Code code) {
    var name = code.name();
    var mh = switch (code.specializationContext()) {
      case PyFunction.SpecializationContext(var intrinsicClass) ->
          SpecializationSupport.specialize("$" + name + "$", builtinMethodType(code), intrinsicClass);
      case null -> {
        try {
          yield LOOKUP.findStatic(Builtins.class, "_" +name + "_", builtinMethodType(code)).asFixedArity();
        } catch (NoSuchMethodException e) {
          throw new UnimplementedFeatureError("builtin '" + name + "' not yet implemented", e);
        } catch (IllegalAccessException e) {
          throw new AssertionError(e);
        }
      }
    };
    return MethodHandles.dropArguments(mh, 0, PyFunction.class);
  }

  private static MethodType builtinMethodType(Code code) {
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
    return switch (args.length) {
      case 1 -> RangeIntrinsics.newRange(0L, args[0], 1L);
      case 2 -> RangeIntrinsics.newRange(args[0], args[1], 1L);
      default -> RangeIntrinsics.newRange(args[0], args[1], args[2]);
    };
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

  @SuppressWarnings("unused")
  public static Object _NoneType_() {
    return PyNone.INSTANCE;
  }

  @SuppressWarnings("unused")
  public static Object _slice_(Object... a) {
    requireCount("slice", a.length, 1, 3);
    return switch (a.length) {
      case 1 -> new PySlice(PyNone.INSTANCE, a[0], PyNone.INSTANCE);
      case 2 -> new PySlice(a[0], a[1], PyNone.INSTANCE);
      default -> new PySlice(a[0], a[1], a[2]);
    };
  }

  @SuppressWarnings("unused")
  public static Object _list_(Object... args) {
    requireCount("list", args.length, 0, 1);
    if (args.length == 0) {
      return new PyList<>();
    }
    var result = new PyList<>();
    var it = IteratorIntrinsics.$iterator$(args[0]);
    Object item;
    while ((item = IteratorIntrinsics.$next$(it)) != null) {
      result.append(item);
    }
    return result;
  }

  @SuppressWarnings("unused")
  public static Object _tuple_(Object... args) {
    requireCount("tuple", args.length, 0, 1);
    if (args.length == 0) {
      return TupleIntrinsics.empty();
    }
    var arrayList = new ArrayList<>();
    var it = IteratorIntrinsics.$iterator$(args[0]);
    Object item;
    while ((item = IteratorIntrinsics.$next$(it)) != null) {
      arrayList.add(item);
    }
    return TupleIntrinsics.fromArray(arrayList.toArray());
  }

  @SuppressWarnings("unused")
  public static Object _all_(Object iterable) {
    var iterator = IteratorIntrinsics.$iterator$(iterable);
    Object item;
    while ((item = IteratorIntrinsics.$next$(iterator)) != null) {
      if (!TruthinessIntrinsics.$truthy$(item)) {
        return false;
      }
    }
    return true;
  }

  @SuppressWarnings("unused")
  public static Object _any_(Object iterable) {
    var iterator = IteratorIntrinsics.$iterator$(iterable);
    Object item;
    while ((item = IteratorIntrinsics.$next$(iterator)) != null) {
      if (TruthinessIntrinsics.$truthy$(item)) {
        return true;
      }
    }
    return false;
  }

  @SuppressWarnings("unused")
  public static Object _ascii_(Object value) {
    var repr = (String) StringIntrinsics.$repr$(value);
    var builder = new StringBuilder(repr.length());
    for (var offset = 0; offset < repr.length();) {
      var cp = repr.codePointAt(offset);
      if (cp < 128) {
        builder.appendCodePoint(cp);
      } else {
        var width = cp <= 0xFF ? 2 : cp <= 0xFFFF ? 4 : 8;
        var prefix = cp <= 0xFF ? "\\x"
            : cp <= 0xFFFF ? "\\u" : "\\U";
        var hex = Integer.toHexString(cp);
        builder.append(prefix)
            .repeat("0", width - hex.length())
            .append(hex);
      }
      offset += Character.charCount(cp);
    }
    return builder.toString();
  }

  @SuppressWarnings("unused")
  public static Object _callable_(Object value) {
    return value instanceof PyFunction || value instanceof PyType;
  }

  @SuppressWarnings("unused")
  public static Object _chr_(Object value) {
    var codePoint = asIndex(value);

    if (codePoint.signum() < 0 ||
        codePoint.compareTo(BigInteger.valueOf(Character.MAX_CODE_POINT)) > 0) {
      throw new PyValueError("chr() arg not in range(0x110000)");
    }

    return new String(Character.toChars(codePoint.intValue()));
  }

  @SuppressWarnings("unused")
  public static Object _min_(Object... args) {
    if (args.length == 0) {
      throw new PyTypeError("min expected at least 1 argument, got 0");
    }
    Object best;
    if (args.length == 1) {
      var iterator = IteratorIntrinsics.$iterator$(args[0]);
      best = IteratorIntrinsics.$next$(iterator);
      if (best == null) {
        throw new PyValueError("min() arg is an empty sequence");
      }
      Object item;
      while ((item = IteratorIntrinsics.$next$(iterator)) != null) {
        if (ComparisonIntrinsics.$lt$(item, best)) {
          best = item;
        }
      }
      return best;
    }
    best = args[0];
    for (var i = 1; i < args.length; i++) {
      var item = args[i];
      if (ComparisonIntrinsics.$lt$(item, best)) {
        best = item;
      }
    }
    return best;
  }

  @SuppressWarnings("unused")
  public static Object _max_(Object... args) {
    if (args.length == 0) {
      throw new PyTypeError("max expected at least 1 argument, got 0");
    }
    Object best;
    if (args.length == 1) {
      var iterator = IteratorIntrinsics.$iterator$(args[0]);
      best = IteratorIntrinsics.$next$(iterator);
      if (best == null) {
        throw new PyValueError("max() arg is an empty sequence");
      }
      Object item;
      while ((item = IteratorIntrinsics.$next$(iterator)) != null) {
        if (ComparisonIntrinsics.$lt$(best, item)) {
          best = item;
        }
      }
      return best;
    }
    best = args[0];
    for (var i = 1; i < args.length; i++) {
      var item = args[i];
      if (ComparisonIntrinsics.$lt$(best, item)) {
        best = item;
      }
    }
    return best;
  }

  @SuppressWarnings("unused")
  public static Object _sum_(Object... args) {
    requireCount("sum", args.length, 1, 2);
    var total = args.length == 2 ? args[1] : 0L;
    if (!isNumeric(total)) {
      throw new PyTypeError(
          "sum() can't sum " + typeName(total) + " values");
    }
    var iterator = IteratorIntrinsics.$iterator$(args[0]);
    Object item;
    while ((item = IteratorIntrinsics.$next$(iterator)) != null) {
      if (!isNumeric(item)) {
        throw new PyTypeError(
            "unsupported operand type(s) for +: '"
                + typeName(total) + "' and '" + typeName(item) + "'");
      }
      total = ArithmeticIntrinsics.$add$(total, item);
    }

    return total;
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
