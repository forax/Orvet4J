package com.github.forax.orvet.rt.intrinsic;

import com.github.forax.orvet.rt.PyDict;
import com.github.forax.orvet.rt.PyFunction;
import com.github.forax.orvet.rt.PyList;
import com.github.forax.orvet.rt.PyNone;
import com.github.forax.orvet.rt.PyRange;
import com.github.forax.orvet.rt.PySlice;
import com.github.forax.orvet.rt.PyTuple;
import com.github.forax.orvet.rt.PyType;
import com.github.forax.orvet.rt.error.PyOverflowError;
import com.github.forax.orvet.rt.error.PyTypeError;
import com.github.forax.orvet.rt.error.PyValueError;

import java.math.BigDecimal;
import java.math.BigInteger;

import static com.github.forax.orvet.rt.intrinsic.IntrinsicSupport.typeName;

public final class TypeIntrinsics {
  private TypeIntrinsics() { throw new AssertionError(); }

  @Intrinsic
  public static Object $type$(Object value) {
    switch (value) {
      case Long _, BigInteger _ -> { return PyType.INT; }
      case Boolean _ -> { return PyType.BOOL; }
      case Double _ -> { return PyType.FLOAT; }
      case String _ -> { return PyType.STR; }
      case PyNone _ -> { return PyType.NONE_TYPE; }
      case PyTuple _ -> { return PyType.TUPLE; }
      case PyList<?> _ -> { return PyType.LIST; }
      case PyDict<?, ?> _ -> { return PyType.DICT; }
      case PyRange _ -> { return PyType.RANGE; }
      case PySlice _ -> { return PyType.SLICE; }
      case PyFunction f -> { return PyType.FUNCTION; }
      default -> throw new NotCanonicalException("not canonical", value);
    }
  }

  @Intrinsic
  public static Object $bool$(Object... args) {
    return switch (args.length) {
      case 0 -> false;
      case 1 -> TruthinessIntrinsics.$truthy$(args[0]);
      default -> throw new PyTypeError("bool() expected at most 1 argument, got " + args.length);
    };
  }

  @Intrinsic
  public static Object $int$(Object... args) {
    switch (args.length) {
      case 0 -> { return 0L; }
      case 1 -> { return $int_oneArg$(args[0]); }
      case 2 -> { return int_twoArg(args[0], args[1]); }
      default -> throw new PyTypeError("int() expected at most 2 arguments, got " + args.length);
    }
  }

  @Intrinsic
  public static Object $int_oneArg$(Object value) {
    switch (value) {
      case Boolean b -> { return b ? 1L : 0L; }
      case Long l -> { return l; }
      case BigInteger b -> { return normalizeInt(b); }
      case Double d -> {
        if (!Double.isFinite(d)) {
          if (Double.isNaN(d)) {
            throw new PyValueError("cannot convert float NaN to integer");
          }
          throw new PyOverflowError("cannot convert float infinity to integer");
        }
        return normalizeInt(new BigDecimal(d).toBigInteger());
      }
      case String string -> { return parseInteger(string, 10); }
      default -> throw new PyTypeError("int() argument must be a string, a bytes-like object or a real number, not '"
          + typeName(value) + "'");
    }
  }

  private static Object int_twoArg(Object value, Object index) {
    switch (value) {
      case String string -> {
        var base = asBaseIndex(index);
        if (base.compareTo(BigInteger.ZERO) < 0 || base.compareTo(BigInteger.valueOf(36)) > 0
            || base.equals(BigInteger.ONE)) {
          throw new PyValueError("int() base must be >= 2 and <= 36, or 0");
        }
        return parseInteger(string, base.intValue());
      }
      default -> throw new PyTypeError("int() can't convert non-string with explicit base");
    }
  }

  private static boolean isInteger(Object value) {
    return value instanceof Boolean || value instanceof Long || value instanceof BigInteger;
  }

  private static BigInteger asBaseIndex(Object value) {
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

  private static Object parseInteger(String original, int requestedBase) {
    var text = stripWhitespace(original);
    if (text.isEmpty()) {
      throw invalidInteger(original, requestedBase);
    }

    var sign = "";
    if (text.charAt(0) == '+' || text.charAt(0) == '-') {
      sign = text.substring(0, 1);
      text = text.substring(1);
    }
    if (text.isEmpty()) {
      throw invalidInteger(original, requestedBase);
    }

    var base = requestedBase;
    var strippedPrefix = false;
    if (text.length() >= 2 && text.charAt(0) == '0') {
      var prefixBase = switch (text.charAt(1)) {
        case 'b', 'B' -> 2;
        case 'o', 'O' -> 8;
        case 'x', 'X' -> 16;
        default -> 0;
      };
      if (prefixBase != 0 && (base == 0 || base == prefixBase)) {
        base = prefixBase;
        text = text.substring(2);
        strippedPrefix = true;
      }
    }
    if (base == 0) {
      base = 10;
    }
    if (strippedPrefix && text.startsWith("_")) {
      text = text.substring(1);  // one underscore immediately after a base prefix is permitted
    }
    if (text.isEmpty()) {
      throw invalidInteger(original, requestedBase);
    }

    var digits = new StringBuilder(text.length());
    boolean previousWasDigit = false;
    for (var i = 0; i < text.length();) {
      var cp = text.codePointAt(i);
      i += Character.charCount(cp);
      if (cp == '_') {
        if (!previousWasDigit || i == text.length()
            || digit(text.codePointAt(i), base) < 0) {
          throw invalidInteger(original, requestedBase);
        }
        previousWasDigit = false;
        continue;
      }
      var digit = digit(cp, base);
      if (digit < 0) {
        throw invalidInteger(original, requestedBase);
      }
      digits.append(Character.forDigit(digit, base));
      previousWasDigit = true;
    }
    if (digits.isEmpty() || !previousWasDigit) {
      throw invalidInteger(original, requestedBase);
    }

    if (requestedBase == 0 && base == 10 && digits.length() > 1
        && digits.charAt(0) == '0' && !digits.toString().matches("0+")) {
      throw invalidInteger(original, requestedBase);
    }
    try {
      return normalizeInt(new BigInteger(sign + digits, base));
    } catch (NumberFormatException e) {
      throw invalidInteger(original, requestedBase);
    }
  }

  private static int digit(int cp, int base) {
    if (cp >= '0' && cp <= '9') {
      var value = cp - '0';
      return value < base ? value : -1;
    }
    if (cp >= 'a' && cp <= 'z') {
      var value = cp - 'a' + 10;
      return value < base ? value : -1;
    }
    if (cp >= 'A' && cp <= 'Z') {
      var value = cp - 'A' + 10;
      return value < base ? value : -1;
    }
    if (Character.getType(cp) == Character.DECIMAL_DIGIT_NUMBER) {
      var value = Character.digit(cp, 10);
      return value >= 0 && value < base ? value : -1;
    }
    return -1;
  }

  private static String stripWhitespace(String value) {
    var start = 0;
    var end = value.length();
    while (start < end) {
      var cp = value.codePointAt(start);
      if (!Character.isWhitespace(cp) && !Character.isSpaceChar(cp)) {
        break;
      }
      start += Character.charCount(cp);
    }
    while (start < end) {
      var cp = value.codePointBefore(end);
      if (!Character.isWhitespace(cp) && !Character.isSpaceChar(cp)) {
        break;
      }
      end -= Character.charCount(cp);
    }
    return value.substring(start, end);
  }

  private static PyValueError invalidInteger(String value, int base) {
    return new PyValueError("invalid literal for int() with base " + base + ": "
        + StringIntrinsics.$repr$(value));
  }
}