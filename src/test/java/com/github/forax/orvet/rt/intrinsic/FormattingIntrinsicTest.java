package com.github.forax.orvet.rt.intrinsic;

import com.github.forax.orvet.engine.Engine;
import com.github.forax.orvet.rt.ModuleContext;
import com.github.forax.orvet.rt.PyDict;
import com.github.forax.orvet.rt.PyList;
import com.github.forax.orvet.rt.PyNone;
import com.github.forax.orvet.rt.PyRange;
import com.github.forax.orvet.rt.error.PyIndexError;
import com.github.forax.orvet.rt.error.PyKeyError;
import com.github.forax.orvet.rt.error.PyOverflowError;
import com.github.forax.orvet.rt.error.PyTypeError;
import com.github.forax.orvet.rt.error.PyValueError;
import com.github.forax.orvet.rt.error.UnimplementedFeatureError;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static com.github.forax.orvet.rt.intrinsic.FormattingIntrinsics.formatValue;
import static com.github.forax.orvet.rt.intrinsic.FormattingIntrinsics.stringMod;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class FormattingIntrinsicTest {

  private ModuleContext run(String source) {
    var context = new ModuleContext();
    Engine.run(context, source);
    return context;
  }

  private static Object args(Object... elements) {
    return TupleIntrinsics.fromArray(elements);
  }

  private static PyDict<String, Object> withA(Object value) {
    var dict = new PyDict<String, Object>();
    dict.put("a", value);
    return dict;
  }

  private static String fmt(String template, Object... args) {
    return FormattingIntrinsics.format(template, args);
  }

  private static String fmtValue(Object value, String spec) {
    return FormattingIntrinsics.formatValue(value, spec);
  }

  @Nested
  public final class StringMod {

    @Test
    public void integerConversions() {
      assertEquals("42", stringMod("%d", 42L));
      assertEquals("42", stringMod("%i", 42L));
      assertEquals("42", stringMod("%u", 42L));
      assertEquals("-42", stringMod("%d", -42L));
      assertEquals("1", stringMod("%d", true));
      assertEquals("123456789012345678901234567890",
          stringMod("%d", new BigInteger("123456789012345678901234567890")));
      assertEquals("42", stringMod("%d", 42.0));
      assertEquals("3", stringMod("%d", 3.5));  // floats are truncated towards zero
    }

    @Test
    public void radixConversions() {
      assertEquals("ff", stringMod("%x", 255L));
      assertEquals("FF", stringMod("%X", 255L));
      assertEquals("10", stringMod("%o", 8L));
      assertEquals("-2a", stringMod("%x", -42L));
      assertEquals("0xff", stringMod("%#x", 255L));
      assertEquals("0XFF", stringMod("%#X", 255L));
      assertEquals("0o10", stringMod("%#o", 8L));
      assertEquals("0x0", stringMod("%#x", 0L));
      assertEquals("1", stringMod("%x", true));
      assertEquals("c9f2c9cd04674edea40000000",
          stringMod("%x", new BigInteger("1000000000000000000000000000000")));
    }

    @Test
    public void integerFlagsAndWidth() {
      assertEquals("   42", stringMod("%5d", 42L));
      assertEquals("42   ", stringMod("%-5d", 42L));
      assertEquals("-0042", stringMod("%05d", -42L));
      assertEquals("+0042", stringMod("%+05d", 42L));
      assertEquals("42   ", stringMod("%-05d", 42L));   // '-' disables zero padding
      assertEquals("   42", stringMod("% 5d", 42L));
      assertEquals("-42", stringMod("% d", -42L));
      assertEquals("+42", stringMod("%+d", 42L));
      assertEquals("0x007", stringMod("%#5.3x", 7L));
      assertEquals("7", stringMod("%.0d", 7L));
    }

    @Test
    public void floatConversions() {
      assertEquals("3.000000", stringMod("%f", 3L));
      assertEquals("3.14", stringMod("%.2f", 3.14159));
      assertEquals("1.234560e+02", stringMod("%e", 123.456));
      assertEquals("1.234560E+02", stringMod("%E", 123.456));
      assertEquals("2", stringMod("%.0f", 2.5));    // rounded half-to-even
      assertEquals("2", stringMod("%.0f", 1.5));
      assertEquals("4", stringMod("%.0f", 3.5));
      assertEquals("3", stringMod("%.0f", 3.0));
      assertEquals("3.", stringMod("%#.0f", 3.0));
    }

    @Test
    public void generalFormat() {
      assertEquals("100000", stringMod("%g", 100000.0));
      assertEquals("1e+08", stringMod("%g", 100000000.0));
      assertEquals("1.23e+04", stringMod("%.3g", 12345.0));
      assertEquals("0.0001", stringMod("%g", 0.0001));
      assertEquals("1.234E-05", stringMod("%G", 1.234e-5));
      assertEquals("0", stringMod("%g", 0.0));
      assertEquals("0.00000", stringMod("%#g", 0.0));
      assertEquals("-0", stringMod("%g", -0.0));
    }

    @Test
    public void specialFloats() {
      assertEquals("inf", stringMod("%f", Double.POSITIVE_INFINITY));
      assertEquals("-inf", stringMod("%e", Double.NEGATIVE_INFINITY));
      assertEquals("INF", stringMod("%E", Double.POSITIVE_INFINITY));
      assertEquals("nan", stringMod("%g", Double.NaN));
      assertEquals("0000000inf", stringMod("%010f", Double.POSITIVE_INFINITY));
      assertEquals("+inf", stringMod("%+f", Double.POSITIVE_INFINITY));
    }

    @Test
    public void stringAndReprConversions() {
      assertEquals("42", stringMod("%s", 42L));
      assertEquals("None", stringMod("%s", PyNone.INSTANCE));
      assertEquals("True", stringMod("%s", true));
      assertEquals("[1, 2]", stringMod("%s", new PyList<>(1L, 2L)));
      assertEquals("'ab'", stringMod("%r", "ab"));
      assertEquals("'ab", stringMod("%.3r", "abcdef"));
      assertEquals("'\\xe9'", stringMod("%a", "é"));
      assertEquals("   ab", stringMod("%5s", "ab"));
      assertEquals("ab   |", stringMod("%-5s|", "ab"));
      assertEquals("he", stringMod("%.2s", "hello"));
      assertEquals("hé", stringMod("%.2s", "héllo"));
      assertEquals(" 😀", stringMod("%2s", "😀"));   // width counted in code points
    }

    @Test
    public void charConversion() {
      assertEquals("A", stringMod("%c", 65L));
      assertEquals("a", stringMod("%c", "a"));
      assertEquals("é", stringMod("%c", 233L));
      assertEquals("😀", stringMod("%c", 128512L));
      assertEquals("\u0001", stringMod("%c", true));
      assertEquals("    A", stringMod("%5c", 65L));
      assertEquals("A    |", stringMod("%-5c|", 65L));
      assertEquals("%c requires an int or a unicode character, not a string of length 2",
          assertThrows(PyTypeError.class, () -> stringMod("%c", "ab")).getMessage());
      assertEquals("%c requires an int or a unicode character, not float",
          assertThrows(PyTypeError.class, () -> stringMod("%c", 1.5)).getMessage());
      assertEquals("%c arg not in range(0x110000)",
          assertThrows(PyOverflowError.class, () -> stringMod("%c", 1114112L)).getMessage());
      assertEquals("%c arg not in range(0x110000)",
          assertThrows(PyOverflowError.class, () -> stringMod("%c", -1L)).getMessage());
      // unlike the % operator, the format-spec path converts through a C long first
      assertEquals("Python int too large to convert to C long",
          assertThrows(PyOverflowError.class,
              () -> formatValue(new BigInteger("9223372036854775808"), "c")).getMessage());
      assertEquals("%c arg not in range(0x110000)",
          assertThrows(PyOverflowError.class,
              () -> stringMod("%c", new BigInteger("9223372036854775808"))).getMessage());
    }

    @Test
    public void literalPercent() {
      assertEquals("100%", stringMod("100%%", TupleIntrinsics.empty()));
      assertEquals("%d", stringMod("%%d", TupleIntrinsics.empty()));
      assertEquals("50%", stringMod("%d%%", 50L));
      assertEquals("%(b)s", stringMod("%%(b)s", TupleIntrinsics.empty()));
    }

    @Test
    public void starWidthAndPrecision() {
      assertEquals("   42", stringMod("%*d", args(5L, 42L)));
      assertEquals("42   ", stringMod("%*d", args(-5L, 42L)));
      assertEquals("-0042", stringMod("%0*d", args(5L, -42L)));
      assertEquals("3.14", stringMod("%.*f", args(2L, 3.14159)));
      assertEquals("3", stringMod("%.*f", args(-2L, 3.14159)));   // negative precision clamps to zero
      assertEquals("     3.142", stringMod("%*.*f", args(10L, 3L, 3.14159)));
      assertEquals("* wants int",
          assertThrows(PyTypeError.class, () -> stringMod("%*d", args("x", 1L))).getMessage());
      assertEquals("* wants int",
          assertThrows(PyTypeError.class, () -> stringMod("%*d", args(2.5, 1L))).getMessage());
      assertEquals("* wants int",
          assertThrows(PyTypeError.class, () -> stringMod("%.*d", args(PyNone.INSTANCE, 1L))).getMessage());
    }

    @Test
    public void mappingKeys() {
      var dict = new PyDict<String, Object>();
      dict.put("name", "Li");
      dict.put("age", 7L);
      assertEquals("Li is 7", stringMod("%(name)s is %(age)d", dict));
      assertEquals("x00007x", stringMod("x%(a)05dx", withA(7L)));
      assertEquals("v     |", stringMod("%(a)-6s|", withA("v")));
      assertEquals("None", stringMod("%(a)s", withA(PyNone.INSTANCE)));
      assertEquals("'b'",
          assertThrows(PyKeyError.class, () -> stringMod("%(b)s", withA(1L))).getMessage());
      assertEquals("format requires a mapping",
          assertThrows(PyTypeError.class, () -> stringMod("%(a)s", 1L)).getMessage());
      assertEquals("not enough arguments for format string",
          assertThrows(PyTypeError.class, () -> stringMod("%(a)s %s", withA(1L))).getMessage());
    }

    @Test
    public void percentTreatsListAndRangeAsMappings() {
      assertEquals("abc", stringMod("abc", new PyList<>(1L)));
      assertEquals("abc", stringMod("abc", PyRange.of(0L, 3L, 1L)));
      assertEquals("[1, 2]", stringMod("%s", new PyList<>(1L, 2L)));
      var e = assertThrows(PyTypeError.class, () -> stringMod("%(a)s", new PyList<>(1L)));
      assertNotEquals("format requires a mapping", e.getMessage());
      assertEquals("format requires a mapping",
          assertThrows(PyTypeError.class, () -> stringMod("%(a)s", "text")).getMessage());
    }

    @Test
    public void argumentProtocolErrors() {
      assertEquals("1 2", stringMod("%s %s", args(1L, 2L)));
      assertEquals("not enough arguments for format string",
          assertThrows(PyTypeError.class, () -> stringMod("%s %s", args(1L))).getMessage());
      assertEquals("not all arguments converted during string formatting",
          assertThrows(PyTypeError.class, () -> stringMod("%s", args(1L, 2L))).getMessage());
      assertEquals("not all arguments converted during string formatting",
          assertThrows(PyTypeError.class, () -> stringMod("abc", 1L)).getMessage());
    }

    @Test
    public void invalidFormats() {
      assertEquals("unsupported format character 'z' (0x7a) at index 1",
          assertThrows(PyValueError.class, () -> stringMod("%z", 1L)).getMessage());
      assertEquals("unsupported format character '$' (0x24) at index 2",
          assertThrows(PyValueError.class, () -> stringMod("%1$d", 1L)).getMessage());
      assertEquals("incomplete format",
          assertThrows(PyValueError.class, () -> stringMod("abc %", TupleIntrinsics.empty())).getMessage());
      assertEquals("incomplete format",
          assertThrows(PyValueError.class, () -> stringMod("%-", TupleIntrinsics.empty())).getMessage());
      assertEquals("incomplete format key",
          assertThrows(PyValueError.class, () -> stringMod("%(()s", withA(1L))).getMessage());
    }

    @Test
    public void badArgumentTypes() {
      assertEquals("%d format: a real number is required, not str",
          assertThrows(PyTypeError.class, () -> stringMod("%d", "3")).getMessage());
      assertEquals("%x format: an integer is required, not float",
          assertThrows(PyTypeError.class, () -> stringMod("%x", 3.0)).getMessage());
      assertEquals("must be real number, not str",
          assertThrows(PyTypeError.class, () -> stringMod("%f", "3")).getMessage());
      assertEquals("cannot convert float infinity to integer",
          assertThrows(PyOverflowError.class, () -> stringMod("%d", Double.POSITIVE_INFINITY)).getMessage());
      assertEquals("cannot convert float NaN to integer",
          assertThrows(PyValueError.class, () -> stringMod("%d", Double.NaN)).getMessage());
    }

    @Test
    public void dynamicPrecisionBoundaries() {
      assertEquals("",
          stringMod("%.*s", args((long) Integer.MIN_VALUE, "abc")));

      assertEquals("x",
          stringMod("%.*s", args((long) Integer.MAX_VALUE, "x")));

      assertEquals("Python int too large to convert to C int",
          assertThrows(PyOverflowError.class,
              () -> stringMod("%.*s", args(2147483648L, "x"))).getMessage());

      assertEquals("Python int too large to convert to C int",
          assertThrows(PyOverflowError.class,
              () -> stringMod("%.*s", args(-2147483649L, "x"))).getMessage());
    }

    @Test
    public void percentDecimalHonorsTheIntMaxStrDigitsLimit() {
      assertEquals(4_300, stringMod("%d", BigInteger.TEN.pow(4_299)).length());
      var beyond = BigInteger.TEN.pow(4_300);
      for (var conversion : new String[] {"%d", "%i", "%u", "%5d", "%+d", "%.2d"}) {
        assertThrows(PyValueError.class, () -> stringMod(conversion, beyond), conversion);
        assertThrows(PyValueError.class, () -> stringMod(conversion, beyond.negate()), conversion);
      }
      // power-of-two radixes are not limited
      var huge = BigInteger.TEN.pow(5_000);
      assertEquals(huge.toString(16), stringMod("%x", huge));
    }

    @Test
    public void throughTheCompiler() {
      var context = run("""
          name = 'Ada'
          greeting = 'Hello %s, %d%% done' % (name, 42)
          row = '%(item)s: %(value)05.1f' % {'item': 'cost', 'value': 12.34}
          rest = 17 % 5
          """);
      assertEquals("Hello Ada, 42% done", context.getGlobal("greeting"));
      assertEquals("cost: 012.3", context.getGlobal("row"));
      assertEquals(2L, context.getGlobal("rest"));
    }
  }

  @Nested
  public class Integers {
    @Test
    public void integerFormatting() {
      var hex = new BigInteger("1234567890abcdef12345", 16);

      assertEquals("0x001234567890abcdef12345",
          stringMod("%#.23x", hex));

      assertEquals("-0x001234567890abcdef12345",
          stringMod("%#.23x", hex.negate()));

      assertEquals("0x00001234567890abcdef12345",
          stringMod("%#027x", hex));

      assertEquals("+0x001234567890abcdef12345",
          stringMod("%#+.23x", hex));

      var octal = new BigInteger("12345670123456701234567012345670", 8);

      assertEquals("0o0012345670123456701234567012345670",
          stringMod("%#.34o", octal));

      assertEquals("0o000012345670123456701234567012345670",
          stringMod("%#038o", octal));

      var decimal = new BigInteger("123456789012345678901234567890");

      assertEquals("-000123456789012345678901234567890",
          stringMod("%034d", decimal.negate()));

      assertEquals("+000123456789012345678901234567890",
          stringMod("%0+34d", decimal));
    }
  }

  @Nested
  public class Floats {
    @Test
    public void foatFormatting() {
      assertEquals("2.67", stringMod("%.2f", 2.675));
      assertEquals("1.00", stringMod("%.2f", 1.005));

      assertEquals("1.10000", stringMod("%#g", 1.1));

      assertEquals("1e+06", stringMod("%.6g", 999999.5));

      assertEquals("0.0001", stringMod("%.3g", 0.00009999));
      assertEquals("9.999e-05", stringMod("%.4g", 0.00009999));

      assertEquals("1.e+00", stringMod("%#.0e", 1.25));
      assertEquals("1.", stringMod("%#.0g", 1.0));

      assertEquals("-0.000000", stringMod("%+f", -0.0));

      assertEquals("+NAN", stringMod("%+G", Double.NaN));
      assertEquals("-INF", stringMod("%F", Double.NEGATIVE_INFINITY));
    }
  }

  @Nested
  public class Unicode {
    @Test
    public void unicodeFormatting() {
      assertEquals("'\\u0378'", stringMod("%r", "\u0378"));
      assertEquals("'\\u0378'", stringMod("%a", "\u0378"));

      assertEquals("'\u0374'", stringMod("%r", "\u0374"));
      assertEquals("'\\u0374'", stringMod("%a", "\u0374"));

      assertEquals("  😀", stringMod("%3.1s", "😀ab"));
      assertEquals("😀  ", stringMod("%-3.1s", "😀ab"));

      assertEquals("'\\U0001f600'", stringMod("%a", "😀"));

      assertEquals("a\0b", stringMod("a%cb", 0L));
      assertEquals("ac\0db", stringMod("a%sb", "c\0d"));
    }
  }

  @Nested
  public class ParseError {
    @Test
    public void mappingFormatting() {
      var dict = new PyDict<String, Object>();
      dict.put("outer(inner)", "nested");
      dict.put("unused", 42L);

      assertEquals("nested",
          stringMod("%(outer(inner))s", dict));
    }

    @Test
    public void formattingErrors() {
      // CPython checks for a mapping before parsing the mapping key.
      assertEquals("format requires a mapping",
          assertThrows(PyTypeError.class,
              () -> stringMod("%(unfinished", 42L)).getMessage());

      // Non-ASCII conversion characters are displayed as '?'.
      assertEquals("unsupported format character '?' (0x1f600) at index 1",
          assertThrows(PyValueError.class,
              () -> stringMod("%😀", 42L)).getMessage());

      // Negative width cannot be negated when it is PY_SSIZE_T_MIN.
      assertEquals("width too big",
          assertThrows(PyOverflowError.class,
              () -> stringMod("%*s",
                  args(BigInteger.valueOf(Long.MIN_VALUE), "x"))).getMessage());
    }
  }

  @Nested
  public final class Format {
    @Test
    public void fieldIndexesFollowCPythonGetInteger() {
      assertEquals("b", fmt("{0000000000000000000001}", "a", "b"));
      assertEquals("b", fmt("{\u0661}", "a", "b"));                      // Arabic-Indic one
      assertEquals("Too many decimal digits in format string",
          assertThrows(PyValueError.class, () -> fmt("{9223372036854775808}", "a")).getMessage());
      assertThrows(PyIndexError.class, () -> fmt("{9223372036854775807}", "a"));
      // the overflow wins over the numbering-mode conflict
      assertThrows(PyValueError.class, () -> fmt("{}{9223372036854775808}", "a"));
      assertThrows(PyValueError.class, () -> fmt("{0[9223372036854775808]}", new PyList<>(1L)));
      assertEquals("20", fmt("{0[0001]}", new PyList<>(10L, 20L)));
    }

    @Test
    public void conversionParsingFollowsCPythonParseField() {
      assertEquals("unmatched '{' in format spec",
          assertThrows(PyValueError.class, () -> fmt("{0!s", 1L)).getMessage());
      assertEquals("unmatched '{' in format spec",
          assertThrows(PyValueError.class, () -> fmt("{0!}", 1L)).getMessage());
      assertEquals("expected ':' after conversion specifier",
          assertThrows(PyValueError.class, () -> fmt("{0!} abc", 1L)).getMessage());
      // the argument is resolved before the conversion is validated
      assertThrows(PyIndexError.class, () -> fmt("{!x}"));
      assertEquals("Unknown conversion specifier x",
          assertThrows(PyValueError.class, () -> fmt("{0!x}", 1L)).getMessage());
      assertEquals("Unknown conversion specifier \\xe9",
          assertThrows(PyValueError.class, () -> fmt("{0!\u00e9}", 1L)).getMessage());
    }
  }

  @Nested
  public final class FormatValue {

    private static String formatValue(Object value, String spec) {
      return (String) FormattingIntrinsics.formatValue(value, spec);
    }

    @Test
    public void emptySpecBehavesLikeStr() {
      assertEquals("x", formatValue("x", ""));
      assertEquals("42", formatValue(42L, ""));
      assertEquals("True", formatValue(true, ""));
      assertEquals("1.5", formatValue(1.5, ""));
      assertEquals("None", formatValue(PyNone.INSTANCE, ""));
    }

    @Test
    public void specAppliesToNumbersAndStrings() {
      assertEquals("00042", formatValue(42L, "05d"));
      assertEquals("  a  ", formatValue("a", "^5"));
      assertEquals("3.14", formatValue(3.14159, ".2f"));
      assertEquals("1.000000", formatValue(1L, "f"));      // float presentation types format an int
      assertEquals("0.3", formatValue(0.1 + 0.2, ".10"));  // precision without type: 'g'
      assertEquals("ff", formatValue(255L, "x"));
      assertEquals("00,001,234", formatValue(1234L, "010,"));
    }

    @Test
    public void groupedZeroFilledScientific() {
      // regression: exponent bodies without a decimal point were left ungrouped
      assertEquals("0,001e-320", formatValue(1e-320, "010,"));
      assertEquals("0,001e-320", formatValue(1e-320, "=010,"));
      assertEquals("00,001e+23", formatValue(1e23, "010,"));
      assertEquals("0,001e+300", formatValue(1e300, "=010,"));
      assertEquals("-0,001e-11", formatValue(-1e-11, "010,"));
      assertEquals("0,001.235e+06", formatValue(1234567.0, "012,.3e"));
      assertEquals("00,123,456.789", formatValue(123456.789, "014,.3f"));
      // an explicit '>' keeps the plain zero fill of the whole field, like CPython
      assertEquals("00001e-320", formatValue(1e-320, ">010,"));
    }

    @Test
    public void hugeWidthWithGroupingFailsEagerly() {
      // regression: the zero-fill + '=' + grouping fast path used to loop forever
      // (int overflow) and allocated without any size check
      assertThrows(UnimplementedFeatureError.class, () -> formatValue(5L, "0=4294967296,d"));
      assertThrows(UnimplementedFeatureError.class, () -> formatValue(1.5d, "0=4294967296,.2f"));
      assertThrows(UnimplementedFeatureError.class, () -> formatValue(5L, "0=2147483649,d"));
    }

    @Test
    public void nonEmptySpecRejectsUnsupportedTypes() {
      assertEquals("unsupported format string passed to NoneType.__format__",
          assertThrows(PyTypeError.class, () -> formatValue(PyNone.INSTANCE, "10")).getMessage());
      assertEquals("unsupported format string passed to dict.__format__",
          assertThrows(PyTypeError.class, () -> formatValue(new PyDict<Object, Object>(), "10")).getMessage());
    }

    @Test
    public void widthOverflowUsesTheFormatSpecMessage() {
      // unlike the printf-style % operator ("width too big"), the format-spec
      // mini-language reports the int64 overflow like CPython's PyObject_Format
      assertEquals("Too many decimal digits in format string",
          assertThrows(PyValueError.class, () -> formatValue(1L, "9999999999999999999d")).getMessage());
      assertEquals("Too many decimal digits in format string",
          assertThrows(PyValueError.class, () -> formatValue(1L, ".9999999999999999999f")).getMessage());
    }

    @Test
    public void hugeWidthFailsEagerlyInsteadOfAllocating() {
      // unlike CPython, which would try to allocate the padding
      assertThrows(UnimplementedFeatureError.class, () -> formatValue(1L, "9999999999d"));
    }
  }

  @Nested
  public final class Grouping {
    @Test
    public void commaWithTypeSIsRejectedAtParseTimeForAnyReceiver() {
      // the check is done by the spec parser, before the type dispatch
      assertEquals("Cannot specify ',' with 's'.",
          assertThrows(PyValueError.class, () -> fmtValue(1L, ",s")).getMessage());
      assertEquals("Cannot specify ',' with 's'.",
          assertThrows(PyValueError.class, () -> fmtValue("abc", ",s")).getMessage());
      assertEquals("Cannot specify '_' with 's'.",
          assertThrows(PyValueError.class, () -> fmtValue(1L, "_s")).getMessage());
    }

    @Test
    public void repeatedGroupingCharBecomesThePresentationType() {
      // CPython consumes ',' then '_' then ',' again: a same-char repeat is left in
      // place and parsed as the type, so it is not the "both" error
      assertEquals("Cannot specify ',' with ','.",
          assertThrows(PyValueError.class, () -> fmtValue(1L, ",,")).getMessage());
      assertEquals("Cannot specify '_' with '_'.",
          assertThrows(PyValueError.class, () -> fmtValue(1L, "__")).getMessage());
      assertEquals("Cannot specify ',' with ','.",
          assertThrows(PyValueError.class, () -> fmtValue(1L, "3,,")).getMessage());
    }

    @Test
    public void mixedGroupingCharsReportTheBothError() {
      assertEquals("Cannot specify both ',' and '_'.",
          assertThrows(PyValueError.class, () -> fmtValue(1L, ",_")).getMessage());
      assertEquals("Cannot specify both ',' and '_'.",
          assertThrows(PyValueError.class, () -> fmtValue(1L, "_,")).getMessage());
    }

    @Test
    public void trailingJunkWinsOverTheGroupingTypeCheck() {
      assertEquals("Invalid format specifier ',,x' for object of type 'int'",
          assertThrows(PyValueError.class, () -> fmtValue(1L, ",,x")).getMessage());
    }

    @Test
    public void groupingIsCheckedBeforeDispatchEvenForFloatAndStrReceivers() {
      assertEquals("Cannot specify ',' with 'x'.",
          assertThrows(PyValueError.class, () -> fmtValue(1L, ",x")).getMessage());
      assertEquals("Cannot specify ',' with 'x'.",
          assertThrows(PyValueError.class, () -> fmtValue("abc", ",x")).getMessage());
      assertEquals("Cannot specify ',' with 'c'.",
          assertThrows(PyValueError.class, () -> fmtValue(1.5, ",c")).getMessage());
      assertEquals("Cannot specify ',' with 'n'.",
          assertThrows(PyValueError.class, () -> fmtValue(1L, ",n")).getMessage());
      assertEquals("Cannot specify ',' with 'b'.",
          assertThrows(PyValueError.class, () -> fmtValue(255L, ",b")).getMessage());
      // non-printable type characters use the \x form
      assertEquals("Cannot specify ',' with '\\xb2'.",
          assertThrows(PyValueError.class, () -> fmtValue(1L, ",²")).getMessage());
    }

    @Test
    public void strReceiverDefaultsItsTypeToS() {
      // str passes default_type='s' into the parser, so bare grouping is an error for str
      assertEquals("Cannot specify ',' with 's'.",
          assertThrows(PyValueError.class, () -> fmtValue("abc", ",")).getMessage());
      assertEquals("Cannot specify '_' with 's'.",
          assertThrows(PyValueError.class, () -> fmtValue("abc", "_")).getMessage());
      // ... while int/float keep their (allowed) empty default type
      assertEquals("1", fmtValue(1L, ","));
      assertEquals("1.5", fmtValue(1.5, ","));
    }

    @Test
    public void validGroupingSpecsStillWork() {
      assertEquals("1", fmtValue(1L, "_"));
      assertEquals("1", fmtValue(1L, ","));
      assertEquals("  1", fmtValue(1L, "3,"));
      assertEquals("1", fmtValue(1L, ",d"));
      assertEquals("1111_1111", fmtValue(255L, "_b"));
      assertEquals("00,001,234", fmtValue(1234L, "010,"));
      // dispatch-level unknown codes are unchanged
      assertEquals("Unknown format code 's' for object of type 'int'",
          assertThrows(PyValueError.class, () -> fmtValue(1L, "s")).getMessage());
      assertEquals("Unknown format code 'd' for object of type 'str'",
          assertThrows(PyValueError.class, () -> fmtValue("abc", ",d")).getMessage());
    }
  }

  @Nested
  public final class UnicodeDigits {

    @Test
    public void widthAcceptsAnyUnicodeDecimalDigit() {
      assertEquals("  5", fmtValue(5L, "٣"));       // U+0663 ARABIC-INDIC DIGIT THREE
      assertEquals("5", fmtValue(5L, "１"));         // U+FF11 FULLWIDTH DIGIT ONE
      assertEquals("  5", fmtValue(5L, "٣d"));
      assertEquals("    5", fmtValue(5L, "𝟝"));      // U+1D7DD, supplementary plane: code-point iteration
      assertEquals("1", fmtValue(1L, "٠"));          // U+0660: width 0, not the ASCII zero-padding flag
      assertEquals("٣٣5", fmtValue(5L, "٣>٣d"));     // the first ٣ is the fill, the second the width
    }

    @Test
    public void precisionAcceptsAnyUnicodeDecimalDigit() {
      assertEquals("0.333", fmtValue(1.0 / 3, ".٣f"));
    }

    @Test
    public void nonDecimalDigitsFallThroughToTheTypeChar() {
      // U+00B2 SUPERSCRIPT TWO is not a decimal digit (Py_UNICODE_TODECIMAL fails)
      assertEquals("Unknown format code '\\xb2' for object of type 'int'",
          assertThrows(PyValueError.class, () -> fmtValue(5L, "²")).getMessage());
    }

    @Test
    public void overflowStillReportsTheFormatSpecMessage() {
      assertEquals("Too many decimal digits in format string",
          assertThrows(PyValueError.class, () -> fmtValue(1L, "٩".repeat(20))).getMessage());
    }
  }


  @Nested
  public final class StrSign {

    @Test
    public void spaceSignHasItsOwnMessage() {
      assertEquals("Space not allowed in string format specifier",
          assertThrows(PyValueError.class, () -> fmtValue("abc", " s")).getMessage());
      assertEquals("Space not allowed in string format specifier",
          assertThrows(PyValueError.class, () -> fmtValue("abc", " ")).getMessage());
      assertEquals("Space not allowed in string format specifier",
          assertThrows(PyValueError.class, () -> fmt("{: s}", "abc")).getMessage());
    }

    @Test
    public void plusAndMinusKeepTheSignMessage() {
      assertEquals("Sign not allowed in string format specifier",
          assertThrows(PyValueError.class, () -> fmtValue("abc", "+")).getMessage());
      assertEquals("Sign not allowed in string format specifier",
          assertThrows(PyValueError.class, () -> fmtValue("abc", "-")).getMessage());
    }
  }


  @Nested
  public final class Float {

    @Test
    public void alternateFormAddsTheDotBeforeTheExponent() {
      assertEquals("1.e+16", fmtValue(1e16, "#"));
      assertEquals("-1.e+16", fmtValue(-1e16, "#"));
      assertEquals("1.e+100", fmtValue(1e100, "#"));
      assertEquals("1.e-05", fmtValue(1e-5, "#"));
    }

    @Test
    public void reprsThatAlreadyHaveADotAreUnchanged() {
      assertEquals("1.0", fmtValue(1.0, "#"));
      assertEquals("123456.0", fmtValue(123456.0, "#"));
      assertEquals("1.5e-07", fmtValue(1.5e-7, "#"));
      assertEquals("9007199254740992.0", fmtValue(9007199254740992.0, "#"));
      assertEquals("0.0", fmtValue(0.0, "#"));
    }

    @Test
    public void specialFloatsAndMissingAlternateAreUnchanged() {
      assertEquals("inf", fmtValue(Double.POSITIVE_INFINITY, "#"));
      assertEquals("nan", fmtValue(Double.NaN, "#"));
      assertEquals("1e+16", fmtValue(1e16, ""));
    }

    @Test
    public void alternateFormWithAPrecisionGoesThroughGeneral() {
      assertEquals("1.e+16", fmtValue(1e16, "#.0"));
      assertEquals("1e+16", fmtValue(1e16, ".2"));
    }
  }


  @Nested
  public final class FieldName {

    @Test
    public void strayClosingBracketIsPartOfTheKeywordName() {
      assertEquals("'a]'", assertThrows(PyKeyError.class, () -> fmt("{a]}", withA(1L))).getMessage());
      assertEquals("'a]b'", assertThrows(PyKeyError.class, () -> fmt("{a]b}", withA(1L))).getMessage());
      assertEquals("'0]'", assertThrows(PyKeyError.class, () -> fmt("{0]}", 1L)).getMessage());
    }

    @Test
    public void onlyDotOrBracketMayFollowAClosedItem() {
      // positional form (str.format keyword arguments are not supported by this runtime)
      var list = new PyList<>(1L);
      assertEquals("Only '.' or '[' may follow ']' in format field specifier",
          assertThrows(PyValueError.class, () -> fmt("{0[0]b}", list)).getMessage());
      assertEquals("Only '.' or '[' may follow ']' in format field specifier",
          assertThrows(PyValueError.class, () -> fmt("{0[0]]}", list)).getMessage());
      assertEquals("1", fmt("{0[0]}", list));
      var dict = new PyDict<String, Object>();
      dict.put("b", 1L);
      assertEquals("1", fmt("{0[b]}", dict));
    }
  }


  @Nested
  public final class NestedSpecRecursion {

    @Test
    public void oneNestedSpecExpansionIsAllowed() {
      assertEquals("1", fmt("{0:{0}}", 1L));
      assertEquals("  1", fmt("{:{}}", 1L, 3L));
    }

    @Test
    public void twoNestedSpecExpansionsFail() {
      assertEquals("Max string recursion exceeded",
          assertThrows(PyValueError.class, () -> fmt("{0:{0:{0}}}", 1L)).getMessage());
    }

    @Test
    public void theArgumentIsResolvedBeforeTheSpecIsExpanded() {
      // CPython raises the lookup error during rendering, before expanding the spec,
      // so the IndexError wins over "Max string recursion exceeded"
      assertThrows(PyIndexError.class, () -> fmt("{0:{0}}"));
      assertThrows(PyIndexError.class, () -> fmt("{0:{0:{0}}}"));
    }
  }

  @Nested
  public final class StringModBoundaries {
    @Test
    public void literalWidthAndPrecisionOverflows() {
      // the % operator uses PY_SSIZE_T_MAX for the width and INT_MAX for the precision,
      // with the actual digit in the overflow guard
      assertEquals("width too big",
          assertThrows(PyValueError.class, () -> stringMod("%9223372036854775808d", 1L)).getMessage());
      assertEquals("precision too big",
          assertThrows(PyValueError.class, () -> stringMod("%.2147483648f", 1L)).getMessage());
    }

    @Test
    public void widthJustBelowLongMaxValueIsAcceptedAtParseTime() {
      // CPython parses width 2**63-1 fine and fails while allocating; Java fails eagerly
      // on the JVM string limit instead (like CPython, the parse itself succeeds)
      assertThrows(UnimplementedFeatureError.class, () -> stringMod("%9223372036854775807d", 1L));
    }
  }
}
