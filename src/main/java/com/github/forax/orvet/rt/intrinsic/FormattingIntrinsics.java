package com.github.forax.orvet.rt.intrinsic;

import com.github.forax.orvet.rt.PyDict;
import com.github.forax.orvet.rt.PyTuple;
import com.github.forax.orvet.rt.error.PyOverflowError;
import com.github.forax.orvet.rt.error.PyTypeError;
import com.github.forax.orvet.rt.error.PyValueError;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.Locale;

import static com.github.forax.orvet.rt.intrinsic.IntrinsicSupport.isInt;
import static com.github.forax.orvet.rt.intrinsic.IntrinsicSupport.toBigInteger;
import static com.github.forax.orvet.rt.intrinsic.IntrinsicSupport.toDouble;

public final class FormattingIntrinsics {
  private FormattingIntrinsics() {
    throw new UnsupportedOperationException();
  }

  // -- printf-style string formatting ('%' operator)

  /// Above that width/precision, the padding itself would not be allocatable,
  /// so fail early instead of dying with an out-of-memory error.
  private static final int MAX_FORMAT_WIDTH = Integer.MAX_VALUE - 8;

  /// Python's printf-style string formatting (the `%` operator applied to a string).
  /// Follows CPython (`PyUnicode_Format`): a format containing `%(key)` requires a
  /// dict argument (mapping mode), otherwise a tuple argument provides the values
  /// in order and any other value is a single argument. Conversions `s r a c d i u
  /// f F e E g G x X o %%` with flags `-+ 0#`, a width, a precision and `*` args.
  static Object stringMod(String format, Object arg) {
    var out = new StringBuilder(format.length() + 16);
    var cursor = new ArgCursor(format, arg);
    var length = format.length();
    var index = 0;
    while (index < length) {
      if (format.charAt(index) != '%') {
        out.append(format.charAt(index++));
        continue;
      }
      index++;
      Object mappedValue = null;  // argument of a `%(key)` conversion, null otherwise
      if (index < length && format.charAt(index) == '(') {
        // like CPython, a key may contain parentheses: count them to find the matching one
        var keyStart = ++index;
        var depth = 1;
        while (index < length) {
          var c = format.charAt(index++);
          if (c == '(') {
            depth++;
          } else if (c == ')' && --depth == 0) {
            break;
          }
        }
        if (depth != 0) {
          throw new PyValueError("incomplete format key");
        }
        mappedValue = cursor.key(format.substring(keyStart, index - 1));
      }
      var leftAlign = false;
      var plusSign = false;
      var spaceSign = false;
      var zeroPad = false;
      var altForm = false;
      flags: for(;;) {
        if (index == length) {
          throw new PyValueError("incomplete format");
        }
        switch (format.charAt(index)) {
          case '-' -> { leftAlign = true; index++; }
          case '+' -> { plusSign = true; index++; }
          case ' ' -> { spaceSign = true; index++; }
          case '0' -> { zeroPad = true; index++; }
          case '#' -> { altForm = true; index++; }
          default -> { break flags; }
        }
      }
      int width = -1;
      if (index < length && format.charAt(index) == '*') {
        index++;
        width = widthArg(cursor.next());
        if (width < 0) {
          leftAlign = true;
          width = -width;
        }
      } else {
        while (index < length && isDigit(format.charAt(index))) {
          width = accumulate(width < 0 ? 0 : width, format.charAt(index++) - '0');
        }
      }
      int precision = -1;
      if (index < length && format.charAt(index) == '.') {
        index++;
        if (index < length && format.charAt(index) == '*') {
          index++;
          precision = widthArg(cursor.next());
          if (precision < 0) {
            precision = 0;  // CPython clamps a negative star precision to zero
          }
        } else {
          precision = 0;
          while (index < length && isDigit(format.charAt(index))) {
            precision = accumulate(precision, format.charAt(index++) - '0');
          }
        }
      }
      // C length modifiers, parsed but ignored
      while (index < length && (format.charAt(index) == 'l' || format.charAt(index) == 'h')) {
        index++;
      }
      if (index == length) {
        throw new PyValueError("incomplete format");
      }
      var conversionIndex = index;
      var conversion = format.charAt(index++);
      var spec = new FormatSpec(width, precision, leftAlign, plusSign, spaceSign, zeroPad, altForm);
      switch (conversion) {
        case '%' -> {
          // A literal percent sign: CPython only accepts it without flags, width or
          // precision, and still consumes an argument before rejecting the conversion.
          if (leftAlign || plusSign || spaceSign || zeroPad || altForm || width != -1 || precision != -1 || mappedValue != null) {
            cursor.next();
            throw new PyValueError("unsupported format character '%' (0x25) at index " + conversionIndex);
          }
          out.append('%');
        }
        case 's', 'r', 'a' -> {
          var value = mappedValue != null ? mappedValue : cursor.next();
          var text = switch (conversion) {
            case 's' -> StringIntrinsics.$str$(value);
            case 'r' -> StringIntrinsics.$repr$(value);
            default -> StringIntrinsics.ascii(value);
          };
          appendString(out, spec, truncate(text, precision));
        }
        case 'c' -> appendChar(out, spec, mappedValue != null ? mappedValue : cursor.next());
        case 'd', 'i', 'u' -> appendInteger(out, spec, mappedValue != null ? mappedValue : cursor.next(), 'd', 10, false);
        case 'x', 'X', 'o' -> appendInteger(out, spec, mappedValue != null ? mappedValue : cursor.next(),
            conversion, conversion == 'o' ? 8 : 16, conversion == 'X');
        case 'e', 'E', 'f', 'F', 'g', 'G' -> appendFloat(out, spec, mappedValue != null ? mappedValue : cursor.next(), conversion);
        default -> throw new PyValueError(
            "unsupported format character '" + conversion + "' (0x"
                + Integer.toHexString(conversion) + ") at index " + conversionIndex);
      }
    }
    cursor.checkAllConsumed();
    return out.toString();
  }

