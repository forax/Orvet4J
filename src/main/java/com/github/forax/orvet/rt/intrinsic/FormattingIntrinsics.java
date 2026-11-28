package com.github.forax.orvet.rt.intrinsic;

import com.github.forax.orvet.rt.PyDict;
import com.github.forax.orvet.rt.PyTuple;
import com.github.forax.orvet.rt.error.PyIndexError;
import com.github.forax.orvet.rt.error.PyKeyError;
import com.github.forax.orvet.rt.error.PyOverflowError;
import com.github.forax.orvet.rt.error.PyTypeError;
import com.github.forax.orvet.rt.error.PyValueError;
import com.github.forax.orvet.rt.error.UnimplementedFeatureError;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Locale;

import static com.github.forax.orvet.rt.intrinsic.IntrinsicSupport.isInt;
import static com.github.forax.orvet.rt.intrinsic.IntrinsicSupport.normalize;
import static com.github.forax.orvet.rt.intrinsic.IntrinsicSupport.toBigInteger;
import static com.github.forax.orvet.rt.intrinsic.IntrinsicSupport.toDouble;
import static com.github.forax.orvet.rt.intrinsic.IntrinsicSupport.typeName;

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
        cursor.requireMapping();  // like CPython, the mapping is checked before the key is parsed
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
          if (width < 0) {  // Long.MIN_VALUE cannot be negated
            throw new PyOverflowError("width too big");
          }
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
          // CPython converts to a C int and clamps a negative precision to zero
          precision = Math.max(0L, sizeIntArg(cursor.next()));
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

  /// The integer argument of a `*` width (CPython converts it to a C ssize_t).
  /// Long.MIN_VALUE is valid here: the "width too big" error only happens later,
  /// when the negative width cannot be negated.
  private static long sizeArg(Object value) {
    if (!isInt(value)) {
      throw new PyTypeError("* wants int");
    }
    var big = toBigInteger(value);
    if (big.bitLength() > 63) {
      throw new PyOverflowError("Python int too large to convert to C ssize_t");
    }
    return big.longValue();
  }

  /// The integer argument of a `*` precision (CPython converts it to a C int).
  private static long sizeIntArg(Object value) {
    if (!isInt(value)) {
      throw new PyTypeError("* wants int");
    }
    var big = toBigInteger(value);
    if (big.bitLength() > 31) {
      throw new PyOverflowError("Python int too large to convert to C int");
    }
    return big.intValueExact();
  }

  /// Message and index follow CPython: the character is shown raw when it is printable
  /// ASCII, as '?' otherwise; the index is in code points.
  private static PyValueError unsupportedFormat(String format, int conversionIndex, int codePoint) {
    var displayed = codePoint > 0x1f && codePoint < 0x7f
        ? "'" + (char) codePoint + "'"
        : "'?'";
    return new PyValueError("unsupported format character "
        + displayed
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
        var count = s.codePointCount(0, s.length());
        if (count != 1) {
          throw new PyTypeError("%c requires an int or a unicode character, not a string of length " + count);
        }
        yield s;
      }
      default -> throw new PyTypeError("%c requires an int or a unicode character, not " + typeName(value));
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
    var magnitude = number.abs();
    var digits = radix == 10
        ? StringIntrinsics.intString(magnitude)
        : magnitude.toString(radix);
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
      default -> formatGeneral(value, decimals, altForm, false);  // g/G
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

  private static String formatGeneral(BigDecimal value, long significantDigits, boolean altForm, boolean addDotZero) {
    var sig = significantDigits == 0 ? 1 : significantDigits;
    if (value.signum() == 0) {
      if (addDotZero && sig == 1) {
        return formatScientific(value, 0, true, altForm);   // format(0.0, '.0') == '0e+00'
      }
      return altForm ? "0." + zeros(sig - 1) : (addDotZero ? "0.0" : "0");
    }
    var sci = value.round(roundingContext(sig));
    var exp = sci.precision() - sci.scale() - 1;
    if (exp >= -4 && exp < (addDotZero ? sig - 1 : sig)) {
      var text = fixed(value, sig - 1 - exp, altForm);
      if (altForm) {
        return text.indexOf('.') == -1 ? text + '.' : text;
      }
      text = stripFractionZeros(text);
      return addDotZero && text.indexOf('.') == -1 ? text + ".0" : text;
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

    /// Like CPython, the mapping is checked before the key is parsed
    /// ('%(unfinished' % 42 raises TypeError, not ValueError "incomplete format key").
    void requireMapping() {
      if (dict == null) {
        throw new PyTypeError("format requires a mapping");
      }
    }

    void selectKey(String key) {
      requireMapping();
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

  // -- format-spec mini-language (str.format fields and the format() builtin)

  public static Object format(String self, Object... args) {
    var out = new StringBuilder(self.length() + 16);
    formatFields(self, out, new FieldCursor(args), 0);
    return out.toString();
  }

  /// Depth 0 is the template itself, depth 1 a format spec nested inside a field.
  private static void formatFields(String template, StringBuilder out, FieldCursor cursor, int depth) {
    var length = template.length();
    var index = 0;
    while (index < length) {
      switch (template.charAt(index)) {
        case '{' -> {
          if (index + 1 < length && template.charAt(index + 1) == '{') {
            out.append('{');
            index += 2;
          } else {
            index = formatField(template, index + 1, out, cursor, depth);
          }
        }
        case '}' -> {
          if (index + 1 < length && template.charAt(index + 1) == '}') {
            out.append('}');
            index += 2;
          } else {
            throw new PyValueError("Single '}' encountered in format string");
          }
        }
        default -> {
          out.append(template.charAt(index));
          index++;
        }
      }
    }
  }

  /// Renders one replacement field starting just after its `{`; returns the offset after its `}`.
  private static int formatField(String template, int index, StringBuilder out, FieldCursor cursor, int depth) {
    var length = template.length();
    var end = scanFieldName(template, index);
    if (end == length) {
      throw new PyValueError(end == index
          ? "Single '{' encountered in format string"
          : "expected '}' before end of string");
    }
    var argName = template.substring(index, end);
    var hasAttribute = false;
    var items = new ArrayList<String>();
    loop: while (end < length) {
      switch (template.charAt(end)) {
        case '.' -> {
          hasAttribute = true;
          end++;
          var attrEnd = scanFieldName(template, end);
          if (attrEnd == length) {
            throw new PyValueError("expected '}' before end of string");
          }
          if (attrEnd == end) {
            throw new PyValueError("Empty attribute in format string");
          }
          end = attrEnd;
        }
        case '[' -> {
          end++;
          var itemEnd = end;
          while (itemEnd < length && template.charAt(itemEnd) != ']') {
            itemEnd++;
          }
          if (itemEnd == length) {
            throw new PyValueError("expected '}' before end of string");
          }
          if (itemEnd == end) {
            throw new PyValueError("Empty attribute in format string");
          }
          items.add(template.substring(end, itemEnd));
          end = itemEnd + 1;
        }
        default -> { break loop; }
      }
    }
    if (end < length) {
      var c = template.charAt(end);
      if (c != ':' && c != '!' && c != '}') {
        throw new PyValueError("Only '.' or '[' may follow ']' in format field specifier");
      }
    }
    // -- conversion
    char conversion = '\0';
    if (end < length && template.charAt(end) == '!') {
      end++;
      if (end >= length) {
        throw new PyValueError("end of string while looking for conversion specifier");
      }
      conversion = template.charAt(end);
      end++;
      if (conversion == '}') {
        throw new PyValueError("unmatched '{' in format spec");
      }
      if (conversion != 'r' && conversion != 's' && conversion != 'a') {
        throw new PyValueError("Unknown conversion specifier " + conversion);
      }
      if (end < length) {
        var c = template.charAt(end);
        if (c != ':' && c != '}') {
          throw new PyValueError("expected ':' after conversion specifier");
        }
      }
    }
    // -- format spec, which may itself contain replacement fields
    var spec = "";
    if (end < length && template.charAt(end) == ':') {
      var specStart = ++end;
      var braces = 0;
      var found = false;
      while (end < length) {
        var c = template.charAt(end);
        if (c == '{') {
          if (depth != 0) {
            throw new PyValueError("Max string recursion exceeded");
          }
          braces++;
        } else if (c == '}') {
          if (braces == 0) {
            found = true;
            break;
          }
          braces--;
        }
        end++;
      }
      if (!found) {
        if (braces == 0) {
          // a malformed spec is reported before the unmatched error, like CPython
          FormattingIntrinsics.parseSpec(template.substring(specStart, end));
        }
        throw new PyValueError("unmatched '{' in format spec");
      }
      spec = template.substring(specStart, end);
    }
    if (end >= length) {                       // FIX: was only checked when there was no conversion
      throw new PyValueError("expected '}' before end of string");
    }
    end++;  // the closing '}'

    // -- resolve the argument, then its items, then render
    var value = cursor.resolve(argName);
    if (hasAttribute) {
      throw new UnimplementedFeatureError("attribute access in format fields is not supported yet");
    }
    for (var item : items) {
      value = ItemIntrinsics.$getItem$(value, itemValue(item));
    }
    value = switch (conversion) {
      case 'r' -> StringIntrinsics.$repr$(value);
      case 'a' -> StringIntrinsics.ascii(value);
      case 's' -> StringIntrinsics.$str$(value);
      default -> value;
    };
    if (spec.indexOf('{') != -1) {
      var specOut = new StringBuilder(spec.length());
      formatFields(spec, specOut, cursor, depth + 1);
      spec = specOut.toString();
    }
    out.append(FormattingIntrinsics.formatValue(value, spec));
    return end;
  }

  private static int scanFieldName(String template, int index) {
    var length = template.length();
    while (index < length) {
      var c = template.charAt(index);
      if (c == '.' || c == '[' || c == ']' || c == ':' || c == '!' || c == '}') {
        return index;
      }
      if (c == '{') {
        throw new PyValueError("unexpected '{' in field name");
      }
      index++;
    }
    return length;
  }

  private static Object itemValue(String item) {
    return isDecimalDigits(item) ? normalize(new BigInteger(item)) : item;
  }

  private static boolean isDecimalDigits(String s) {
    if (s.isEmpty()) {
      return false;
    }
    for (var i = 0; i < s.length(); i++) {
      var c = s.charAt(i);
      if (c < '0' || c > '9') {
        return false;
      }
    }
    return true;
  }

  /// Resolves field names against the positional arguments; automatic (`{}`) and manual (`{0}`)
  /// numbering cannot be mixed. Keyword names raise KeyError.
  private static final class FieldCursor {
    private final Object[] args;
    private int autoIndex;
    private boolean automatic;
    private boolean manual;

    FieldCursor(Object[] args) {
      this.args = args;
    }

    Object resolve(String name) {
      if (name.isEmpty()) {
        if (manual) {
          throw new PyValueError("cannot switch from manual field specification to automatic field numbering");
        }
        automatic = true;
        if (autoIndex >= args.length) {
          throw new PyIndexError("Replacement index " + autoIndex + " out of range for positional args tuple");
        }
        return args[autoIndex++];
      }
      if (isDecimalDigits(name)) {
        if (automatic) {
          throw new PyValueError("cannot switch from automatic field numbering to manual field specification");
        }
        manual = true;
        if (name.length() > 19) {
          throw new PyValueError("Too many decimal digits in format string");
        }
        var index = new BigInteger(name);
        if (index.bitLength() > 63 || index.longValue() >= args.length) {
          throw new PyIndexError("Replacement index " + index + " out of range for positional args tuple");
        }
        return args[index.intValue()];
      }
      throw new PyKeyError(StringIntrinsics.$repr$(name));
    }
  }


  /// Python `format(value, spec)`. Only str, int, bool and float accept a non-empty spec.
  /// Known divergence: type `n` behaves like `d`/`g` in the C locale. A width beyond the
  /// JVM string limit raises UnimplementedFeatureError.
  public static Object formatValue(Object value, String spec) {
    if (spec.isEmpty()) {
      return switch (value) {
        case String s -> s;
        default -> StringIntrinsics.$str$(value);
      };
    }
    return switch (value) {
      case String s -> formatStrValue(s, spec);
      case Long _, BigInteger _, Boolean _ -> formatIntValue(value, spec);
      case Double d -> formatFloatValue(d, spec);
      default -> throw new PyTypeError("unsupported format string passed to "
          + IntrinsicSupport.typeName(value) + ".__format__");
    };
  }

  /// `[[fill]align][sign][z][#][0][width][,|_][.precision][type]`
  /// align/type are '\0' when absent; trailingJunk is set when more than one char follows.
  record Pyspec(int fill, char align, char sign, boolean z, boolean alt, boolean zero,
                char grouping, long width, long precision, char type, boolean trailingJunk) {
  }

  static Pyspec parseSpec(String spec) {
    var index = 0;
    var length = spec.length();
    int fill = ' ';
    var fillGiven = false;
    char align = '\0';
    if (length > 0) {
      var firstWidth = Character.charCount(spec.codePointAt(0));   // the fill may be a supplementary char
      if (length > firstWidth && isAlign(spec.charAt(firstWidth))) {
        fill = spec.codePointAt(0);
        fillGiven = true;
        align = spec.charAt(firstWidth);
        index = firstWidth + 1;
      } else if (isAlign(spec.charAt(0))) {
        align = spec.charAt(0);
        index = 1;
      }
    }
    char sign = '\0';
    if (index < length) {
      var c = spec.charAt(index);
      if (c == '+' || c == '-' || c == ' ') {
        sign = c;
        index++;
      }
    }
    var z = false;
    if (index < length && spec.charAt(index) == 'z') {
      z = true;
      index++;
    }
    var alt = false;
    if (index < length && spec.charAt(index) == '#') {
      alt = true;
      index++;
    }
    var zero = false;
    if (index < length && spec.charAt(index) == '0') {
      zero = true;
      if (!fillGiven) {
        fill = '0';
      }
      index++;
    }
    long width = -1;
    if (index < length && isDigit(spec.charAt(index))) {
      var parsed = parseDigits(spec, index);
      index = parsed.end();
      width = parsed.value();
    }
    char grouping = '\0';
    if (index < length && (spec.charAt(index) == ',' || spec.charAt(index) == '_')) {
      grouping = spec.charAt(index++);
      if (index < length && (spec.charAt(index) == ',' || spec.charAt(index) == '_')) {
        throw new PyValueError("Cannot specify both ',' and '_'.");
      }
    }
    long precision = -1;
    if (index < length && spec.charAt(index) == '.') {
      index++;
      if (index >= length || !isDigit(spec.charAt(index))) {
        throw new PyValueError("Format specifier missing precision");
      }
      var parsed = parseDigits(spec, index);
      index = parsed.end();
      precision = parsed.value();
    }
    char type = '\0';
    var trailingJunk = false;
    var remaining = length - index;
    if (remaining == 1) {
      type = spec.charAt(index);
    } else if (remaining > 1) {
      trailingJunk = true;
    }
    return new Pyspec(fill, align, sign, z, alt, zero, grouping, width, precision, type, trailingJunk);
  }

  private record ParsedDigits(long value, int end) {}

  private static ParsedDigits parseDigits(String spec, int index) {
    var value = 0L;
    while (index < spec.length() && isDigit(spec.charAt(index))) {
      var digit = spec.charAt(index) - '0';
      if (value > (Long.MAX_VALUE - digit) / 10) {
        throw new PyValueError("Too many decimal digits in format string");
      }
      value = value * 10 + digit;
      index++;
    }
    return new ParsedDigits(value, index);
  }

  private static boolean isAlign(char c) {
    return c == '<' || c == '>' || c == '^' || c == '=';
  }

  private static PyValueError invalidSpec(String spec, String typeName) {
    return new PyValueError("Invalid format specifier '" + spec + "' for object of type '" + typeName + "'");
  }

  private static PyValueError unknownCode(char type, String typeName) {
    return new PyValueError("Unknown format code '" + type + "' for object of type '" + typeName + "'");
  }

  private static String formatStrValue(String s, String spec) {
    var p = parseSpec(spec);
    if (p.trailingJunk()) {
      throw invalidSpec(spec, "str");
    }
    if (p.type() != '\0' && p.type() != 's') {
      throw unknownCode(p.type(), "str");
    }
    if (p.sign() != '\0') {
      throw new PyValueError("Sign not allowed in string format specifier");
    }
    if (p.z()) {
      throw new PyValueError("Negative zero coercion (z) not allowed in string format specifier");
    }
    if (p.alt()) {
      throw new PyValueError("Alternate form (#) not allowed in string format specifier");
    }
    if (p.grouping() != '\0') {
      throw new PyValueError("Cannot specify '" + p.grouping() + "' with 's'.");
    }
    if (p.align() == '=') {
      throw new PyValueError("'=' alignment not allowed in string format specifier");
    }
    var align = p.align() == '\0' ? '<' : p.align();
    return padField("", truncate(s, p.precision()), p.fill(), align, p.width());
  }

  private static String formatIntValue(Object value, String spec) {
    var p = parseSpec(spec);
    var typeName = IntrinsicSupport.typeName(value);
    var type = p.type();
    // like CPython, float presentation types format an int as a float
    if (type == 'e' || type == 'E' || type == 'f' || type == 'F' || type == 'g' || type == 'G' || type == '%') {
      return formatFloatBody(IntrinsicSupport.toDouble(value), p, spec, typeName);
    }
    if (p.trailingJunk()) {
      throw invalidSpec(spec, typeName);
    }
    switch (type) {
      case '\0', 'd', 'n', 'b', 'o', 'x', 'X', 'c' -> { }
      default -> throw unknownCode(type, typeName);
    }
    if (p.z()) {
      throw new PyValueError("Negative zero coercion (z) not allowed in integer format specifier");
    }
    var grouping = p.grouping();
    var groupSize = type == 'b' || type == 'o' || type == 'x' || type == 'X' ? 4 : 3;
    if (grouping != '\0' && (type == 'n' || type == 'c' || (grouping == ',' && groupSize == 4))) {
      throw new PyValueError("Cannot specify '" + grouping + "' with '" + type + "'.");
    }
    if (p.precision() != -1) {
      throw new PyValueError("Precision not allowed in integer format specifier");
    }
    var align = p.align() == '\0' ? (p.zero() ? '=' : '>') : p.align();
    if (type == 'c') {
      if (p.sign() != '\0') {
        throw new PyValueError("Sign not allowed with integer format specifier 'c'");
      }
      if (p.alt()) {
        throw new PyValueError("Alternate form (#) not allowed with integer format specifier 'c'");
      }
      return padField("", charForCodePoint(value), p.fill(), align, p.width());
    }
    var number = toBigInteger(value);
    var digits = switch (type) {
      case 'b' -> number.abs().toString(2);
      case 'o' -> number.abs().toString(8);
      case 'x', 'X' -> number.abs().toString(16);
      default -> StringIntrinsics.intString(number.abs());  // 'd', 'n' and no type
    };
    if (type == 'X') {
      digits = digits.toUpperCase(Locale.ROOT);
    }
    var prefix = signFor(number.signum() < 0, p);
    if (p.alt()) {
      prefix += switch (type) {
        case 'x' -> "0x";
        case 'X' -> "0X";
        case 'o' -> "0o";
        case 'b' -> "0b";
        default -> "";
      };
    }
    var body = digits;
    if (grouping != '\0') {
      if (align == '=' && p.fill() == '0') {
        // fold the zero padding into the digits so the groups stay aligned
        checkSize(p.width());  // fail eagerly instead of looping/allocating (like padField)
        var target = p.width() - prefix.length();
        digits = "0".repeat(zeroPadForGrouping(digits.length(), groupSize, target)) + digits;
      }
      body = groupDigits(digits, grouping, groupSize);
    }
    return padField(prefix, body, p.fill(), align, p.width());
  }

  private static String formatFloatValue(double value, String spec) {
    return formatFloatBody(value, parseSpec(spec), spec, "float");
  }

  private static String formatFloatBody(double value, Pyspec p, String spec, String typeName) {
    if (p.trailingJunk()) {
      throw invalidSpec(spec, typeName);
    }
    var type = p.type();
    switch (type) {
      case '\0', 'e', 'E', 'f', 'F', 'g', 'G', 'n', '%' -> { }
      default -> throw unknownCode(type, typeName);
    }
    if (p.grouping() != '\0' && type == 'n') {
      throw new PyValueError("Cannot specify '" + p.grouping() + "' with 'n'.");
    }
    var align = p.align() == '\0' ? (p.zero() ? '=' : '>') : p.align();

    if (type == '\0' && p.precision() == -1) {
      // no type and no precision: rendered like repr()
      var signed = StringIntrinsics.floatString(value);
      var negative = signed.startsWith("-");
      var body = negative ? signed.substring(1) : signed;
      if (negative && p.z() && value == 0.0) {
        negative = false;
      }
      var prefix = signFor(negative, p);
      if (p.grouping() != '\0') {
        body = groupFloatBody(body, p, align, prefix.length(), "");
      }
      return padField(prefix, body, p.fill(), align, p.width());
    }

    var conversion = switch (type) {
      case '\0', 'n' -> 'g';  // without locale support, 'n' formats like 'g'
      case '%' -> 'f';
      default -> type;
    };
    var scaled = type == '%' ? value * 100.0 : value;
    var upper = type == 'E' || type == 'F' || type == 'G';
    var suffix = type == '%' ? "%" : "";
    String prefix;
    String body;
    if (Double.isNaN(scaled)) {
      prefix = signFor(false, p);
      body = upper ? "NAN" : "nan";
    } else if (Double.isInfinite(scaled)) {
      prefix = signFor(scaled < 0, p);
      body = upper ? "INF" : "inf";
    } else {
      var negative = Double.doubleToRawLongBits(scaled) < 0;
      var abs = new BigDecimal(Math.abs(scaled));
      body = type == '\0'
          ? formatGeneral(abs, p.precision(), p.alt(), true)  // precision but no type: 'g' that keeps a ".0"
          : formatDouble(abs, p.precision(), conversion, p.alt());
      if (upper) {
        body = body.toUpperCase(Locale.ROOT);
      }
      if (negative && p.z() && isAllZeros(body)) {          // z also applies to values that round to zero
        negative = false;
      }
      prefix = signFor(negative, p);
    }
    if (p.grouping() != '\0') {
      body = groupFloatBody(body, p, align, prefix.length(), suffix);
    }
    return padField(prefix, body + suffix, p.fill(), align, p.width());
  }

  private static boolean isAllZeros(String body) {
    for (var i = 0; i < body.length(); i++) {
      var c = body.charAt(i);
      if (c >= '1' && c <= '9') {
        return false;
      }
      if (c == 'e' || c == 'E') {
        break;
      }
    }
    return true;
  }

  /// Groups the integer digits of a float body; inf/nan bodies are left alone.
  /// Like CPython, the mantissa of an exponent body ("1e-320") is still grouped and,
  /// with '=' alignment, receives the zero padding.
  private static String groupFloatBody(String body, Pyspec p, char align, int prefixLength, String suffix) {
    var point = body.indexOf('.');
    var intEnd = point != -1 ? point : exponentStart(body);
    if (!isDigits(body, 0, intEnd)) {
      return body;
    }
    var intPart = body.substring(0, intEnd);
    var rest = body.substring(intEnd);
    if (align == '=' && p.fill() == '0') {
      checkSize(p.width());  // fail eagerly instead of looping/allocating (like padField)
      var target = p.width() - prefixLength - rest.length() - suffix.length();
      intPart = "0".repeat(zeroPadForGrouping(intPart.length(), 3, target)) + intPart;
    }
    return groupDigits(intPart, p.grouping(), 3) + rest;
  }

  /// Start of the exponent part of a scientific body ("1e-320"), or the body length if none.
  private static int exponentStart(String body) {
    for (var i = 1; i < body.length(); i++) {
      var c = body.charAt(i);
      if (c == 'e' || c == 'E') {
        return i;
      }
    }
    return body.length();
  }

  /// Smallest pad >= 0 such that groupedLength(digits + pad, groupSize) >= target.
  /// The caller caps the target with checkSize, so the result fits in an int.
  private static int zeroPadForGrouping(int digits, int groupSize, long target) {
    if (target <= digits) {
      return 0;
    }
    var lo = 0L;
    var hi = target - digits;  // groupedLength(target, groupSize) >= target
    while (lo < hi) {
      var mid = (lo + hi) >>> 1;
      if (groupedLength(digits + mid, groupSize) < target) {
        lo = mid + 1;
      } else {
        hi = mid;
      }
    }
    return (int) lo;
  }

  private static boolean isDigits(String s, int from, int to) {
    if (from >= to) {
      return false;
    }
    for (var i = from; i < to; i++) {
      if (!isDigit(s.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  private static long groupedLength(long digits, int size) {
    return digits + (digits - 1) / size;
  }

  private static String groupDigits(String digits, char separator, int size) {
    var length = digits.length();
    var groups = (length - 1) / size;
    if (groups == 0) {
      return digits;
    }
    var builder = new StringBuilder(length + groups);
    var head = length - groups * size;
    builder.append(digits, 0, head);
    for (var index = head; index < length; index += size) {
      builder.append(separator).append(digits, index, index + size);
    }
    return builder.toString();
  }

  /// Places `prefix + body` in a field of `width` code points. With '=' the padding goes
  /// between the prefix and the body.
  private static String padField(String prefix, String body, int fill, char align, long width) {
    var total = (long) prefix.codePointCount(0, prefix.length()) + body.codePointCount(0, body.length());
    if (width <= total) {
      return prefix + body;
    }
    var padding = width - total;
    if (padding > MAX_STRING_LENGTH) {
      throw tooLarge();
    }
    var fillText = new String(Character.toChars(fill));
    checkSize((long) prefix.length() + body.length() + padding * fillText.length());
    return switch (align) {
      case '<' -> prefix + body + fillText.repeat((int) padding);
      case '>' -> fillText.repeat((int) padding) + prefix + body;
      case '=' -> prefix + fillText.repeat((int) padding) + body;
      default -> {  // '^': the extra character goes on the right
        var left = (int) (padding / 2);
        yield fillText.repeat(left) + prefix + body + fillText.repeat((int) padding - left);
      }
    };
  }

  private static String signFor(boolean negative, Pyspec p) {
    if (negative) {
      return "-";
    }
    return switch (p.sign()) {
      case '+' -> "+";
      case ' ' -> " ";
      default -> "";
    };
  }

  private static String charForCodePoint(Object value) {
    var code = toBigInteger(value);
    if (code.bitLength() > 63) {
      // unlike the % operator, the format-spec path converts through a C long first
      throw new PyOverflowError("Python int too large to convert to C long");
    }
    if (code.signum() < 0 || code.compareTo(BigInteger.valueOf(0x110000)) >= 0) {  // FIX: 0x10FFFF is valid
      throw new PyOverflowError("%c arg not in range(0x110000)");
    }
    return String.valueOf(Character.toChars(code.intValueExact()));
  }


}