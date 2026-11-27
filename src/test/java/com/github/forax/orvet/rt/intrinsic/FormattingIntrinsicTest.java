package com.github.forax.orvet.rt.intrinsic;

import com.github.forax.orvet.engine.Engine;
import com.github.forax.orvet.rt.ModuleContext;
import com.github.forax.orvet.rt.PyDict;
import com.github.forax.orvet.rt.PyList;
import com.github.forax.orvet.rt.PyNone;
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
      assertEquals("%c requires int or char",
          assertThrows(PyTypeError.class, () -> stringMod("%c", "ab")).getMessage());
      assertEquals("%c requires int or char",
          assertThrows(PyTypeError.class, () -> stringMod("%c", 1.5)).getMessage());
      assertEquals("%c arg not in range(0x110000)",
          assertThrows(PyOverflowError.class, () -> stringMod("%c", 1114112L)).getMessage());
      assertEquals("%c arg not in range(0x110000)",
          assertThrows(PyOverflowError.class, () -> stringMod("%c", -1L)).getMessage());
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
    @Disabled
    public void dynamicPrecisionBoundaries() {
      assertEquals("",
          stringMod("%.*s", args(Integer.MIN_VALUE, "abc")));

      assertEquals("x",
          stringMod("%.*s", args(Integer.MAX_VALUE, "x")));

      assertEquals("Python int too large to convert to C int",
          assertThrows(PyOverflowError.class,
              () -> stringMod("%.*s", args(2147483648L, "x"))).getMessage());

      assertEquals("Python int too large to convert to C int",
          assertThrows(PyOverflowError.class,
              () -> stringMod("%.*s", args(-2147483649L, "x"))).getMessage());
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
    public void cpython314IntegerFormatting() {
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
    public void cpython314FloatFormatting() {
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
    public void cpython314UnicodeFormatting() {
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
    public void cpython314MappingFormatting() {
      var dict = new PyDict<String, Object>();
      dict.put("outer(inner)", "nested");
      dict.put("unused", 42L);

      assertEquals("nested",
          stringMod("%(outer(inner))s", dict));
    }

    @Test
    @Disabled
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
}
