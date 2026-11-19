package com.github.forax.orvet.rt.intrinsic;

import com.github.forax.orvet.rt.PyDict;
import com.github.forax.orvet.rt.PyFunction;
import com.github.forax.orvet.rt.PyList;
import com.github.forax.orvet.rt.PyNone;
import com.github.forax.orvet.rt.PyRange;
import com.github.forax.orvet.rt.PySlice;
import com.github.forax.orvet.rt.PyTuple;
import com.github.forax.orvet.rt.PyType;
import com.github.forax.orvet.rt.error.PyIndexError;
import com.github.forax.orvet.rt.error.PyOverflowError;
import com.github.forax.orvet.rt.error.PyTypeError;
import com.github.forax.orvet.rt.error.PyValueError;
import com.github.forax.orvet.rt.error.UnimplementedFeatureError;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.function.IntPredicate;

import static com.github.forax.orvet.rt.intrinsic.IntrinsicSupport.normalize;
import static com.github.forax.orvet.rt.intrinsic.RangeIntrinsics.bigStart;
import static com.github.forax.orvet.rt.intrinsic.RangeIntrinsics.bigStep;
import static com.github.forax.orvet.rt.intrinsic.RangeIntrinsics.bigStop;

public final class StringIntrinsics {
  private StringIntrinsics() {
    throw new AssertionError();
  }

  // CPython 3.14 default. sys.set_int_max_str_digits() is not implemented.
  private static final int MAX_INT_STR_DIGITS = 4_300;

  //  FIXME use ScopeValue instead
  // Shared by recursive tuple/list/dict repr so mixed cycles are handled correctly.
  private static final ThreadLocal<IdentityHashMap<Object, Boolean>> ACTIVE_REPR =
      ThreadLocal.withInitial(IdentityHashMap::new);

  /// Python `string[index]` and `string[slice]`, using Unicode code points.
  @Intrinsic
  public static Object $getItem$(String s, Object index) {
    var i = ItemIntrinsics.$toIndex$(index, "string");
    int start;
    if (i < 0) {
      // Negative index: count backwards from the end.
      if (i < -(long) s.length()) {
        throw badIndex("string index out of range");
      }
      try {
        start = s.offsetByCodePoints(s.length(), (int) i);
      } catch (IndexOutOfBoundsException e) {
        throw badIndex("string index out of range");
      }
    } else {
      // Positive index: count forwards from the beginning.
      if (i >= s.length()) {
        throw badIndex("string index out of range");
      }
      try {
        start = s.offsetByCodePoints(0, (int) i);
      } catch (IndexOutOfBoundsException e) {
        throw badIndex("string index out of range");
      }
    }
    var end = start + Character.charCount(s.codePointAt(start));
    return s.substring(start, end);
  }

  private static PyIndexError badIndex(String message) {
    return new PyIndexError(message);
  }

  @Intrinsic
  public static Object $str$(Object value) {
    return switch (value) {
      case String s -> s;
      case Boolean b -> b ? "True" : "False";
      case Long l -> Long.toString(l);
      case BigInteger b -> intString(b);
      case Double d -> floatString(d);
      case PyNone _ -> "None";
      case PyTuple t -> tupleRepr(t);
      case PyList<?> list -> listRepr(list);
      case PyDict<?,?> dict -> dictRepr(dict);
      case PyFunction f -> functionRepr(f);
      case PyRange r -> r.toString();
      case PySlice s -> s.toString();
      case PyType t -> t.toString();
      default -> throw notCanonical("str", value);
    };
  }

  @Intrinsic
  public static Object $repr$(Object value) {
    return switch (value) {
      case String s -> quoteString(s);
      case PyTuple t -> tupleRepr(t);
      case PyList<?> list -> listRepr(list);
      case PyDict<?,?> dict -> dictRepr(dict);
      case Boolean b -> b ? "True" : "False";
      case Long l -> Long.toString(l);
      case BigInteger b -> intString(b);
      case Double d -> floatString(d);
      case PyNone _ -> "None";
      case PyFunction f -> functionRepr(f);
      case PyRange r -> rangeRepr(r);
      case PySlice s -> s.toString();
      case PyType t -> t.toString();
      default -> throw notCanonical("repr", value);
    };
  }


  // -- helpers

