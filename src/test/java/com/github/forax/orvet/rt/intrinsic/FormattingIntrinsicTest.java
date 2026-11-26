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
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static com.github.forax.orvet.rt.intrinsic.FormattingIntrinsics.stringMod;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class FormattingIntrinsicTest {

  private ModuleContext run(String source) {
    var context = new ModuleContext();
    Engine.run(context, source);
    return context;
  }

  @Nested
  public final class StringMod {

    private static Object args(Object... elements) {
      return TupleIntrinsics.fromArray(elements);
    }

    private static PyDict<String, Object> withA(Object value) {
      var dict = new PyDict<String, Object>();
      dict.put("a", value);
      return dict;
    }

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
}
