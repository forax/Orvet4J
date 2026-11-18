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
    switch (args.length) {
      case 0 -> {
        return false;
      }
      case 1 -> { return TruthinessIntrinsics.$truthy$(args[0]); }
      default -> throw new PyTypeError("bool() expected at most 1 argument, got " + args.length);
    }
  }

  @Intrinsic
  public static Object $float$(Object... args) {
    switch (args.length) {
      case 0 -> { return 0.0d; }
      case 1 -> { return $float_oneArg$(args[0]); }
      default -> throw new PyTypeError("float expected at most 1 argument, got " + args.length);
    }
  }

  @Intrinsic
  public static Object $float_oneArg$(Object value) {
    switch (value) {
      case Boolean b -> { return b ? 1.0d : 0.0d; }
      case Long l -> { return l.doubleValue(); }
      case BigInteger b -> {
        var d = b.doubleValue();
        if (!Double.isFinite(d)) {
          throw new PyOverflowError("int too large to convert to float");
        }
        return d;
      }
      case Double d -> { return d; }
      case String s -> { return parseFloat(s); }
      default -> throw new PyTypeError("float() argument must be a string or a real number, not '"
          + typeName(value) + "'");
    }
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


  private static double parseFloat(String original) {
    var candidate = isFastFloatCandidate(original);
    if (candidate == null) {
      try {
        return Double.parseDouble(original);
      } catch (NumberFormatException e) {
        throw invalidFloat(original);
      }
    }
    return parseFloatSlow(candidate, original);
  }

  /// Returns null if the original string can be passed directly to
  /// Double.parseDouble(), otherwise returns its normalized representation.
  private static String isFastFloatCandidate(String text) {
    var start = 0;
    var end = text.length();
    var needsRewrite = false;

    // Trim Python whitespace. ASCII whitespace can be left untouched
    // because Double.parseDouble() also trims it.
    while (start < end) {
      var cp = text.codePointAt(start);
      if (isNotPythonWhitespace(cp)) {
        break;
      }
      needsRewrite |= cp > ' ';
      start += Character.charCount(cp);
    }
    while (end > start) {
      var cp = text.codePointBefore(end);
      if (isNotPythonWhitespace(cp)) {
        break;
      }
      needsRewrite |= cp > ' ';
      end -= Character.charCount(cp);
    }
    // Scan without allocation until the first character that requires
    // normalization or is not part of the fast-path alphabet.
    for (var index = start; index < end; index++) {
      var ch = text.charAt(index);
      if (isFastFloatCharacter(ch)) {
        continue;
      }
      return rewriteFloatCandidate(text, start, index, end);
    }
    // Unicode whitespace needs trimming, even if the numeric part is ASCII.
    if (needsRewrite) {
      return rewriteFloatCandidate(text, start, end, end);
    }
    return null;
  }

  private static boolean isFastFloatCharacter(char ch) {
    return (ch >= '0' && ch <= '9') || ch == '+' || ch == '-' || ch == '.' || ch == 'e' || ch == 'E';
  }

  private static boolean isNotPythonWhitespace(int cp) {
    return !Character.isWhitespace(cp) && !Character.isSpaceChar(cp);
  }

  /// Rewrites the candidate starting at the first character that cannot
  /// be handled by the fast path. The preceding characters are already
  /// known to be suitable for the normalized representation.
  private static String rewriteFloatCandidate(String text, int start, int index, int end) {
    var builder = new StringBuilder(text.length());
    builder.append(text, start, index);
    var previousWasDigit =
        index > start && decimalDigit(text.codePointBefore(index)) >= 0;
    for (var i = index; i < end;) {
      var cp = text.codePointAt(i);
      i += Character.charCount(cp);
      if (cp == '_') {
        // An underscore is valid only between two decimal digits.
        if (previousWasDigit && i < end && decimalDigit(text.codePointAt(i)) >= 0) {
          previousWasDigit = false;
          continue;
        }
        builder.append('_');
        previousWasDigit = false;
        continue;
      }
      var digit = decimalDigit(cp);
      if (digit >= 0) {
        builder.append((char) ('0' + digit));
        previousWasDigit = true;
      } else {
        builder.appendCodePoint(cp);
        previousWasDigit = false;
      }
    }
    return builder.toString();
  }

  private static double parseFloatSlow(String text, String original) {
    var index = text.startsWith("+") || text.startsWith("-") ? 1 : 0;
    var negative = text.startsWith("-");
    if (matchesIgnoreCase(text, index, "inf")
        || matchesIgnoreCase(text, index, "infinity")) {
      return negative ? Double.NEGATIVE_INFINITY : Double.POSITIVE_INFINITY;
    }
    if (matchesIgnoreCase(text, index, "nan")) {
      return negative ? -Double.NaN : Double.NaN;
    }
    if (!isValidFloatLiteral(text)) {
      throw invalidFloat(original);
    }
    try {
      return Double.parseDouble(text);
    } catch (NumberFormatException e) {
      throw invalidFloat(original);
    }
  }

  private static boolean matchesIgnoreCase(String text, int start, String expected) {
    return text.length() - start == expected.length()
        && text.regionMatches(true, start, expected, 0, expected.length());
  }

  /// Validates the normalized ASCII decimal representation.
  private static boolean isValidFloatLiteral(String text) {
    var index = 0;
    if (index < text.length()
        && (text.charAt(index) == '+' || text.charAt(index) == '-')) {
      index++;
    }
    var digitsBeforePoint = index;
    index = skipAsciiDigits(text, index);
    var hasDigitsBeforePoint = index != digitsBeforePoint;
    var hasDigitsAfterPoint = false;
    if (index < text.length() && text.charAt(index) == '.') {
      index++;
      var digitsAfterPoint = index;
      index = skipAsciiDigits(text, index);
      hasDigitsAfterPoint = index != digitsAfterPoint;
    }
    if (!hasDigitsBeforePoint && !hasDigitsAfterPoint) {
      return false;
    }
    if (index < text.length() && (text.charAt(index) == 'e' || text.charAt(index) == 'E')) {
      index++;
      if (index < text.length() && (text.charAt(index) == '+' || text.charAt(index) == '-')) {
        index++;
      }
      var exponentStart = index;
      index = skipAsciiDigits(text, index);
      if (index == exponentStart) {
        return false;
      }
    }

    return index == text.length();
  }

  private static int skipAsciiDigits(String text, int index) {
    while (index < text.length()) {
      var ch = text.charAt(index);
      if (ch < '0' || ch > '9') {
        break;
      }
      index++;
    }
    return index;
  }

  private static int decimalDigit(int cp) {
    if (Character.getType(cp) != Character.DECIMAL_DIGIT_NUMBER) {
      return -1;
    }
    return Character.digit(cp, 10);
  }

  private static PyValueError invalidFloat(String value) {
    return new PyValueError("could not convert string to float: " + StringIntrinsics.$repr$(value));
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
    var candidate = isFastIntegerCandidate(original, requestedBase);
    if (candidate == null) {  // fast path
      try {
        return Long.parseLong(original, requestedBase);
      } catch (NumberFormatException _) {
        // Either out of long range or not a valid integer.
        // Let the slow path distinguish these cases.
      }
    }
    return parseIntegerSlow(original, requestedBase, candidate);
  }

  /// Returns null when the original string can be passed directly
  /// to Long.parseLong(). Otherwise, returns a normalized candidate.
  private static StringBuilder isFastIntegerCandidate(String text, int requestedBase) {
    var start = 0;
    var end = text.length();
    // Remove Python whitespace from both ends.
    while (start < end) {
      var cp = text.codePointAt(start);
      if (isNotPythonWhitespace(cp)) {
        break;
      }
      start += Character.charCount(cp);
    }
    while (end > start) {
      var cp = text.codePointBefore(end);
      if (isNotPythonWhitespace(cp)) {
        break;
      }
      end -= Character.charCount(cp);
    }
    // Base zero always requires Python-specific prefix and leading-zero handling.
    // Whitespace also requires normalization.
    if (requestedBase == 0 || start != 0 || end != text.length()) {
      return rewriteIntegerCandidate(text, start, start, end);
    }
    // Find the first character requiring normalization.
    // Invalid ASCII characters are left for Long.parseLong() to reject.
    for (var index = start; index < end;) {
      var cp = text.codePointAt(index);
      if (cp == '_' || cp > 127) {
        return rewriteIntegerCandidate(text, start, index, end);
      }
      index += Character.charCount(cp);
    }
    return null;
  }

  /// Copies the already-scanned prefix and rewrites the remainder.
  /// Unicode decimal digits are converted to ASCII.
  /// Underscores are preserved for validation by parseIntegerSlow().
  private static StringBuilder rewriteIntegerCandidate(String text, int start, int index, int end) {
    var builder = new StringBuilder(end - start);
    builder.append(text, start, index);
    for (var i = index; i < end;) {
      var cp = text.codePointAt(i);
      i += Character.charCount(cp);
      if (Character.getType(cp) == Character.DECIMAL_DIGIT_NUMBER) {
        var value = Character.digit(cp, 10);
        if (value >= 0) {
          builder.append((char) ('0' + value));
          continue;
        }
      }
      builder.appendCodePoint(cp);
    }
    return builder;
  }

  private static Object parseIntegerSlow(String original, int requestedBase, StringBuilder candidate) {
    var text = candidate == null ? original : candidate.toString();
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
    // One underscore immediately after a radix prefix is permitted.
    if (strippedPrefix && text.startsWith("_")) {
      text = text.substring(1);
    }
    if (text.isEmpty()) {
      throw invalidInteger(original, requestedBase);
    }
    var digits = new StringBuilder(text.length());
    var previousWasDigit = false;
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
      var value = digit(cp, base);
      if (value < 0) {
        throw invalidInteger(original, requestedBase);
      }
      digits.append(Character.forDigit(value, base));
      previousWasDigit = true;
    }
    if (digits.isEmpty() || !previousWasDigit) {
      throw invalidInteger(original, requestedBase);
    }
    // Base zero rejects leading zeros unless all digits are zero.
    if (requestedBase == 0 && base == 10 &&
        digits.length() > 1 && digits.charAt(0) == '0' &&
        !allZeros(digits)) {
      throw invalidInteger(original, requestedBase);
    }
    var literal = sign + digits;
    try {
      return normalizeInt(new BigInteger(literal, base));
    } catch (NumberFormatException e) {
      throw invalidInteger(original, requestedBase);
    }
  }

  private static boolean allZeros(StringBuilder digits) {
    for (var i = 0; i < digits.length(); i++) {
      if (digits.charAt(i) != '0') {
        return false;
      }
    }
    return true;
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

  private static PyValueError invalidInteger(String value, int base) {
    return new PyValueError("invalid literal for int() with base " + base + ": "
        + StringIntrinsics.$repr$(value));
  }
}