  private static Object getItemSlice(String s, PySlice  index) {
    var codePoints = s.codePoints().toArray();
    var resolved = index.resolve(codePoints.length);
    var sb = new StringBuilder();
    var pos = resolved.start();
    for (var i = 0L; i < resolved.count(); i++, pos += resolved.step()) {
      sb.appendCodePoint(codePoints[(int) pos]);
    }
    return sb.toString();
  }

  private static String intString(BigInteger value) {
    var result = value.toString();
    var digits = result.charAt(0) == '-'
        ? result.length() - 1
        : result.length();

    if (digits > MAX_INT_STR_DIGITS) {
      throw new PyValueError(
          "Exceeds the limit (" + MAX_INT_STR_DIGITS
              + " digits) for integer string conversion; "
              + "use sys.set_int_max_str_digits() to increase the limit");
    }
    return result;
  }

  private static String floatString(double value) {
    if (Double.isNaN(value)) {
      return "nan";
    }
    if (Double.isInfinite(value)) {
      return value < 0.0 ? "-inf" : "inf";
    }

    var negative = Double.doubleToRawLongBits(value) < 0;
    var absolute = Math.abs(value);
    if (absolute == 0.0) {
      return negative ? "-0.0" : "0.0";
    }

    var decimal = shortestRoundTripDecimal(absolute);
    var digits = decimal.unscaledValue().abs().toString();
    var exponent = digits.length() - decimal.scale() - 1;

    // CPython uses scientific notation for exponent < -4 or >= 16.
    String body;
    if (exponent < -4 || exponent >= 16) {
      var mantissa = digits.length() == 1
          ? digits
          : digits.charAt(0) + "." + digits.substring(1);

      var exponentDigits = Integer.toString(Math.abs(exponent));
      if (exponentDigits.length() < 2) {
        exponentDigits = "0" + exponentDigits;
      }

      body = mantissa
          + "e"
          + (exponent >= 0 ? "+" : "-")
          + exponentDigits;
    } else {
      body = decimal.toPlainString();
      if (decimal.scale() <= 0) {
        body += ".0";
      }
    }

    return negative ? "-" + body : body;
  }

  /// Finds a shortest decimal representation which round-trips to exactly the original binary64.
  private static BigDecimal shortestRoundTripDecimal(double value) {
    var exact = new BigDecimal(value);
    var exponent = exact.precision() - exact.scale() - 1;
    var expectedBits = Double.doubleToRawLongBits(value);

    for (var precision = 1; precision <= 17; precision++) {
      var scale = precision - 1 - exponent;
      var nearest = exact.movePointRight(scale)
          .setScale(0, RoundingMode.HALF_EVEN)
          .toBigIntegerExact();

      BigDecimal best = null;
      BigDecimal bestDistance = null;
      boolean bestEven = false;

      for (var delta = -1; delta <= 1; delta++) {
        var coefficient = nearest.add(BigInteger.valueOf(delta));
        if (coefficient.signum() <= 0) {
          continue;
        }
        var candidate = new BigDecimal(coefficient, scale).stripTrailingZeros();
        if (candidate.precision() > precision) {
          continue;
        }
        var parsed = Double.parseDouble(candidate.toString());
        if (Double.doubleToRawLongBits(parsed) != expectedBits) {
          continue;
        }
        var distance = candidate.subtract(exact).abs();
        var even = !coefficient.testBit(0);
        var comparison = bestDistance == null ? -1 : distance.compareTo(bestDistance);
        if (best == null || comparison < 0 || (comparison == 0 && even && !bestEven)) {
          best = candidate;
          bestDistance = distance;
          bestEven = even;
        }
      }
      if (best != null) {
        return best;
      }
    }
    throw new AssertionError("no shortest decimal for " + value);
  }


  private static String functionRepr(PyFunction function) {
    var name = function.name();
    if (function.isBuiltin()) {
      return "<built-in function " + name + ">";
    }
    return "<function " + name
        + " at 0x"
        + Integer.toHexString(System.identityHashCode(function))
        + ">";
  }


  // -- tuple representation

  private static String tupleRepr(PyTuple tuple) {
    if (!enterRepr(tuple)) {
      return "(...)";
    }
    try {
      var size = TupleIntrinsics.$size$(tuple);
      if (size == 0) {
        return "()";
      }
      var builder = new StringBuilder();
      builder.append('(');
      for (var i = 0; i < size; i++) {
        if (i != 0) {
          builder.append(", ");
        }
        builder.append($repr$(TupleIntrinsics.$get$(tuple, i)));
      }
      if (size == 1) {
        builder.append(',');
      }
      return builder.append(')').toString();
    } finally {
      leaveRepr(tuple);
    }
  }