  private static boolean isDigit(char c) {
    return c >= '0' && c <= '9';
  }

  private static int accumulate(long value, int digit) {
    var result = value * 10 + digit;
    if (result > MAX_FORMAT_WIDTH) {
      throw new PyOverflowError("width or precision too large");
    }
    return (int) result;
  }

  /// The integer argument of a `*` width or precision.
  private static int widthArg(Object value) {
    if (!isInt(value)) {
      throw new PyTypeError("* wants int");
    }
    var big = toBigInteger(value);
    if (big.bitLength() > 31) {
      throw new PyOverflowError("width or precision too large");
    }
    return big.intValue();
  }

  /// A `%(key)` conversion: find a parenthesized mapping key, skipping `%%` escapes.
  private static boolean hasMappingKey(String format) {
    for (var index = format.indexOf('%'); index != -1 && index + 1 < format.length(); index = format.indexOf('%', index + 1)) {
      var next = format.charAt(index + 1);
      if (next == '%') {
        index++;
      } else if (next == '(') {
        return true;
      }
    }
    return false;
  }

  private record FormatSpec(
      int width,
      int precision,
      boolean leftAlign,
      boolean plusSign,
      boolean spaceSign,
      boolean zeroPad,
      boolean altForm) {
  }

  private static String sign(boolean negative, FormatSpec spec) {
    if (negative) {
      return "-";
    }
    if (spec.plusSign()) {
      return "+";
    }
    if (spec.spaceSign()) {
      return " ";
    }
    return "";
  }

  private static void appendNumber(StringBuilder out, FormatSpec spec, String prefix, String body) {
    var total = prefix.length() + body.length();
    if (spec.width() <= total) {
      out.append(prefix).append(body);
      return;
    }
    if (spec.zeroPad() && !spec.leftAlign()) {
      out.append(prefix).append("0".repeat(spec.width() - total)).append(body);
    } else if (spec.leftAlign()) {
      out.append(prefix).append(body).append(" ".repeat(spec.width() - total));
    } else {
      out.append(" ".repeat(spec.width() - total)).append(prefix).append(body);
    }
  }

  private static void appendString(StringBuilder out, FormatSpec spec, String text) {
    var count = text.codePointCount(0, text.length());
    if (spec.width() <= count) {
      out.append(text);
    } else if (spec.leftAlign()) {
      out.append(text).append(" ".repeat(spec.width() - count));
    } else {
      out.append(" ".repeat(spec.width() - count)).append(text);
    }
  }

  private static String truncate(String text, int precision) {
    if (precision == -1 || text.codePointCount(0, text.length()) <= precision) {
      return text;
    }
    return text.substring(0, text.offsetByCodePoints(0, precision));
  }

