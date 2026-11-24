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

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.util.ArrayList;
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

  private static final MethodHandle IS_LATIN1;
  static {
    var spliteratorLatinClass = "A"
        .chars().spliterator().getClass();
    var spliteratorNonLatinClass = "\uD83D\uDE42"
        .chars().spliterator().getClass();
    MethodHandle target;
    if (spliteratorLatinClass != spliteratorNonLatinClass) {
      MethodHandle mh;
      try {
        mh = MethodHandles.lookup()
            .findStatic(StringIntrinsics.class, "isSameClass", MethodType.methodType(boolean.class, Class.class, String.class));
      } catch (NoSuchMethodException | IllegalAccessException e) {
        throw new AssertionError(e);
      }
      target = mh.bindTo(spliteratorLatinClass);
    } else {
      target = MethodHandles.dropArguments(
          MethodHandles.constant(boolean.class, false),
          0, String.class);
    }
    IS_LATIN1 = target;
  }

  private static boolean isSameClass(Class<?> spliteratorClass, String s) {
    return s.chars().spliterator().getClass() == spliteratorClass;
  }

  // acessed by IsLatin1BenchMark
  private static boolean isLatin1(String s) {
    try {
      return (boolean) IS_LATIN1.invokeExact(s);
    } catch(RuntimeException | Error e) {
      throw e;
    } catch (Throwable e) {
      throw new AssertionError(e);
    }
  }


  // CPython 3.14 default. sys.set_int_max_str_digits() is not implemented.
  private static final int MAX_INT_STR_DIGITS = 4_300;
  private static final int MAX_INT_STR_BIT_LENGTH = 14_284;

  //  FIXME use ScopeValue instead
  // Shared by recursive tuple/list/dict repr so mixed cycles are handled correctly.
  private static final ThreadLocal<IdentityHashMap<Object, Boolean>> ACTIVE_REPR =
      ThreadLocal.withInitial(IdentityHashMap::new);

  /// Python `string[index]` and `string[slice]`, using Unicode code points.
  @Intrinsic
  public static Object $getItem$(String s, Object index) {
    var i = ItemIntrinsics.$toIndex$(index, "string");
    if (!isLatin1(s)) {
      return getItemCodePoint(s, i);
    }
    var length = s.length();
    if (i < 0) {
      i += length;
    }
    if (i < 0 || i >= length) {
      throw badIndex("string index out of range");
    }
    var offset = (int) i;
    return "" + s.charAt(offset);
  }

  private static Object getItemCodePoint(String s, long i) {
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
      if (start == s.length()) {   // <=> start == codePointCount(s)
        throw badIndex("string index out of range");
      }
    }
    var end = start + Character.charCount(s.codePointAt(start));
    return s.substring(start, end);
  }


  // Keep the existing generic implementation below.

  private static PyIndexError badIndex(String message) {
    return new PyIndexError(message);
  }

  @Intrinsic
  public static String $str$(Object value) {
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
      case PyRange r -> rangeRepr(r);
      case PySlice s -> s.toString();
      case PyType t -> t.toString();
      default -> throw notCanonical("str", value);
    };
  }

  @Intrinsic
  public static String $repr$(Object value) {
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

  private static String intString(BigInteger value) {
    if (value.bitLength() > MAX_INT_STR_BIT_LENGTH) {
      if (value.bitLength() == MAX_INT_STR_BIT_LENGTH + 1) {
        // To emulate CPython behavior, we still need to check the number of digits
        var result = value.toString();
        var digits = result.charAt(0) == '-'
            ? result.length() - 1
            : result.length();
        if (digits <= MAX_INT_STR_DIGITS) {
          return result;
        }
      }
      throw new PyValueError(
          "Exceeds the limit (" + MAX_INT_STR_DIGITS
              + " digits) for integer string conversion; "
              + "use sys.set_int_max_str_digits() to increase the limit");
    }
    return value.toString();
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

  public static Object _swapcase_(String s) {
    var lowered = s.toLowerCase(Locale.ROOT);
    var builder = new StringBuilder(s.length());
    var lowerIndex = 0;
    for (var i = 0; i < s.length();) {
      var cp = s.codePointAt(i);
      var charCount = Character.charCount(cp);
      var lowerCount = loweredCharCount(cp);
      if (Character.isUpperCase(cp)) {
        builder.append(lowered, lowerIndex, lowerIndex + lowerCount);
      } else if (Character.isLowerCase(cp)) {
        builder.append(s.substring(i, i + charCount).toUpperCase(Locale.ROOT));
      } else {
        builder.appendCodePoint(cp);
      }
      i += charCount;
      lowerIndex += lowerCount;
    }
    return builder.toString();
  }

  public static Object _capitalize_(String s) {
    if (s.isEmpty()) {
      return s;
    }
    if (isLatin1(s)) {
      var first = s.charAt(0);
      return fullTitle(first) + s.substring(1).toLowerCase(Locale.ROOT);
    }
    return capitalizeCodePoint(s);
  }

  private static Object capitalizeCodePoint(String s) {
    var first = s.codePointAt(0);
    var lowered = s.toLowerCase(Locale.ROOT);
    var rest = lowered.substring(loweredCharCount(first));
    return fullTitle(first) + rest;
  }

  public static Object _find_(String s, Object sub) {
    return find(s, sub, PyNone.INSTANCE, PyNone.INSTANCE, false, false, "find");
  }
  public static Object _find_(String s, Object sub, Object start) {
    return find(s, sub, start, PyNone.INSTANCE, false, false, "find");
  }
  public static Object _find_(String s, Object sub, Object start, Object end) {
    return find(s, sub, start, end, false, false, "find");
  }

  public static Object _rfind_(String s, Object sub) {
    return find(s, sub, PyNone.INSTANCE, PyNone.INSTANCE, true, false, "rfind");
  }
  public static Object _rfind_(String s, Object sub, Object start) {
    return find(s, sub, start, PyNone.INSTANCE, true, false, "rfind");
  }
  public static Object _rfind_(String s, Object sub, Object start, Object end) {
    return find(s, sub, start, end, true, false, "rfind");
  }

  public static Object _index_(String s, Object sub) {
    return find(s, sub, PyNone.INSTANCE, PyNone.INSTANCE, false, true, "index");
  }
  public static Object _index_(String s, Object sub, Object start) {
    return find(s, sub, start, PyNone.INSTANCE, false, true, "index");
  }
  public static Object _index_(String s, Object sub, Object start, Object end) {
    return find(s, sub, start, end, false, true, "index");
  }

  public static Object _rindex_(String s, Object sub) {
    return find(s, sub, PyNone.INSTANCE, PyNone.INSTANCE, true, true, "rindex");
  }
  public static Object _rindex_(String s, Object sub, Object start) {
    return find(s, sub, start, PyNone.INSTANCE, true, true, "rindex");
  }
  public static Object _rindex_(String s, Object sub, Object start, Object end) {
    return find(s, sub, start, end, true, true, "rindex");
  }


  public static Object _partition_(String s, Object sep) {
    var separator = requireOperandString(sep);
    if (separator.isEmpty()) {
      throw new PyValueError("empty separator");
    }
    var index = indexOfAligned(s, separator, 0, s.length());
    if (index < 0) {
      return new PyTuple.PyTuple3(s, "", "");
    }
    return new PyTuple.PyTuple3(
        s.substring(0, index),
        separator,
        s.substring(index + separator.length()));
  }

  public static Object _rpartition_(String s, Object sep) {
    var separator = requireOperandString(sep);
    if (separator.isEmpty()) {
      throw new PyValueError("empty separator");
    }
    var index = lastIndexOfAligned(s, separator, 0, s.length());
    if (index < 0) {
      return new PyTuple.PyTuple3("", "", s );
    }
    return new PyTuple.PyTuple3(
        s.substring(0, index),
        separator,
        s.substring(index + separator.length()));
  }

  public static Object _join_(String separator, Object iterable) {
    Object iterator;
    try {
      iterator = IteratorIntrinsics.$iterator$(iterable);
    } catch (PyTypeError _) {
      throw new PyTypeError("can only join an iterable");
    }

    // Python implementation drains the iterable before looking at any item
    // This is not really necessary now given that all our iterators have no side effects
    var items = new ArrayList<>();
    {
      Object item;
      while ((item = IteratorIntrinsics.$next$(iterator)) != null) {
        items.add(item);
      }
    }

    var sep = "";
    var builder = new StringBuilder();
    for (var index = 0; index < items.size(); index++) {
      var item = items.get(index);
      if (!(item instanceof String string)) {
        throw new PyTypeError("sequence item " + index
            + ": expected str instance, "
            + IntrinsicSupport.typeName(item)
            + " found");
      }
      builder.append(sep).append(string);
      sep = separator;
    }
    return builder.toString();
  }

  public static Object _removeprefix_(String s, Object prefix) {
    var p = requireArgumentString(prefix, "removeprefix");
    return s.startsWith(p) && isCodePointBoundary(s, p.length())
        ? s.substring(p.length())
        : s;
  }

  public static Object _removesuffix_(String s, Object suffix) {
    var p = requireArgumentString(suffix, "removesuffix");
    var cut = s.length() - p.length();
    return !p.isEmpty() && s.endsWith(p) && isCodePointBoundary(s, cut)
        ? s.substring(0, cut)
        : s;
  }


  public static Object _zfill_(String s, Object width) {
    var requested = asIndex(width);
    var length = s.codePointCount(0, s.length());
    if (requested <= length) {
      return s;
    }
    if (requested > Integer.MAX_VALUE - 8) {
      throw new UnimplementedFeatureError("string padding exceeds the maximum Java string size");
    }
    var padding = (int) (requested - length);
    var zeros = "0".repeat(padding);
    if (s.startsWith("+") || s.startsWith("-")) {
      return s.charAt(0) + zeros + s.substring(1);
    }
    return zeros + s;
  }


  public static Object _isascii_(String s) {
    if (!isLatin1(s)) {
      return isasciiCodePoint(s);
    }
    for(var i = 0; i < s.length(); i++) {
      if (s.charAt(i) >= 128) {
        return false;
      }
    }
    return true;
  }

  private static boolean isasciiCodePoint(String s) {
    return allCodePoints(s, cp -> cp < 128);
  }


  public static Object _isalpha_(String s) {
    if (s.isEmpty()) {
      return false;
    }
    if (!isLatin1(s)) {
      return isalphaCodePoint(s);
    }
    for (var i = 0; i < s.length(); i++) {
      if (!Character.isLetter(s.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  private static Object isalphaCodePoint(String s) {
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
    if (s.isEmpty()) {
      return false;
    }
    if (!isLatin1(s)) {
      return isdecimalCodePoint(s);
    }
    for (var i = 0; i < s.length(); i++) {
      if (!Character.isDigit(s.charAt(i))) {
        return false;
      }
    }
    return true;
  }

  private static Object isdecimalCodePoint(String s) {
    return allCodePoints(s, cp ->
        Character.getType(cp) == Character.DECIMAL_DIGIT_NUMBER);
  }


  // -- helpers

  private static String requireOperandString(Object value) {
    if (value instanceof String s) {
      return s;
    }
    throw new PyTypeError("must be str, not " + IntrinsicSupport.typeName(value));
  }

  private static String requireArgumentString(Object value, String method) {
    if (value instanceof String s) {
      return s;
    }
    throw new PyTypeError(method + "() argument must be str, not "
        + IntrinsicSupport.typeName(value));
  }

  private static int indexOfAligned(String s, String sub, int from, int to) {
    var limit = to - sub.length();
    var index = s.indexOf(sub, from);
    while (index >= 0 && index <= limit) {
      if (isCodePointBoundary(s, index) && isCodePointBoundary(s, index + sub.length())) {
        return index;
      }
      index = s.indexOf(sub, index + 1);
    }
    return -1;
  }

  private static int lastIndexOfAligned(String s, String sub, int from, int to) {
    var index = s.lastIndexOf(sub, to - sub.length());
    while (index >= from) {
      if (isCodePointBoundary(s, index) && isCodePointBoundary(s, index + sub.length())) {
        return index;
      }
      index = s.lastIndexOf(sub, index - 1);
    }
    return -1;
  }

  private static int loweredCharCount(int cp) {
    return cp == 0x0130 ? 2 : Character.charCount(cp);
  }

  // Java unicode implementation has no primitive to go from one code point to its full titlecase mapping.
  private static String fullTitle(int cp) {
    return switch (cp) {
      case 0x00DF -> "Ss";                       // ß
      case 0x0149 -> "\u02BCN";                  // ŉ -> ʼN  (N stays uppercase)
      case 0x01F0 -> "J\u030C";                  // ǰ -> J̌
      case 0x0390 -> "\u0399\u0308\u0301";       // ΐ
      case 0x03B0 -> "\u03A5\u0308\u0301";       // ΰ
      case 0x0587 -> "\u0535\u0582";             // և
      case 0x1E96 -> "H\u0331";                  // ẖ
      case 0x1E97 -> "T\u0308";                  // ẗ
      case 0x1E98 -> "W\u030A";                  // ẘ
      case 0x1E99 -> "Y\u030A";                  // ẙ
      case 0x1E9A -> "A\u02BE";                  // ẚ
      case 0x1F50 -> "\u03A5\u0313";             // ὐ
      case 0x1F52 -> "\u03A5\u0313\u0300";       // ὒ
      case 0x1F54 -> "\u03A5\u0313\u0301";       // ὔ
      case 0x1F56 -> "\u03A5\u0313\u0342";       // ὖ
      case 0x1FB2 -> "\u1FBA\u0345";             // ᾲ
      case 0x1FB4 -> "\u0386\u0345";             // ᾴ
      case 0x1FB6 -> "\u0391\u0342";             // ᾶ
      case 0x1FB7 -> "\u0391\u0342\u0345";       // ᾷ
      case 0x1FC2 -> "\u1FCA\u0345";             // ῂ
      case 0x1FC4 -> "\u0389\u0345";             // ῄ
      case 0x1FC6 -> "\u0397\u0342";             // ῆ
      case 0x1FC7 -> "\u0397\u0342\u0345";       // ῇ
      case 0x1FD2 -> "\u0399\u0308\u0300";       // ῒ
      case 0x1FD3 -> "\u0399\u0308\u0301";       // ΐ
      case 0x1FD6 -> "\u0399\u0342";             // ῖ
      case 0x1FD7 -> "\u0399\u0308\u0342";       // ῗ
      case 0x1FE2 -> "\u03A5\u0308\u0300";       // ῢ
      case 0x1FE3 -> "\u03A5\u0308\u0301";       // ΰ
      case 0x1FE4 -> "\u03A1\u0313";             // ῤ
      case 0x1FE6 -> "\u03A5\u0342";             // ῦ
      case 0x1FE7 -> "\u03A5\u0308\u0342";       // ῧ
      case 0x1FF2 -> "\u1FFA\u0345";             // ῲ
      case 0x1FF4 -> "\u038F\u0345";             // ῴ
      case 0x1FF6 -> "\u03A9\u0342";             // ῶ
      case 0x1FF7 -> "\u03A9\u0342\u0345";       // ῷ
      case 0xFB00 -> "Ff";                       // ﬀ
      case 0xFB01 -> "Fi";                       // ﬁ
      case 0xFB02 -> "Fl";                       // ﬂ
      case 0xFB03 -> "Ffi";                      // ﬃ
      case 0xFB04 -> "Ffl";                      // ﬄ
      case 0xFB05, 0xFB06 -> "St";               // ﬅ ﬆ
      case 0xFB13 -> "\u0544\u0576";             // ﬓ -> Մն
      case 0xFB14 -> "\u0544\u0565";             // ﬔ
      case 0xFB15 -> "\u0544\u056B";             // ﬕ
      case 0xFB16 -> "\u054E\u0576";             // ﬖ
      case 0xFB17 -> "\u0544\u056D";             // ﬗ
      default -> new String(Character.toChars(Character.toTitleCase(cp)));
    };
  }

  private static long asSliceIndex(Object value, long defaultValue) {
    return switch (value) {
      case PyNone _ -> defaultValue;
      case Boolean b -> b ? 1L : 0L;
      case Long l -> l;
      case BigInteger b -> b.bitLength() <= 63
          ? b.longValue()
          : (b.signum() < 0 ? Long.MIN_VALUE : Long.MAX_VALUE);
      default -> throw new PyTypeError(
          "slice indices must be integers or None or have an __index__ method");
    };
  }

  private static boolean allCodePoints(String s, IntPredicate predicate) {
    return s.codePoints().allMatch(predicate);
  }

  private static long find(String s, Object subObject, Object startObject, Object endObject,
                           boolean reverse, boolean raiseIfMissing, String method) {
    if (!(subObject instanceof String sub)) {
      throw new PyTypeError(method + "() argument 1 must be str, not " + IntrinsicSupport.typeName(subObject));
    }
    var start = asSliceIndex(startObject, 0L);
    var end = asSliceIndex(endObject, Long.MAX_VALUE);

    var charLength = s.length();
    var length = s.codePointCount(0, charLength);

    if (end > length) {
      end = length;
    } else if (end < 0) {
      end = Math.max(0L, end + length);
    }
    if (start < 0) {
      start = Math.max(0L, start + length);
    }

    var result = -1L;
    var subLength = sub.codePointCount(0, sub.length());
    if (end - start >= subLength) {
      if (sub.isEmpty()) {
        result = reverse ? end : start;
      } else {
        // No surrogate pair in s: code point index == char index.
        var simple = length == charLength;
        var startOffset = simple ? (int) start : s.offsetByCodePoints(0, (int) start);
        var endOffset = simple ? (int) end : s.offsetByCodePoints(startOffset, (int) (end - start));
        var offset = reverse
            ? lastIndexOfAligned(s, sub, startOffset, endOffset)
            : indexOfAligned(s, sub, startOffset, endOffset);
        if (offset >= 0) {
          result = simple ? offset : start + s.codePointCount(startOffset, offset);
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

  private static NotCanonicalException notCanonical(String name, Object value) {
    return new NotCanonicalException(name + " of a non canonical value", value);
  }
}
