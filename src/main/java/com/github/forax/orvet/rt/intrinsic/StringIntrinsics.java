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
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.Locale;
import java.util.function.IntPredicate;

import static com.github.forax.orvet.rt.intrinsic.IntrinsicSupport.isInt;
import static com.github.forax.orvet.rt.intrinsic.IntrinsicSupport.normalize;
import static com.github.forax.orvet.rt.intrinsic.IntrinsicSupport.toBigInteger;
import static com.github.forax.orvet.rt.intrinsic.IntrinsicSupport.toDouble;
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

  // Precomputed all Latin1 characters as String for $getItem$ fast path
  private static final class Latin1Chars {
    static final String[] LATIN1_CHARS;

    static {
      var chars = new String[256];
      for (var c = 0; c < 256; c++) {
        chars[c] = String.valueOf((char) c);
      }
      LATIN1_CHARS = chars;
    }
  }

  //  FIXME use ScopeValue instead
  // Shared by recursive tuple/list/dict repr so mixed cycles are handled correctly.
  private static final ThreadLocal<IdentityHashMap<Object, Boolean>> ACTIVE_REPR =
      ThreadLocal.withInitial(IdentityHashMap::new);

  /// Python `string[index]` and `string[slice]`, using Unicode code points.
  @Intrinsic
  public static Object $getItem$(String s, Object index) {
    return switch (index) {
      case PySlice slice -> $slice$(s, slice);
      default -> {
        var i = ItemIntrinsics.$toIndex$(index, "string");
        if (!isLatin1(s)) {
          yield getItemCodePoint(s, i);
        }
        var length = s.length();
        if (i < 0) {
          i += length;
        }
        if (i < 0 || i >= length) {
          throw badIndex("string index out of range");
        }
        yield Latin1Chars.LATIN1_CHARS[s.charAt((int) i)];
      }
    };
  }

  private static Object getItemCodePoint(String s, long i) {
    // Note: this implementation uses offsetByCodePoints()
    // which is linear, one solution is to use a concurrent weak cache
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


  @Intrinsic
  public static String $slice$(String s, PySlice slice) {
    var length = s.codePointCount(0, s.length());
    var resolved = slice.resolve(length);

    // Preserve the original String for s[:].
    if (resolved.step() == 1 && resolved.count() == length) {
      return s;
    }
    if (resolved.count() == 0) {
      return "";
    }

    // Contiguous slice: use substring if possible.
    var latin1 = isLatin1(s);
    if (resolved.step() == 1) {
      var from = latin1
          ? (int) resolved.start()
          : s.offsetByCodePoints(0, (int) resolved.start());
      var to = latin1
          ? (int) resolved.stop()
          : s.offsetByCodePoints(0, (int) resolved.stop());
      return s.substring(from, to);
    }
    return latin1 ? stridedSlice(s, resolved) : stridedSliceCodePoint(s, resolved);
  }

  private static String stridedSlice(String s, PySlice.Resolved resolved) {
    var count = (int) resolved.count();
    var builder = new StringBuilder(count);
    var position = resolved.start();
    for (var i = 0; i < count; i++) {
      builder.append(s.charAt((int) position));
      if (i + 1 < count) {
        position += resolved.step();
      }
    }
    return builder.toString();
  }

  // General Unicode: navigate by code points
  private static String stridedSliceCodePoint(String s, PySlice.Resolved resolved) {
    var count = (int) resolved.count();
    var builder = new StringBuilder(count);
    var offset = s.offsetByCodePoints(0, (int) resolved.start());
    for (var i = 0; i < count; i++) {
      builder.appendCodePoint(s.codePointAt(offset));
      if (i + 1 < count) {
        offset = s.offsetByCodePoints(offset, (int) resolved.step());
      }
    }
    return builder.toString();
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

  /// Python's `ascii()`: the repr with the non-ASCII characters escaped.
  public static String ascii(Object value) {
    var repr = $repr$(value);
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
        .append($repr$(normalize(bigStart(range)))).append(", ")
        .append($repr$(normalize(bigStop(range))));
    if (!step.equals(1L)) {
      builder.append(", ").append($repr$(step));
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
    // Final_Sigma is the only context-sensitive lowercase mapping: CPython maps
    // Σ to ς using the Unicode Case_Ignorable property, while the JDK toLowerCase
    // uses its own context rule and disagrees with CPython.
    if (s.indexOf(0x3a3) < 0) {   // no uppercase sigma: no context-sensitive mapping
      return s.toLowerCase(Locale.ROOT);
    }
    var cps = s.codePoints().toArray();
    var builder = new StringBuilder(cps.length);
    for (var i = 0; i < cps.length; i++) {
      appendLowerCase(builder, cps, i);   // Σ -> isFinalSigma(cps, i) ? ς : σ, else ROOT-lowercase
    }
    return builder.toString();
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
    // We use it to pre-compute the total length of the resulting string
    var items = new ArrayList<>();
    var total = 0L;
    {
      Object item;
      while ((item = IteratorIntrinsics.$next$(iterator)) != null) {
        if (item instanceof String s) {
          total += s.length();
        }
        items.add(item);
      }
    }
    total += Math.max(0L, items.size() - 1L) * separator.length();

    var sep = "";
    var builder = new StringBuilder((int) Math.min(total, Integer.MAX_VALUE - 8));
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
    var pre = requireArgumentString(prefix, "removeprefix");
    return s.startsWith(pre) && isCodePointBoundary(s, pre.length())
        ? s.substring(pre.length())
        : s;
  }

  public static Object _removesuffix_(String s, Object suffix) {
    var suf = requireArgumentString(suffix, "removesuffix");
    var cut = s.length() - suf.length();
    return !suf.isEmpty() && s.endsWith(suf) && isCodePointBoundary(s, cut)
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
      return false;
    }
    for(var i = 0; i < s.length(); i++) {
      if (s.charAt(i) >= 128) {
        return false;
      }
    }
    return true;
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


  // split / rsplit
  public static Object _split_(String s) {
    return splitWhitespace(s, -1, false);
  }
  public static Object _split_(String s, Object separator) {
    return splitString(s, separator, -1, false);
  }
  public static Object _split_(String s, Object separator, Object maxsplit) {
    return splitString(s, separator, asIndex(maxsplit), false);
  }
  public static Object _rsplit_(String s) {
    return splitWhitespace(s, -1, true);
  }
  public static Object _rsplit_(String s, Object separator) {
    return splitString(s, separator, -1, true);
  }
  public static Object _rsplit_(String s, Object separator, Object maxsplit) {
    return splitString(s, separator, asIndex(maxsplit), true);
  }

  private static Object splitString(String s, Object separator, long maxsplit, boolean reverse) {
    if (separator == PyNone.INSTANCE) {
      return splitWhitespace(s, maxsplit, reverse);
    }
    if (!(separator instanceof String sep)) {
      var typeName = separator == null
          ? "null"
          : IntrinsicSupport.typeName(separator);
      throw new PyTypeError(
          (reverse ? "rsplit" : "split")
              + "() separator must be str or None, not " + typeName);
    }
    if (sep.isEmpty()) {
      throw new PyValueError("empty separator");
    }
    return splitWithSeparator(s, sep, maxsplit, reverse);
  }

  private static PyList<String> splitWhitespace(String s, long maxsplit, boolean reverse) {
    var cps = s.codePoints().toArray();
    var length = cps.length;
    var result = new PyList<String>();
    if (length == 0) {
      return result;
    }
    if (maxsplit == 0) {
      if (reverse) {
        var end = length;
        while (end > 0 && isPythonWhitespace(cps[end - 1])) {
          end--;
        }
        if (end > 0) {
          result.append(substringByCodePoints(s, 0, end));
        }
      } else {
        var start = 0;
        while (start < length && isPythonWhitespace(cps[start])) {
          start++;
        }
        if (start < length) {
          result.append(substringByCodePoints(s, start, length));
        }
      }
      return result;
    }

    var index = 0;
    if (!reverse) {
      // Leading whitespace is discarded.
      while (index < length && isPythonWhitespace(cps[index])) {
        index++;
      }
      var splits = 0;
      while (index < length) {
        var start = index;
        while (index < length && !isPythonWhitespace(cps[index])) {
          index++;
        }
        result.append(substringByCodePoints(s, start, index));
        if (index == length) {
          break;
        }
        // A whitespace run separates two fields.
        while (index < length && isPythonWhitespace(cps[index])) {
          index++;
        }
        splits++;
        if (maxsplit >= 0 && splits >= maxsplit) {
          if (index < length) {
            result.append(substringByCodePoints(s, index, length));
          }
          break;
        }
      }
      return result;
    }

    // rsplit: process from right to left, then reverse the result.
    index = length;
    while (index > 0 && isPythonWhitespace(cps[index - 1])) {
      index--;
    }
    var splits = 0;
    while (index > 0) {
      var end = index;
      while (index > 0 && !isPythonWhitespace(cps[index - 1])) {
        index--;
      }
      result.append(substringByCodePoints(s, index, end));
      if (index == 0) {
        break;
      }
      while (index > 0 && isPythonWhitespace(cps[index - 1])) {
        index--;
      }
      splits++;
      if (maxsplit >= 0 && splits >= maxsplit) {
        if (index > 0) {
          result.append(substringByCodePoints(s, 0, index));
        }
        break;
      }
    }
    return reverseList(result);
  }

  private static PyList<String> splitWithSeparator(String s, String separator, long maxsplit, boolean reverse) {
    var source = s.codePoints().toArray();
    var delimiter = separator.codePoints().toArray();
    var delimiterLength = delimiter.length;
    var result = new PyList<String>();
    if (!reverse) {
      var start = 0;
      var index = 0;
      var splits = 0;
      while (index + delimiterLength <= source.length && (maxsplit < 0 || splits < maxsplit)) {
        if (matchesAt(source, delimiter, index)) {
          result.append(substringByCodePoints(s, start, index));
          index += delimiterLength;
          start = index;
          splits++;
        } else {
          index++;
        }
      }
      result.append(substringByCodePoints(s, start, source.length));
      return result;
    }

    var end = source.length;
    var index = source.length - delimiterLength;
    var splits = 0;
    while (index >= 0 && (maxsplit < 0 || splits < maxsplit)) {
      if (matchesAt(source, delimiter, index)) {
        result.append(substringByCodePoints(
            s, index + delimiterLength, end));

        end = index;
        index -= delimiterLength;
        splits++;
      } else {
        index--;
      }
    }
    result.append(substringByCodePoints(s, 0, end));
    return reverseList(result);
  }

  private static PyList<String> reverseList(PyList<String> source) {
    var result = new PyList<String>(source.size());
    for (var i = source.size() - 1; i >= 0; i--) {
      result.append(source.get(i));
    }
    return result;
  }


  // strip / lstrip / rstrip
  public static Object _strip_(String s) {
    return stripString(s, null, true, true);
  }
  public static Object _strip_(String s, Object chars) {
    return stripString(s, stripCharacters(chars, "strip"), true, true);
  }
  public static Object _lstrip_(String s) {
    return stripString(s, null, true, false);
  }
  public static Object _lstrip_(String s, Object chars) {
    return stripString(s, stripCharacters(chars, "lstrip"), true, false);
  }
  public static Object _rstrip_(String s) {
    return stripString(s, null, false, true);
  }
  public static Object _rstrip_(String s, Object chars) {
    return stripString(s, stripCharacters(chars, "rstrip"), false, true);
  }

  private static String stripString(String s, String chars, boolean left, boolean right) {
    if (isLatin1(s)) {
      return stripLatin1(s, chars, left, right);
    }
    return stripCodePoint(s, chars, left, right);
  }

  private static String stripLatin1(String s, String chars, boolean left, boolean right) {
    var start = 0;
    var end = s.length();
    if (left) {
      while (start < end &&
          isStripCharacterLatin1(s.charAt(start), chars)) {
        start++;
      }
    }
    if (right) {
      while (end > start &&
          isStripCharacterLatin1(s.charAt(end - 1), chars)) {
        end--;
      }
    }
    if (start == 0 && end == s.length()) {
      return s;
    }
    return s.substring(start, end);
  }

  private static boolean isStripCharacterLatin1(char c, String chars) {
    return chars == null
        ? isPythonWhitespace(c)
        : chars.indexOf(c) >= 0;
  }

  private static String stripCodePoint(String s, String chars, boolean left, boolean right) {
    var cps = s.codePoints().toArray();
    var start = 0;
    var end = cps.length;
    if (left) {
      while (start < end && isStripCharacter(cps[start], chars)) {
        start++;
      }
    }
    if (right) {
      while (end > start && isStripCharacter(cps[end - 1], chars)) {
        end--;
      }
    }
    if (start == 0 && end == cps.length) {
      return s;
    }
    return substringByCodePoints(s, start, end);
  }

  private static boolean isStripCharacter(int cp, String chars) {
    if (chars == null) {
      return isPythonWhitespace(cp);
    }
    return chars.codePoints().anyMatch(character -> character == cp);
  }


  // replace
  public static Object _replace_(String s, Object oldValue, Object newValue) {
    return replaceString(s, oldValue, newValue, -1);
  }
  public static Object _replace_(String s, Object oldValue, Object newValue, Object count) {
    return replaceString(s, oldValue, newValue, asIndex(count));
  }

  private static String replaceString(String s, Object oldObject, Object newObject, long maxcount) {
    var oldValue = requireMethodString(oldObject, "replace", 1);
    var newValue = requireMethodString(newObject, "replace", 2);
    if (maxcount == 0) {
      return s;
    }
    if (isLatin1(s)) {
      return replaceLatin1(s, oldValue, newValue, maxcount);
    }
    return replaceCodePoint(s, oldValue, newValue, maxcount);
  }

  private static String replaceLatin1(String s, String oldValue, String newValue, long maxcount) {
    var builder = new StringBuilder();

    // Empty search string: insert at every character boundary.
    if (oldValue.isEmpty()) {
      var replacements = 0L;
      for (var i = 0; i <= s.length(); i++) {
        if (maxcount < 0 || replacements < maxcount) {
          builder.append(newValue);
          replacements++;
        }
        if (i < s.length()) {
          builder.append(s.charAt(i));
        }
      }
      return builder.toString();
    }

    var index = 0;
    var replacements = 0L;
    while (maxcount < 0 || replacements < maxcount) {
      var match = s.indexOf(oldValue, index);
      if (match < 0) {
        break;
      }
      builder.append(s, index, match);
      builder.append(newValue);
      index = match + oldValue.length();
      replacements++;
    }
    builder.append(s, index, s.length());
    return builder.toString();
  }

  private static String replaceCodePoint(String s, Object oldObject, Object newObject, long maxcount) {
    var oldValue = requireMethodString(oldObject, "replace", 1);
    var newValue = requireMethodString(newObject, "replace", 2);
    if (maxcount == 0) {
      return s;
    }
    var source = s.codePoints().toArray();
    var oldCps = oldValue.codePoints().toArray();
    var builder = new StringBuilder();

    // Python inserts the replacement at every code-point boundary
    // when the search string is empty.
    if (oldCps.length == 0) {
      var replacements = 0L;
      for (var i = 0; i <= source.length; i++) {
        if (maxcount < 0 || replacements < maxcount) {
          builder.append(newValue);
          replacements++;
        }
        if (i < source.length) {
          builder.appendCodePoint(source[i]);
        }
      }
      return builder.toString();
    }

    var index = 0;
    var replacements = 0L;
    while (index < source.length) {
      if ((maxcount < 0 || replacements < maxcount) && matchesAt(source, oldCps, index)) {
        builder.append(newValue);
        index += oldCps.length;
        replacements++;
      } else {
        builder.appendCodePoint(source[index]);
        index++;
      }
    }
    return builder.toString();
  }


  // startswith / endswith

  public static Object _startswith_(String s, Object prefix) {
    return startsOrEndsWith(s, prefix, PyNone.INSTANCE, PyNone.INSTANCE, false);
  }
  public static Object _startswith_(String s, Object prefix, Object start) {
    return startsOrEndsWith(s, prefix, start, PyNone.INSTANCE, false);
  }
  public static Object _startswith_(String s, Object prefix, Object start, Object end) {
    return startsOrEndsWith(s, prefix, start, end, false);
  }

  public static Object _endswith_(String s, Object suffix) {
    return startsOrEndsWith(s, suffix, PyNone.INSTANCE, PyNone.INSTANCE, true);
  }
  public static Object _endswith_(String s, Object suffix, Object start) {
    return startsOrEndsWith(s, suffix, start, PyNone.INSTANCE, true);
  }
  public static Object _endswith_(String s, Object suffix, Object start, Object end) {
    return startsOrEndsWith(s, suffix, start, end, true);
  }

  private static boolean startsOrEndsWith(String s, Object prefixObject,
                                          Object startObject, Object endObject, boolean reverse) {
    if (isLatin1(s)) {
      return startsOrEndsWithLatin1(s, prefixObject, startObject, endObject, reverse);
    }
    return startsOrEndsWithCodePoint(s, prefixObject, startObject, endObject, reverse);
  }

  private static boolean startsOrEndsWithLatin1(String s, Object prefixObject,
                                                Object startObject, Object endObject, boolean reverse) {

    var start = normalizeStartBound(startObject, s.length());
    var end = normalizeStringBound(endObject, s.length(), s.length());
    if (prefixObject instanceof String prefix) {
      return matchesPrefixOrSuffixLatin1(s, prefix, start, end, reverse);
    }
    if (prefixObject instanceof PyTuple tuple) {
      var size = TupleIntrinsics.$size$(tuple);
      for (var i = 0L; i < size; i++) {
        var item = TupleIntrinsics.$get$(tuple, i);
        if (!(item instanceof String prefix)) {
          var method = reverse ? "endswith" : "startswith";
          throw new PyTypeError(
              "tuple for " + method + " must only contain str, not "
                  + IntrinsicSupport.typeName(item));
        }
        if (matchesPrefixOrSuffixLatin1(s, prefix, start, end, reverse)) {
          return true;
        }
      }
      return false;
    }
    var method = reverse ? "endswith" : "startswith";
    throw new PyTypeError(method + " first arg must be str or a tuple of str, not "
            + IntrinsicSupport.typeName(prefixObject));
  }

  private static boolean startsOrEndsWithCodePoint(String s, Object prefixObject, Object startObject, Object endObject, boolean reverse) {
    var source = s.codePoints().toArray();
    var start = normalizeStartBound(startObject, source.length);
    var end = normalizeStringBound(endObject, source.length, source.length);

    var method = reverse ? "endswith" : "startswith";
    if (prefixObject instanceof String prefix) {
      return matchesPrefixOrSuffix(source, prefix, start, end, reverse);
    }

    if (prefixObject instanceof PyTuple tuple) {
      var size = TupleIntrinsics.$size$(tuple);
      for (var i = 0L; i < size; i++) {
        var item = TupleIntrinsics.$get$(tuple, i);
        if (!(item instanceof String prefix)) {
          throw new PyTypeError("tuple for " + method + " must only contain str, not "
                  + IntrinsicSupport.typeName(item));
        }
        if (matchesPrefixOrSuffix(source, prefix, start, end, reverse)) {
          return true;
        }
      }
      return false;
    }
    throw new PyTypeError(method + " first arg must be str or a tuple of str, not "
        + IntrinsicSupport.typeName(prefixObject));
  }

  private static boolean matchesPrefixOrSuffix(int[] source, String value, long start, int end, boolean reverse) {
    if (start > end) {
      return false;
    }
    var candidate = value.codePoints().toArray();
    if (candidate.length > end - start) {
      return false;
    }
    var position = reverse
        ? end - candidate.length
        : (int) start;
    return matchesAt(source, candidate, position);
  }


  public static Object _count_(String s, Object sub) {
    return countString(s, sub, PyNone.INSTANCE, PyNone.INSTANCE);
  }
  public static Object _count_(String s, Object sub, Object start) {
    return countString(s, sub, start, PyNone.INSTANCE);
  }
  public static Object _count_(String s, Object sub, Object start, Object end) {
    return countString(s, sub, start, end);
  }

  private static Object countString(String s, Object subObject, Object startObject, Object endObject) {
    var sub = requireMethodString(subObject, "count", 1);
    if (isLatin1(s)) {
      return countLatin1(s, sub, startObject, endObject);
    }
    return countCodePoint(s, sub, startObject, endObject);
  }

  private static Object countLatin1(String s, String sub, Object startObject, Object endObject) {
    var start = normalizeStartBound(startObject, s.length());
    var end = normalizeStringBound(endObject, s.length(), s.length());
    if (start > end) {
      return 0L;
    }
    if (sub.isEmpty()) {
      return end - start + 1;
    }
    var count = 0L;
    var index = (int) start;
    var limit = end - sub.length();
    while (index <= limit) {
      var match = s.indexOf(sub, index);
      if (match < 0 || match > limit) {
        break;
      }
      count++;
      index = match + sub.length();
    }
    return count;
  }

  private static Object countCodePoint(String s, Object subObject, Object startObject, Object endObject) {
    var sub = requireMethodString(subObject, "count", 1);
    var source = s.codePoints().toArray();
    var needle = sub.codePoints().toArray();
    var start = normalizeStartBound(startObject, source.length);
    var end = normalizeStringBound(endObject, source.length, source.length);
    if (start > end) {
      return 0L;
    }
    if (needle.length == 0) {
      return end - start + 1;
    }

    var count = 0L;
    var index = (int) start;
    while (index + needle.length <= end) {
      if (matchesAt(source, needle, index)) {
        count++;
        index += needle.length;
      } else {
        index++;
      }
    }
    return count;
  }


  // Unicode character classification
  public static Object _isdigit_(String s) {
    return !s.isEmpty() && allCodePoints(s, StringIntrinsics::isDigit);
  }

  public static Object _isalnum_(String s) {
    return !s.isEmpty() && allCodePoints(s, StringIntrinsics::isAlphaNumeric);
  }

  public static Object _isspace_(String s) {
    return !s.isEmpty() && allCodePoints(s, StringIntrinsics::isPythonWhitespace);
  }

  public static Object _islower_(String s) {
    var hasCased = false;
    for (var cp : s.codePoints().toArray()) {
      if (isCased(cp)) {
        hasCased = true;
        if (!Character.isLowerCase(cp)) {
          return false;
        }
      }
    }
    return hasCased;
  }

  public static Object _isupper_(String s) {
    var hasCased = false;
    for (var cp : s.codePoints().toArray()) {
      if (isCased(cp)) {
        hasCased = true;
        if (!Character.isUpperCase(cp)) {
          return false;
        }
      }
    }
    return hasCased;
  }

  private static boolean isPythonWhitespace(int cp) {
    // Python also treats NEL and the ASCII information separators
    // as whitespace.
    return cp == 0x85
        || (cp >= 0x1c && cp <= 0x1f)
        || Character.isWhitespace(cp)
        || Character.isSpaceChar(cp);
  }

  private static boolean isDigit(int cp) {
    // Python's sdigit() accepts Unicode Numeric_Type=Decimal and
    // Numeric_Type=Digit, but not Numeric_Type=Numeric.
    // The switch below contains the 128 Unicode 16.0 code points having
    // Numeric_Type=Digit.
    if (Character.getType(cp) == Character.DECIMAL_DIGIT_NUMBER) {
      return true;
    }
    return switch (cp) {
      case 0x00B2, 0x00B3, 0x00B9,
           0x1369, 0x136A, 0x136B, 0x136C,
           0x136D, 0x136E, 0x136F, 0x1370, 0x1371,
           0x19DA,
           0x2070,
           0x2074, 0x2075, 0x2076, 0x2077,
           0x2078, 0x2079,
           0x2080, 0x2081, 0x2082, 0x2083, 0x2084,
           0x2085, 0x2086, 0x2087, 0x2088, 0x2089,
           0x2460, 0x2461, 0x2462, 0x2463, 0x2464,
           0x2465, 0x2466, 0x2467, 0x2468,
           0x2474, 0x2475, 0x2476, 0x2477, 0x2478,
           0x2479, 0x247A, 0x247B, 0x247C,
           0x2488, 0x2489, 0x248A, 0x248B, 0x248C,
           0x248D, 0x248E, 0x248F, 0x2490,
           0x24EA,
           0x24F5, 0x24F6, 0x24F7, 0x24F8, 0x24F9,
           0x24FA, 0x24FB, 0x24FC, 0x24FD,
           0x24FF,
           0x2776, 0x2777, 0x2778, 0x2779, 0x277A,
           0x277B, 0x277C, 0x277D, 0x277E,
           0x2780, 0x2781, 0x2782, 0x2783, 0x2784,
           0x2785, 0x2786, 0x2787, 0x2788,
           0x278A, 0x278B, 0x278C, 0x278D, 0x278E,
           0x278F, 0x2790, 0x2791, 0x2792,
           0x10A40, 0x10A41, 0x10A42, 0x10A43,
           0x10E60, 0x10E61, 0x10E62, 0x10E63, 0x10E64,
           0x10E65, 0x10E66, 0x10E67, 0x10E68,
           0x11052, 0x11053, 0x11054, 0x11055, 0x11056,
           0x11057, 0x11058, 0x11059, 0x1105A,
           0x1F100, 0x1F101, 0x1F102, 0x1F103, 0x1F104,
           0x1F105, 0x1F106, 0x1F107, 0x1F108, 0x1F109,
           0x1F10A -> true;
      default -> false;
    };
  }

  private static boolean isAlphaNumeric(int cp) {
    return isLetter(cp)
        || isDigit(cp)
        || Character.getType(cp) == Character.LETTER_NUMBER
        || Character.getType(cp) == Character.OTHER_NUMBER;
  }

  private static boolean isLetter(int cp) {
    return switch (Character.getType(cp)) {
      case Character.UPPERCASE_LETTER,
           Character.LOWERCASE_LETTER,
           Character.TITLECASE_LETTER,
           Character.MODIFIER_LETTER,
           Character.OTHER_LETTER -> true;
      default -> false;
    };
  }

  private static boolean isCased(int cp) {
    return Character.isUpperCase(cp)
        || Character.isLowerCase(cp)
        || Character.isTitleCase(cp);
  }


  // title
  public static Object _title_(String s) {
    var cps = s.codePoints().toArray();
    var builder = new StringBuilder(s.length());
    var previousWasCased = false;
    for (var i = 0; i < cps.length; i++) {
      var cp = cps[i];
      if (isCased(cp)) {
        if (previousWasCased) {
          appendLowerCase(builder, cps, i);
        } else {
          builder.append(fullTitle(cp));
        }
        previousWasCased = true;
      } else {
        builder.appendCodePoint(cp);
        previousWasCased = false;
      }
    }
    return builder.toString();
  }


  // casefold
  public static Object _casefold_(String s) {
    var builder = new StringBuilder(s.length());
    for (var cp : s.codePoints().toArray()) {
      appendCaseFolded(builder, cp);
    }
    return builder.toString();
  }

  private static void appendCaseFolded(StringBuilder builder, int cp) {
    // Unicode default case folding preserves dotless i.
    if (cp == 0x0131) {
      builder.appendCodePoint(cp);
      return;
    }
    if (cp == 0x1e9e) {
      builder.append("ss");
      return;
    }

    // Uppercase followed by lowercase gives full expansions for many
    // characters which have no one-code-point lowercase mapping:
    // sharp-s, ligatures, Greek iota-subscript forms, etc.
    var value = new String(Character.toChars(cp))
        .toUpperCase(Locale.ROOT)
        .toLowerCase(Locale.ROOT);
    value.codePoints().forEach(folded -> {
      // Cherokee uses uppercase characters as its case-folded form.
      if ((folded >= 0xab70 && folded <= 0xabbf)
          || (folded >= 0x13f8 && folded <= 0x13fd)) {
        builder.appendCodePoint(Character.toUpperCase(folded));
      } else {
        builder.appendCodePoint(folded);
      }
    });
  }


  // splitlines

  public static Object _splitlines_(String s) {
    return splitLines(s, false);
  }
  public static Object _splitlines_(String s, Object keepends) {
    return splitLines(s, TruthinessIntrinsics.$truthy$(keepends));
  }

  private static PyList<String> splitLines(String s, boolean keepends) {
    var result = new PyList<String>();
    var length = s.length();
    var charStart = 0;
    for (var i = 0; i < length; ) {
      var cp = s.codePointAt(i);
      if (!isLineBreak(cp)) {
        i += Character.charCount(cp);
        continue;
      }
      var end = i + Character.charCount(cp);
      if (cp == '\r' && end < length && s.charAt(end) == '\n') {
        end++;
      }
      result.append(s.substring(charStart, keepends ? end : i));
      charStart = end;
      i = end;
    }
    if (charStart < length) {
      result.append(s.substring(charStart, length));
    }
    return result;
  }




  // Shared helpers for the methods above.

  private static boolean isLineBreak(int cp) {
    return switch (cp) {
      case '\n', '\r', '\u000b', '\u000c', '\u001c', '\u001d', '\u001e',
           '\u0085', '\u2028', '\u2029' -> true;
      default -> false;
    };
  }

  private static boolean matchesPrefixOrSuffixLatin1(String source, String value,
                                                     long start, int end, boolean reverse) {
    if (start > end || value.length() > end - start) {
      return false;
    }
    var position = reverse
        ? end - value.length()
        : (int) start;
    return source.startsWith(value, position);
  }

  private static String stripCharacters(Object chars, String method) {
    if (chars == PyNone.INSTANCE) {
      return null;
    }
    if (chars instanceof String s) {
      return s;
    }
    throw new PyTypeError(method + " arg must be None or str");
  }

  private static void appendLowerCase(StringBuilder builder, int[] cps, int index) {
    var cp = cps[index];
    if (cp == 0x03a3) {
      builder.appendCodePoint(isFinalSigma(cps, index) ? 0x03c2 : 0x03c3);
      return;
    }
    builder.append(new String(Character.toChars(cp)).toLowerCase(Locale.ROOT));
  }

  // cased case-ignorable* SIGMA !(case-ignorable* cased)
  private static boolean isFinalSigma(int[] cps, int index) {
    var j = index - 1;
    while (j >= 0 && isCaseIgnorable(cps[j])) {
      j--;
    }
    if (j < 0 || !isCased(cps[j])) {
      return false;
    }
    j = index + 1;
    while (j < cps.length && isCaseIgnorable(cps[j])) {
      j++;
    }
    return j == cps.length || !isCased(cps[j]);
  }

  // Approximation of the Unicode Case_Ignorable property.
  private static boolean isCaseIgnorable(int cp) {
    return switch (Character.getType(cp)) {
      case Character.NON_SPACING_MARK,
           Character.ENCLOSING_MARK,
           Character.FORMAT,
           Character.MODIFIER_LETTER,
           Character.MODIFIER_SYMBOL -> true;
      default -> switch (cp) {
        case '\'', '.', ':', 0x00b7, 0x0387, 0x055f, 0x05f4, 0x2018, 0x2019,
             0x2024, 0x2027, 0xfe13, 0xfe52, 0xfe55, 0xff07, 0xff0e, 0xff1a -> true;
        default -> false;
      };
    };
  }

  private static long normalizeStartBound(Object value, int length) {
    var bound = asSliceIndex(value, 0);
    return bound < 0 ? Math.max(0L, bound + length) : bound;
  }

  private static String requireMethodString(Object value, String method, int argument) {
    if (value instanceof String s) {
      return s;
    }
    throw new PyTypeError(method + "() argument " + argument
            + " must be str, not " + IntrinsicSupport.typeName(value));
  }

  private static int normalizeStringBound(Object value, int length, int defaultValue) {
    var bound = asSliceIndex(value, defaultValue);
    if (bound < 0) {
      bound = Math.max(0L, bound + length);
    } else {
      bound = Math.min(bound, length);
    }
    return (int) bound;
  }

  private static String substringByCodePoints(String s, int start, int end) {
    if (start == end) {
      return "";
    }
    var from = s.offsetByCodePoints(0, start);
    var to = s.offsetByCodePoints(from, end - start);
    return s.substring(from, to);
  }

  private static boolean matchesAt(int[] source, int[] needle, int position) {
    if (position < 0 || position + needle.length > source.length) {
      return false;
    }
    for (var i = 0; i < needle.length; i++) {
      if (source[position + i] != needle[i]) {
        return false;
      }
    }
    return true;
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