  private static void appendChar(StringBuilder out, FormatSpec spec, Object value) {
    var text = switch (value) {
      case Long l -> charFromCodePoint(l);
      case Boolean b -> charFromCodePoint(b ? 1L : 0L);
      case BigInteger b -> charFromCodePoint(b);
      case String s -> {
        if (s.codePointCount(0, s.length()) != 1) {
          throw new PyTypeError("%c requires int or char");
        }
        yield s;
      }
      default -> throw new PyTypeError("%c requires int or char");
    };
    appendString(out, spec, text);
  }

  private static String charFromCodePoint(long code) {
    if (code < 0 || code >= 0x110000) {
      throw new PyOverflowError("%c arg not in range(0x110000)");
    }
    return String.valueOf(Character.toChars((int) code));
  }

  private static String charFromCodePoint(BigInteger code) {
    if (code.signum() < 0 || code.compareTo(BigInteger.valueOf(0x110000)) >= 0) {
      throw new PyOverflowError("%c arg not in range(0x110000)");
    }
    return String.valueOf(Character.toChars(code.intValueExact()));
  }

  private static void appendInteger(StringBuilder out, FormatSpec spec, Object value, char conversion, int radix, boolean uppercase) {
    var number = conversion == 'd'
        ? toIntegerArg(value, conversion)
        : toExactIntegerArg(value, conversion);
    var digits = number.abs().toString(radix);
    if (uppercase) {
      digits = digits.toUpperCase(Locale.ROOT);
    }
    if (digits.length() < spec.precision()) {
      digits = "0".repeat(spec.precision() - digits.length()) + digits;
    }
    var prefix = sign(number.signum() < 0, spec);
    if (spec.altForm()) {
      if (radix == 16) {
        prefix += uppercase ? "0X" : "0x";
      } else if (radix == 8) {
        prefix += "0o";
      }
    }
    appendNumber(out, spec, prefix, digits);
  }

  /// Integer conversion for `%d`, `%i`, `%u`: floats are truncated towards zero.
  private static BigInteger toIntegerArg(Object value, char conversion) {
    return switch (value) {
      case Long l -> BigInteger.valueOf(l);
      case Boolean b -> b ? BigInteger.ONE : BigInteger.ZERO;
      case BigInteger b -> b;
      case Double d -> {
        if (Double.isNaN(d)) {
          throw new PyValueError("cannot convert float NaN to integer");
        }
        if (Double.isInfinite(d)) {
          throw new PyOverflowError("cannot convert float infinity to integer");
        }
        yield new BigDecimal(d).toBigInteger();
      }
      default -> throw new PyTypeError(
          "%" + conversion + " format: a real number is required, not " + IntrinsicSupport.typeName(value));
    };
  }

  /// Integer conversion for `%x`, `%X`, `%o`: floats are not accepted.
  private static BigInteger toExactIntegerArg(Object value, char conversion) {
    return switch (value) {
      case Long l -> BigInteger.valueOf(l);
      case Boolean b -> b ? BigInteger.ONE : BigInteger.ZERO;
      case BigInteger b -> b;
      default -> throw new PyTypeError(
          "%" + conversion + " format: an integer is required, not " + IntrinsicSupport.typeName(value));
    };
  }

  private static void appendFloat(StringBuilder out, FormatSpec spec, Object value, char conversion) {
    var d = toDoubleArg(value);
    var upper = conversion == 'E' || conversion == 'F' || conversion == 'G';
    String prefix;
    String body;
    if (Double.isNaN(d)) {
      prefix = sign(false, spec);
      body = upper ? "NAN" : "nan";
    } else if (Double.isInfinite(d)) {
      prefix = sign(d < 0, spec);
      body = upper ? "INF" : "inf";
    } else {
      prefix = sign(d < 0.0 || (d == 0.0 && 1.0 / d < 0.0), spec);
      body = formatDouble(new BigDecimal(Math.abs(d)), spec.precision(), conversion, spec.altForm());
      if (upper) {
        body = body.toUpperCase(Locale.ROOT);
      }
    }
    appendNumber(out, spec, prefix, body);
  }

  private static double toDoubleArg(Object value) {
    return switch (value) {
      case Double d -> d;
      case Long l -> toDouble(l);
      case Boolean b -> toDouble(b);
      case BigInteger b -> toDouble(b);
      default -> throw new PyTypeError("must be real number, not " + IntrinsicSupport.typeName(value));
    };
  }