  private static String listRepr(PyList<?> list) {
    if (!enterRepr(list)) {
      return "[...]";
    }
    try {
      var builder = new StringBuilder();
      builder.append('[');
      for (var i = 0; i < list.size(); i++) {
        if (i != 0) {
          builder.append(", ");
        }
        builder.append($repr$(list.get(i)));
      }
      return builder.append(']').toString();
    } finally {
      leaveRepr(list);
    }
  }

  private static String dictRepr(PyDict<?,?> dict) {
    if (!enterRepr(dict)) {
      return "{...}";
    }
    try {
      var builder = new StringBuilder();
      builder.append('{');
      var first = true;
      for (var i = 0; i < dict.entryCount(); i++) {
        if (!dict.isLive(i)) {
          continue;
        }
        if (!first) {
          builder.append(", ");
        }
        first = false;
        builder.append($repr$(dict.keyAt(i)));
        builder.append(": ");
        builder.append($repr$(dict.valueAt(i)));
      }
      return builder.append('}').toString();
    } finally {
      leaveRepr(dict);
    }
  }

  private static String rangeRepr(PyRange range) {
    var step = normalize(bigStep(range));
    var builder = new StringBuilder("range(")
        .append(StringIntrinsics.$repr$(normalize(bigStart(range)))).append(", ")
        .append(StringIntrinsics.$repr$(normalize(bigStop(range))));
    if (!step.equals(1L)) {
      builder.append(", ").append(StringIntrinsics.$repr$(step));
    }
    return builder.append(')').toString();
  }

  private static boolean enterRepr(Object value) {
    var active = ACTIVE_REPR.get();
    if (active.containsKey(value)) {
      return false;
    }
    active.put(value, Boolean.TRUE);
    return true;
  }

  private static void leaveRepr(Object value) {
    var active = ACTIVE_REPR.get();
    active.remove(value);
    if (active.isEmpty()) {
      ACTIVE_REPR.remove();
    }
  }

  // -- string representation

  private static String quoteString(String value) {
    var hasSingleQuote = value.indexOf('\'') >= 0;
    var hasDoubleQuote = value.indexOf('"') >= 0;

    // Choose the quote that minimizes escaping.
    var quote = hasSingleQuote && !hasDoubleQuote ? '"' : '\'';

    var builder = new StringBuilder(value.length() + 2);
    builder.append(quote);

    for (var i = 0; i < value.length();) {
      var cp = value.codePointAt(i);
      i += Character.charCount(cp);

      switch (cp) {
        case '\\' -> builder.append("\\\\");
        case '\t' -> builder.append("\\t");
        case '\n' -> builder.append("\\n");
        case '\r' -> builder.append("\\r");
        default -> {
          if (cp == quote) {
            builder.append('\\').append(quote);
          } else if (isPythonPrintable(cp)) {
            builder.appendCodePoint(cp);
          } else {
            appendUnicodeEscape(builder, cp);
          }
        }
      }
    }

    return builder.append(quote).toString();
  }

  private static boolean isPythonPrintable(int cp) {
    if (cp == 0x20) {
      return true;
    }
    // Exact CPython compatibility requires the Unicode 16.0 database, not the JDK's database.
    return switch (Character.getType(cp)) {
      case Character.CONTROL,
           Character.FORMAT,
           Character.SURROGATE,
           Character.PRIVATE_USE,
           Character.UNASSIGNED,
           Character.SPACE_SEPARATOR,
           Character.LINE_SEPARATOR,
           Character.PARAGRAPH_SEPARATOR -> false;
      default -> true;
    };
  }

  private static final char[] HEX = "0123456789abcdef".toCharArray();

  private static void appendUnicodeEscape(StringBuilder builder, int cp) {
    if (cp <= 0xff) {
      builder.append("\\x");
      appendHex(builder, cp, 2);
    } else if (cp <= 0xffff) {
      builder.append("\\u");
      appendHex(builder, cp, 4);
    } else {
      builder.append("\\U");
      appendHex(builder, cp, 8);
    }
  }

  private static void appendHex(StringBuilder builder, int value, int width) {
    for (var shift = (width - 1) * 4; shift >= 0; shift -= 4) {
      builder.append(HEX[(value >>> shift) & 0xf]);
    }
  }


  // -- method table

  public static Object _lower_(String s) {
    return s.toLowerCase(Locale.ROOT);
  }

