package com.github.forax.orvet.rt.intrinsic;

import com.github.forax.orvet.rt.PyDict;
import com.github.forax.orvet.rt.PyTuple;
import com.github.forax.orvet.rt.error.PyOverflowError;
import com.github.forax.orvet.rt.error.PyTypeError;
import com.github.forax.orvet.rt.error.PyValueError;
import com.github.forax.orvet.rt.error.UnimplementedFeatureError;

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

  /// Largest string the JVM can allocate; anything bigger is rejected before allocating.
  private static final int MAX_STRING_LENGTH = Integer.MAX_VALUE - 8;

  /// The exact decimal expansion of a double has at most 1074 fractional digits
  /// and at most 767 significant digits, so asking for more only adds zeros.
  private static final int MAX_EXACT_FRACTION_DIGITS = 1074;
  private static final int MAX_EXACT_SIGNIFICANT_DIGITS = 800;

  // -- printf-style string formatting ('%' operator)

  /// Python's printf-style string formatting (the `%` operator applied to a string).
  /// Follows CPython (`PyUnicode_Format`): a tuple argument provides the values in order,
  /// any other value is a single argument, and a dict argument additionally enables `%(key)`
  /// conversions. Conversions `s r a c d i u f F e E g G x X o %%` with flags `-+ 0#`,
  /// a width, a precision and `*` args.
  static String stringMod(String format, Object arg) {
    try {
      return render(format, arg);
    } catch (OutOfMemoryError e) {
      // The runtime does not translate JVM allocation limits into MemoryError yet.
      throw new UnimplementedFeatureError("formatted string too large for the JVM", e);
    }
  }

  private static String render(String format, Object arg) {
    var out = new StringBuilder(format.length() + 16);
    var cursor = new ArgCursor(arg);
    var length = format.length();
    var index = 0;
    while (index < length) {
      var percent = format.indexOf('%', index);
      if (percent < 0) {
        out.append(format, index, length);
        break;
      }
      out.append(format, index, percent);
      index = percent + 1;

      var keyed = false;
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
        cursor.selectKey(format.substring(keyStart, index - 1));
        keyed = true;
      }

      var leftAlign = false;
      var plusSign = false;
      var spaceSign = false;
      var zeroPad = false;
      var altForm = false;
      flags: for (;;) {
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

      long width = -1;
      if (index < length && format.charAt(index) == '*') {
        index++;
        width = sizeArg(cursor.next());
        if (width < 0) {
          leftAlign = true;
          width = -width;
        }
      } else {
        while (index < length && isDigit(format.charAt(index))) {
          width = accumulate(width < 0 ? 0 : width, format.charAt(index++) - '0', "width");
        }
      }

      long precision = -1;
      if (index < length && format.charAt(index) == '.') {
        index++;
        if (index < length && format.charAt(index) == '*') {
          index++;
          precision = Math.max(0L, sizeArg(cursor.next()));  // CPython clamps a negative precision to zero
        } else {
          precision = 0;
          while (index < length && isDigit(format.charAt(index))) {
            precision = accumulate(precision, format.charAt(index++) - '0', "precision");
          }
        }
      }

      // CPython skips at most one C length modifier ("%hd", "%ld", "%Ld")
      if (index < length) {
        var c = format.charAt(index);
        if (c == 'h' || c == 'l' || c == 'L') {
          index++;
        }
      }
      if (index == length) {
        throw new PyValueError("incomplete format");
      }

      var conversionIndex = index;
      var conversion = format.codePointAt(index);
      index += Character.charCount(conversion);
      var spec = new FormatSpec(width, precision, leftAlign, plusSign, spaceSign, zeroPad, altForm);
      switch (conversion) {
        case '%' -> {
          // A literal percent sign: only accepted without flags, width, precision or key.
          // An argument is still consumed before the conversion is rejected.
          if (leftAlign || plusSign || spaceSign || zeroPad || altForm
              || width != -1 || precision != -1 || keyed) {
            cursor.next();
            throw unsupportedFormat(format, conversionIndex, conversion);
          }
          out.append('%');
        }
        case 's', 'r', 'a' -> {
          var value = cursor.next();
          var text = switch (conversion) {
            case 's' -> StringIntrinsics.$str$(value);
            case 'r' -> StringIntrinsics.$repr$(value);
            default -> StringIntrinsics.ascii(value);
          };
          appendString(out, spec, truncate(text, precision));
        }
        case 'c' -> appendChar(out, spec, cursor.next());
        case 'd', 'i', 'u' -> appendInteger(out, spec, cursor.next(), (char) conversion, 10, false);
        case 'x', 'X', 'o' -> appendInteger(out, spec, cursor.next(), (char) conversion,
            conversion == 'o' ? 8 : 16, conversion == 'X');
        case 'e', 'E', 'f', 'F', 'g', 'G' -> appendFloat(out, spec, cursor.next(), (char) conversion);
        default -> throw unsupportedFormat(format, conversionIndex, conversion);
      }
    }
    cursor.checkAllConsumed();
    return out.toString();
  }

  private static boolean isDigit(char c) {
    return c >= '0' && c <= '9';
  }

  /// Digits of a literal width or precision (CPython: ValueError "width too big").
  private static long accumulate(long value, int digit, String what) {
    if (value > (Long.MAX_VALUE - 9) / 10) {
      throw new PyValueError(what + " too big");
    }
    return value * 10 + digit;
  }

  /// The integer argument of a `*` width or precision.
  private static long sizeArg(Object value) {
    if (!isInt(value)) {
      throw new PyTypeError("* wants int");
    }
    var big = toBigInteger(value);
    if (big.bitLength() > 63 || big.longValue() == Long.MIN_VALUE) {
      throw new PyOverflowError("Python int too large to convert to C ssize_t");
    }
    return big.longValue();
  }

  /// Message and index follow CPython: `%R` of the character, index in code points.
  private static PyValueError unsupportedFormat(String format, int conversionIndex, int codePoint) {
    return new PyValueError("unsupported format character "
        + StringIntrinsics.$repr$(new String(Character.toChars(codePoint)))
        + " (0x" + Integer.toHexString(codePoint) + ") at index "
        + format.codePointCount(0, conversionIndex));
  }

  // -- size guards

  private static UnimplementedFeatureError tooLarge() {
    return new UnimplementedFeatureError("formatted string larger than the maximum Java string size");
  }

  private static void checkSize(long size) {
    if (size > MAX_STRING_LENGTH) {
      throw tooLarge();
    }
  }

  private static void appendRepeated(StringBuilder out, char c, long count) {
    if (count > 0) {
      checkSize((long) out.length() + count);
      out.repeat(c, (int) count);
    }
  }

  private static String zeros(long count) {
    if (count <= 0) {
      return "";
    }
    checkSize(count);
    return "0".repeat((int) count);
  }

  // -- conversion specification

  private record FormatSpec(
      long width,
      long precision,
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

  /// Layout of a number: prefix (sign, radix), `precisionZeros` extra zeros, then the body,
  /// padded to the width with spaces (left or right) or with zeros after the prefix.
  private static void appendNumber(StringBuilder out, FormatSpec spec, String prefix,
                                   long precisionZeros, String body) {
    var total = (long) prefix.length() + precisionZeros + body.length();
    var padding = Math.max(0L, spec.width() - total);
    checkSize((long) out.length() + total + padding);
    if (spec.leftAlign()) {
      out.append(prefix);
      appendRepeated(out, '0', precisionZeros);
      out.append(body);
      appendRepeated(out, ' ', padding);
    } else if (spec.zeroPad()) {
      out.append(prefix);
      appendRepeated(out, '0', precisionZeros + padding);
      out.append(body);
    } else {
      appendRepeated(out, ' ', padding);
      out.append(prefix);
      appendRepeated(out, '0', precisionZeros);
      out.append(body);
    }
  }

  private static void appendString(StringBuilder out, FormatSpec spec, String text) {
    var count = text.codePointCount(0, text.length());
    var padding = Math.max(0L, spec.width() - count);
    checkSize((long) out.length() + text.length() + padding);
    if (spec.leftAlign()) {
      out.append(text);
      appendRepeated(out, ' ', padding);
    } else {
      appendRepeated(out, ' ', padding);
      out.append(text);
    }
  }

  private static String truncate(String text, long precision) {
    if (precision == -1 || text.codePointCount(0, text.length()) <= precision) {
      return text;
    }
    return text.substring(0, text.offsetByCodePoints(0, (int) precision));  // precision < code point count
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

  // -- integers

  private static void appendInteger(StringBuilder out, FormatSpec spec, Object value,
                                    char conversion, int radix, boolean uppercase) {
    var number = radix == 10
        ? toIntegerArg(value, conversion)
        : toExactIntegerArg(value, conversion);
    var digits = number.abs().toString(radix);
    if (uppercase) {
      digits = digits.toUpperCase(Locale.ROOT);
    }
    var precisionZeros = Math.max(0L, spec.precision() - digits.length());
    var prefix = sign(number.signum() < 0, spec);
    if (spec.altForm()) {
      if (radix == 16) {
        prefix += uppercase ? "0X" : "0x";
      } else if (radix == 8) {
        prefix += "0o";
      }
    }
    appendNumber(out, spec, prefix, precisionZeros, digits);
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

  // -- floats

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
    appendNumber(out, spec, prefix, 0, body);
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

  private static String formatDouble(BigDecimal value, long precision, char conversion, boolean altForm) {
    var decimals = precision == -1 ? 6 : precision;
    return switch (conversion) {
      case 'f', 'F' -> {
        var text = fixed(value, decimals, true);
        yield decimals == 0 && altForm ? text + '.' : text;
      }
      case 'e', 'E' -> formatScientific(value, decimals, false, altForm);
      default -> formatGeneral(value, decimals, altForm);  // g/G
    };
  }

  /// Fixed notation with `decimals` decimals, rounded half-to-even like CPython.
  /// Digits beyond the exact expansion of the double are zeros: they are appended
  /// (when `padZeros`) instead of being asked from BigDecimal.
  private static String fixed(BigDecimal value, long decimals, boolean padZeros) {
    var scale = (int) Math.min(decimals, MAX_EXACT_FRACTION_DIGITS);
    var text = value.setScale(scale, RoundingMode.HALF_EVEN).toPlainString();
    if (padZeros && decimals > scale) {
      text += zeros(decimals - scale);
    }
    return text;
  }

  private static String formatGeneral(BigDecimal value, long significantDigits, boolean altForm) {
    var sig = significantDigits == 0 ? 1 : significantDigits;
    if (value.signum() == 0) {
      return altForm ? "0." + zeros(sig - 1) : "0";
    }
    var sci = value.round(roundingContext(sig));
    var exp = sci.precision() - sci.scale() - 1;
    if (exp >= -4 && exp < sig) {
      var text = fixed(value, sig - 1 - exp, altForm);  // zeros would be stripped otherwise
      if (altForm) {
        return text.indexOf('.') == -1 ? text + '.' : text;
      }
      return stripFractionZeros(text);
    }
    return formatScientific(value, sig - 1, !altForm, altForm);
  }

  /// Scientific notation with `decimals` digits after the point, exponent with at
  /// least two digits, trailing zeros stripped when requested (`%g`).
  private static String formatScientific(BigDecimal value, long decimals, boolean stripZeros, boolean altForm) {
    var sig = decimals + 1;
    int exp;
    String digits;
    if (value.signum() == 0) {
      exp = 0;
      digits = "0";
    } else {
      var sci = value.round(roundingContext(sig));
      exp = sci.precision() - sci.scale() - 1;
      digits = sci.unscaledValue().toString();
    }
    if (!stripZeros && digits.length() < sig) {
      digits += zeros(sig - digits.length());
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

  private static MathContext roundingContext(long significantDigits) {
    return new MathContext((int) Math.min(significantDigits, MAX_EXACT_SIGNIFICANT_DIGITS),
        RoundingMode.HALF_EVEN);
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

  /// The arguments of a `%` format, with the state machine of CPython's `PyUnicode_Format`:
  /// a tuple provides one value per conversion, any other value is a single argument,
  /// and a dict argument additionally allows `%(key)` conversions. A key replaces the
  /// current argument by the looked-up value, which is then a single argument,
  /// even if it is a tuple.
  private static final class ArgCursor {
    private final PyDict<?, ?> dict;  // non-null only if the argument itself is a dict
    private PyTuple tuple;            // non-null while values come from the argument tuple
    private Object single;            // the current single value when tuple == null
    private int index;
    private boolean singleUsed;

    ArgCursor(Object arg) {
      this.dict = arg instanceof PyDict<?, ?> d ? d : null;
      this.tuple = arg instanceof PyTuple t ? t : null;
      this.single = arg;
    }

    Object next() {
      if (tuple != null) {
        if (index >= tuple.size()) {
          throw notEnoughArguments();
        }
        return TupleIntrinsics.$get$(tuple, index++);
      }
      if (singleUsed) {
        throw notEnoughArguments();
      }
      singleUsed = true;
      return single;
    }

    void selectKey(String key) {
      if (dict == null) {
        throw new PyTypeError("format requires a mapping");
      }
      single = DictIntrinsics.$get$(dict, key);  // KeyError with the repr of the key
      tuple = null;
      singleUsed = false;
    }

    void checkAllConsumed() {
      if (dict != null) {
        return;  // like CPython, a mapping never reports unused arguments
      }
      if (tuple != null ? index < tuple.size() : !singleUsed) {
        throw new PyTypeError("not all arguments converted during string formatting");
      }
    }

    private static PyTypeError notEnoughArguments() {
      return new PyTypeError("not enough arguments for format string");
    }
  }
}