  private static String formatDouble(BigDecimal value, int precision, char conversion, boolean altForm) {
    var decimals = precision == -1 ? 6 : precision;
    return switch (conversion) {
      case 'f', 'F' -> {
        var fixed = value.setScale(decimals, RoundingMode.HALF_EVEN).toPlainString();
        yield decimals == 0 && altForm ? fixed + '.' : fixed;
      }
      case 'e', 'E' -> formatScientific(value, decimals, false, altForm);
      default -> formatGeneral(value, decimals, altForm);  // g/G
    };
  }

  /// Fixed notation with `digits` decimals, rounded half-to-even like CPython.
  private static String formatGeneral(BigDecimal value, int significantDigits, boolean altForm) {
    var sig = significantDigits == 0 ? 1 : significantDigits;
    if (value.signum() == 0) {
      return altForm ? "0." + "0".repeat(sig - 1) : "0";
    }
    var sci = value.round(new MathContext(sig, RoundingMode.HALF_EVEN));
    var exp = sci.precision() - sci.scale() - 1;
    if (exp >= -4 && exp < sig) {
      var fixed = value.setScale(sig - 1 - exp, RoundingMode.HALF_EVEN).toPlainString();
      if (altForm) {
        return fixed.indexOf('.') == -1 ? fixed + '.' : fixed;
      }
      return stripFractionZeros(fixed);
    }
    return formatScientific(value, sig - 1, !altForm, altForm);
  }

  /// Scientific notation with `decimals` digits after the point, exponent with at
  /// least two digits, trailing zeros stripped when requested (`%g`).
  private static String formatScientific(BigDecimal value, int decimals, boolean stripZeros, boolean altForm) {
    var sig = decimals + 1;
    var sci = value.round(new MathContext(sig, RoundingMode.HALF_EVEN));
    var exp = sci.precision() - sci.scale() - 1;
    var digits = sci.unscaledValue().toString();
    if (digits.length() < sig) {
      digits += "0".repeat(sig - digits.length());
    }
    var end = digits.length();
    if (stripZeros) {
      while (end > 1 && digits.charAt(end - 1) == '0') {
        end--;
      }
    }
    var mantissa = new StringBuilder(end + 5);
    mantissa.append(digits.charAt(0));
    if (end > 1) {
      mantissa.append('.').append(digits, 1, end);
    } else if (altForm) {
      mantissa.append('.');  // '#' keeps the decimal point with a zero precision
    }
    return mantissa
        .append('e')
        .append(exp < 0 ? '-' : '+')
        .append(Math.abs(exp) < 10 ? "0" : "")
        .append(Math.abs(exp))
        .toString();
  }

  private static String stripFractionZeros(String value) {
    var point = value.indexOf('.');
    if (point == -1) {
      return value;
    }
    var end = value.length();
    while (end > point && value.charAt(end - 1) == '0') {
      end--;
    }
    if (end == point + 1) {
      end--;  // also drop the decimal point
    }
    return value.substring(0, end);
  }

  /// Sequential access to the values of a `%` format: a dict for `%(key)` formats,
  /// otherwise a tuple or a single value.
  private static final class ArgCursor {
    private final PyDict<?, ?> dict;
    private final PyTuple tuple;
    private final Object single;
    private int index;
    private boolean singleUsed;

    ArgCursor(String format, Object arg) {
      var mapping = hasMappingKey(format);
      this.dict = mapping && arg instanceof PyDict<?,?> d ? d : null;
      this.tuple = !mapping && arg instanceof PyTuple t ? t : null;
      this.single = !mapping && this.tuple == null ? arg : null;
    }

    Object next() {
      if (dict != null) {
        // a mapping only supports `%(key)` conversions
        throw new PyTypeError("not enough arguments for format string");
      }
      if (tuple != null) {
        if (index >= tuple.size()) {
          throw new PyTypeError("not enough arguments for format string");
        }
        return TupleIntrinsics.$get$(tuple, index++);
      }
      if (singleUsed) {
        throw new PyTypeError("not enough arguments for format string");
      }
      singleUsed = true;
      return single;
    }

    Object key(String key) {
      if (dict == null) {
        throw new PyTypeError("format requires a mapping");
      }
      return DictIntrinsics.$get$(dict, key);
    }

    void checkAllConsumed() {
      if (tuple != null && index < tuple.size()
          || tuple == null && dict == null && !singleUsed) {
        throw new PyTypeError("not all arguments converted during string formatting");
      }
    }
  }
}