  public static Object _upper_(String s) {
    return s.toUpperCase(Locale.ROOT);
  }

  public static Object _capitalize_(String s) {
    if (s.isEmpty()) {
      return s;
    }
    var first = s.codePointAt(0);
    var firstString =
        new String(Character.toChars(Character.toTitleCase(first)));
    var rest = s.substring(Character.charCount(first))
        .toLowerCase(Locale.ROOT);
    return firstString + rest;
  }

  public static Object _swapcase_(String s) {
    var builder = new StringBuilder();
    for (int i = 0; i < s.length();) {
      var cp = s.codePointAt(i);
      i += Character.charCount(cp);
      var character = new String(Character.toChars(cp));
      if (Character.isUpperCase(cp) || Character.isTitleCase(cp)) {
        builder.append(character.toLowerCase(Locale.ROOT));
      } else if (Character.isLowerCase(cp)) {
        builder.append(character.toUpperCase(Locale.ROOT));
      } else {
        builder.appendCodePoint(cp);
      }
    }
    return builder.toString();
  }


  public static Object _find_(String s, Object sub) {
    return find(s, requireString(sub, "find", "sub"),
        0, codePointLength(s), false);
  }

  public static Object _find_(
      String s, Object sub, Object start) {

    return find(s, requireString(sub, "find", "sub"),
        normalizeBound(asIndex(start), codePointLength(s)),
        codePointLength(s), false);
  }

  public static Object _find_(
      String s, Object sub, Object start, Object end) {

    var length = codePointLength(s);

    return find(s, requireString(sub, "find", "sub"),
        normalizeBound(asIndex(start), length),
        normalizeBound(asIndex(end), length), false);
  }

  public static Object _rfind_(String s, Object sub) {
    return find(s, requireString(sub, "rfind", "sub"),
        0, codePointLength(s), true);
  }

  public static Object _index_(String s, Object sub) {
    return find(s, requireString(sub, "index", "sub"),
        0, codePointLength(s), false, true);
  }

  public static Object _rindex_(String s, Object sub) {
    return find(s, requireString(sub, "rindex", "sub"),
        0, codePointLength(s), true, true);
  }


  public static Object _partition_(String s, Object sep) {
    var separator = requireString(sep, "partition", "sep");
    if (separator.isEmpty()) {
      throw new PyValueError("empty separator");
    }
    var index = s.indexOf(separator);
    if (index < 0) {
      return TupleIntrinsics.fromArray(
          new Object[] { s, "", "" });
    }
    return TupleIntrinsics.fromArray(new Object[] {
        s.substring(0, index),
        separator,
        s.substring(index + separator.length())
    });
  }

  public static Object _rpartition_(String s, Object sep) {
    var separator = requireString(sep, "rpartition", "sep");
    if (separator.isEmpty()) {
      throw new PyValueError("empty separator");
    }
    var index = s.lastIndexOf(separator);
    if (index < 0) {
      return TupleIntrinsics.fromArray(
          new Object[] { "", "", s });
    }
    return TupleIntrinsics.fromArray(new Object[] {
        s.substring(0, index),
        separator,
        s.substring(index + separator.length())
    });
  }

  public static Object _join_(String separator, Object iterable) {
    var builder = new StringBuilder();
    var iterator = IteratorIntrinsics.$iterator$(iterable);
    var first = true;
    var index = 0;
    Object item;
    while ((item = IteratorIntrinsics.$next$(iterator)) != null) {
      if (!(item instanceof String string)) {
        throw new PyTypeError("sequence item " + index
            + ": expected str instance, "
            + IntrinsicSupport.typeName(item)
            + " found");
      }
      if (!first) {
        builder.append(separator);
      }
      builder.append(string);
      first = false;
      index++;
    }
    return builder.toString();
  }

  public static Object _removeprefix_(String s, Object prefix) {
    var p = requireString(prefix, "removeprefix", "prefix");
    return s.startsWith(p) ? s.substring(p.length()) : s;
  }

  public static Object _removesuffix_(String s, Object suffix) {
    var p = requireString(suffix, "removesuffix", "suffix");
    return !p.isEmpty() && s.endsWith(p)
        ? s.substring(0, s.length() - p.length())
        : s;
  }


  public static Object _zfill_(String s, Object width) {
    var requested = asIndex(width);
    var length = codePointLength(s);
    if (requested <= length) {
      return s;
    }
    if (requested - length > Integer.MAX_VALUE) {
      throw new UnimplementedFeatureError(
          "string padding exceeds the maximum Java string size");
    }
    var padding = "0".repeat((int) (requested - length));
    if (s.startsWith("+") || s.startsWith("-")) {
      return s.substring(0, 1) + padding + s.substring(1);
    }
    return padding + s;
  }


  public static Object _isascii_(String s) {
    return s.codePoints().allMatch(cp -> cp < 128);
  }

  public static Object _isalpha_(String s) {
    return allCodePoints(s, cp -> {
      var type = Character.getType(cp);
      return type == Character.UPPERCASE_LETTER
          || type == Character.LOWERCASE_LETTER
          || type == Character.TITLECASE_LETTER
          || type == Character.MODIFIER_LETTER
          || type == Character.OTHER_LETTER;
    });
  }

  public static Object _isdecimal_(String s) {
    return allCodePoints(s, cp ->
        Character.getType(cp) == Character.DECIMAL_DIGIT_NUMBER);
  }


  // -- helpers

  private static boolean allCodePoints(String s, IntPredicate predicate) {
    if (s.isEmpty()) {
      return false;
    }
    return s.codePoints().allMatch(predicate);
  }

  private static long find(String s, String sub, int start, int end, boolean reverse) {
    return find(s, sub, start, end, reverse, false);
  }

  private static long find(String s, String sub, int start, int end,
      boolean reverse, boolean raiseIfMissing) {
    var length = codePointLength(s);
    start = normalizeBound(start, length);
    end = normalizeBound(end, length);

    long result = -1;
    if (start <= end) {
      var endOffset = offset(s, end);
      if (sub.isEmpty()) {
        // The empty string matches at every position.
        result = reverse ? end : start;
      } else if (reverse) {
        // Search from right to left.
        var candidateOffset = endOffset;
        for (var i = end; i >= start; i--) {
          var matchEnd = (long) candidateOffset + sub.length();
          if (matchEnd <= endOffset &&
              s.regionMatches(candidateOffset, sub, 0, sub.length()) &&
              isCodePointBoundary(s, (int) matchEnd)) {
            result = i;
            break;
          }
          if (i == start) {
            break;
          }
          var previous = s.codePointBefore(candidateOffset);
          candidateOffset -= Character.charCount(previous);
        }
      } else {
        // Search from left to right.
        var candidateOffset = offset(s, start);
        for (var i = start; i < end; i++) {
          var matchEnd = (long) candidateOffset + sub.length();
          if (matchEnd <= endOffset
              && s.regionMatches(candidateOffset, sub, 0, sub.length())
              && isCodePointBoundary(s, (int) matchEnd)) {
            result = i;
            break;
          }
          var cp = s.codePointAt(candidateOffset);
          candidateOffset += Character.charCount(cp);
        }
      }
    }
    if (result < 0 && raiseIfMissing) {
      throw new PyValueError("substring not found");
    }
    return result;
  }

  private static boolean isCodePointBoundary(String s, int offset) {
    return offset == 0 ||
        offset == s.length() ||
        !Character.isHighSurrogate(s.charAt(offset - 1)) ||
        !Character.isLowSurrogate(s.charAt(offset));
  }

  private static String requireString(Object value, String method, String argument) {
    if (value instanceof String s) {
      return s;
    }
    throw new PyTypeError(method + "() argument " + argument
        + " must be str, not " + IntrinsicSupport.typeName(value));
  }

  private static long asIndex(Object value) {
    return switch (value) {
      case Boolean b -> b ? 1L : 0L;
      case Long l -> l;
      case BigInteger b -> {
        try {
          yield b.longValueExact();
        } catch (ArithmeticException e) {
          throw new PyOverflowError(
              "Python int too large to convert to C ssize_t");
        }
      }
      default -> throw new PyTypeError(
          "'" + IntrinsicSupport.typeName(value)
              + "' object cannot be interpreted as an integer");
    };
  }

  private static int normalizeBound(long index, int length) {
    if (index < 0) {
      index = Math.max(0L, index + length);
    } else {
      index = Math.min(index, length);
    }
    return (int) index;
  }

  private static int offset(String s, int codePointIndex) {
    return s.offsetByCodePoints(0, codePointIndex);
  }

  private static int codePointLength(String s) {
    return s.codePointCount(0, s.length());
  }

  private static NotCanonicalException notCanonical(String name, Object value) {
    return new NotCanonicalException(name + " of a non canonical value", value);
  }
}
