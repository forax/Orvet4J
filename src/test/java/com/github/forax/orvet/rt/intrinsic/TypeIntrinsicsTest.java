package com.github.forax.orvet.rt.intrinsic;

import com.github.forax.orvet.engine.Engine;
import com.github.forax.orvet.rt.ModuleContext;
import com.github.forax.orvet.rt.PyDict;
import com.github.forax.orvet.rt.PyList;
import com.github.forax.orvet.rt.PyNone;
import com.github.forax.orvet.rt.PyRange;
import com.github.forax.orvet.rt.PySlice;
import com.github.forax.orvet.rt.PyTuple;
import com.github.forax.orvet.rt.PyType;
import com.github.forax.orvet.rt.error.PyOverflowError;
import com.github.forax.orvet.rt.error.PyTypeError;
import com.github.forax.orvet.rt.error.PyValueError;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static com.github.forax.orvet.rt.intrinsic.TypeIntrinsics.$bool$;
import static com.github.forax.orvet.rt.intrinsic.TypeIntrinsics.$float$;
import static com.github.forax.orvet.rt.intrinsic.TypeIntrinsics.$int$;
import static com.github.forax.orvet.rt.intrinsic.TypeIntrinsics.$type$;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class TypeIntrinsicsTest {

  @Nested
  public final class Type {

    @Test
    public void typeRecognizesTheCurrentlySupportedRuntimeTypes() {
      assertSame(PyType.INT, $type$(42L));
      assertSame(PyType.INT, $type$(BigInteger.ONE.shiftLeft(100)));
      assertSame(PyType.FLOAT, $type$(2.5d));
      assertSame(PyType.BOOL, $type$(true));
      assertSame(PyType.STR, $type$("hello"));
      assertSame(PyType.NONE_TYPE, $type$(PyNone.INSTANCE));
      assertSame(PyType.DICT, $type$(new PyDict<>()));
      assertSame(PyType.LIST, $type$(new PyList<>()));
      assertSame(PyType.TUPLE, $type$(new PyTuple.PyTuple1(1L)));
      assertSame(PyType.RANGE, $type$(PyRange.of(0, 10, 1)));
      assertSame(PyType.SLICE, $type$(PySlice.of(0L, 10L, 1L)));

      var context = new ModuleContext();
      Engine.run(context, "def f(): pass");
      var function = context.getGlobal("f");
      var builtinFunctionOrMethod = context.getGlobal("hash");
      var type = context.getGlobal("int");
      assertSame(PyType.FUNCTION, $type$(function));
      assertSame(PyType.BUILTIN_FUNCTION_OR_METHOD, $type$(builtinFunctionOrMethod));
      assertSame(PyType.TYPE, $type$(type));
    }

    @Test
    public void typeRejectsAnObjectOutsideTheRuntimeRepresentation() {
      assertThrows(NotCanonicalException.class, () -> $type$(new Object()));
    }
  }


  @Nested
  public final class Bool {

    @Test
    public void boolWithoutAnArgumentIsFalse() {
      assertFalse((Boolean) $bool$());
    }

    @Test
    public void boolUsesPythonTruthiness() {
      assertFalse((Boolean) $bool$(false));
      assertFalse((Boolean) $bool$(0L));
      assertFalse((Boolean) $bool$(BigInteger.ZERO));
      assertFalse((Boolean) $bool$(0.0d));
      assertFalse((Boolean) $bool$(""));
      assertFalse((Boolean) $bool$(PyNone.INSTANCE));
      assertFalse((Boolean) $bool$(new PyList<>()));
      assertFalse((Boolean) $bool$(new PyTuple.PyTupleN(new Object[0])));
      assertFalse((Boolean) $bool$(new PyDict<>()));

      assertTrue((Boolean) $bool$(true));
      assertTrue((Boolean) $bool$(-1L));
      assertTrue((Boolean) $bool$(BigInteger.ONE));
      assertTrue((Boolean) $bool$(Double.NaN));
      assertTrue((Boolean) $bool$("x"));
      assertTrue((Boolean) $bool$(new PyList<>(1L)));
      assertTrue((Boolean) $bool$(new PyTuple.PyTuple1(1L)));
      var dict = new PyDict<String, Object>();
      dict.put("x", 1L);
      assertTrue((Boolean) $bool$(dict));
    }

    @Test
    public void boolRejectsMoreThanOneArgumentWithTheBoolName() {
      var error = assertThrows(PyTypeError.class, () -> $bool$(true, false));
      assertEquals("bool() expected at most 1 argument, got 2", error.getMessage());
    }
  }

  @Nested
  public final class Int {

    @Test
    public void intConvertsNumbersAndNormalizesBigIntegers() {
      assertEquals(0L, $int$());
      assertEquals(1L, $int$(true));
      assertEquals(0L, $int$(false));
      assertEquals(-42L, $int$(-42L));
      assertEquals(3L, $int$(3.9d));
      assertEquals(-3L, $int$(-3.9d));
      assertEquals(Long.MIN_VALUE, $int$(BigInteger.valueOf(Long.MIN_VALUE)));
      assertEquals(BigInteger.ONE.shiftLeft(100), $int$(BigInteger.ONE.shiftLeft(100)));
    }

    @Test
    public void intRejectsNonFiniteFloats() {
      assertThrows(PyValueError.class, () -> $int$(Double.NaN));
      assertThrows(PyOverflowError.class, () -> $int$(Double.POSITIVE_INFINITY));
      assertThrows(PyOverflowError.class, () -> $int$(Double.NEGATIVE_INFINITY));
    }

    @Test
    public void intParsesDecimalStringsAndWhitespace() {
      assertEquals(42L, $int$("  +42  "));
      assertEquals(-42L, $int$("-42"));
      assertEquals(1_000L, $int$("1_000"));
      assertEquals(12L, $int$("١٢"));
    }

    @Test
    public void intParsesExplicitBasesAndPrefixes() {
      assertEquals(5L, $int$("101", 2L));
      assertEquals(255L, $int$("ff", 16L));
      assertEquals(255L, $int$("0x_FF", 0L));
      assertEquals(10L, $int$("10", false));  // False is base zero.
      assertEquals(35L, $int$("z", 36L));
    }

    @Test
    public void intParsingCrossesLongBoundaries() {
      // Values still represented as Long
      assertEquals(Long.MAX_VALUE,
          $int$("9223372036854775807"));
      assertEquals(Long.MIN_VALUE,
          $int$("-9223372036854775808"));

      // Values requiring the BigInteger slow path
      assertEquals(BigInteger.ONE.shiftLeft(63),
          $int$("9223372036854775808"));

      assertEquals(BigInteger.ONE.shiftLeft(63).negate().subtract(BigInteger.ONE),
          $int$("-9223372036854775809"));

      // Also exercise overflow in a non-decimal radix
      assertEquals(BigInteger.ONE.shiftLeft(63),
          $int$("8000000000000000", 16L));
    }

    @Test
    public void intParsesEverySupportedExplicitBase() {
      for (int base = 2; base <= 36; base++) {
        var value = base - 1;
        var digits = Integer.toString(value, base);

        assertEquals((long) value, $int$(digits, (long) base));

        // Alphabetic digits must be case-insensitive
        assertEquals((long) value,
            $int$(digits.toUpperCase(java.util.Locale.ROOT), (long) base));
      }

      // Mixed ASCII and Unicode decimal digits
      assertEquals(123L, $int$("1٢3"));

      // Supplementary-plane Unicode decimal digits
      assertEquals(12L, $int$("\uD835\uDFD9\uD835\uDFDA"));
    }

    @Test
    public void intBaseZeroAcceptsOnlyAllZeroLeadingZeroForms() {
      for (var value : new String[]{
          "0", "00", "000",
          "0_0",
          "+000", "-000",
          "+0_0", "-0_0"
      }) {
        assertEquals(0L, $int$(value, 0L));
      }

      for (var value : new String[]{
          "01", "0_1",
          "+001", "-0_1",
          "00_1"
      }) {
        assertThrows(PyValueError.class, () -> $int$(value, 0L));
      }

      // Leading zeros remain legal with an explicit non-zero base
      assertEquals(10L, $int$("010", 10L));
      assertEquals(8L, $int$("010", 8L));
    }

    @Test
    public void intValidatesUnderscoresAroundRadixPrefixes() {
      assertEquals(65_535L, $int$("0x_F_FFF", 16L));
      assertEquals(42L, $int$("0B_101_010", 2L));

      for (var value : new String[]{
          "0x__FF",
          "0x_",
          "0b_2",
          "+_1",
          "1_+2"
      }) {
        assertThrows(PyValueError.class, () -> $int$(value, 0L));
      }
    }

    @Test
    public void intValidatesPrefixesStripped() {
      // A prefix must agree with the explicitly requested radix
      assertThrows(PyValueError.class, () -> $int$("0x10", 2L));

      // 0b is NOT stripped because the requested base is 16.
      // 'b' is a valid hexadecimal digit.
      assertEquals(0x0b10L, $int$("0b10", 16L));  // 2832

      // 0b is stripped because the requested base is 2.
      assertEquals(2L, $int$("0b10", 2L));

      // 0b is not stripped, and 'b' is invalid in base 8 or 2.
      assertThrows(PyValueError.class, () -> $int$("0b10", 8L));
      assertThrows(PyValueError.class, () -> $int$("0x10", 2L));
    }

    @Test
    public void intRejectsInvalidBaseArgumentTypes() {
      assertThrows(PyTypeError.class, () -> $int$("10", 2.0d));
      assertThrows(PyTypeError.class, () -> $int$("10", "2"));
      assertThrows(PyTypeError.class, () -> $int$("10", PyNone.INSTANCE));

      // True represents base 1, which is invalid
      assertThrows(PyValueError.class, () -> $int$("10", true));

      // BigInteger is a valid integer base argument
      assertEquals(10L, $int$("10", BigInteger.TEN));
    }

    @Test
    public void intTrimsUnicodeNextLineWhitespace() {
      assertEquals(42L, $int$("\u008542\u0085"));
      assertEquals(42L, $int$("\u00A042\u00A0"));
      assertEquals(42L, $int$("\u200742\u2007"));
      assertEquals(42L, $int$("\u202842\u2028"));
      assertEquals(42L, $int$("\u300042\u3000"));

      assertThrows(PyValueError.class, () -> $int$("\u200B42"));
    }

    @Test
    public void intRejectsInvalidLiteralsAndBases() {
      assertThrows(PyValueError.class, () -> $int$("1__0"));
      assertThrows(PyValueError.class, () -> $int$("010", 0L));
      assertThrows(PyValueError.class, () -> $int$("10", -1L));
      assertThrows(PyValueError.class, () -> $int$("10", 1L));
      assertThrows(PyValueError.class, () -> $int$("10", 37L));
    }

    @Test
    public void explicitBaseRequiresAStringEvenForBaseTen() {
      assertThrows(PyTypeError.class, () -> $int$(42L, 16L));
      // Regression case: the base-10 fast path currently bypasses the string check.
      assertThrows(PyTypeError.class, () -> $int$(42L, 10L));
    }

    @Test
    public void intRejectsMoreThanTwoArgumentsWithTheCorrectMaximum() {
      var error = assertThrows(PyTypeError.class, () -> $int$(1L, 2L, 3L));
      assertEquals("int() expected at most 2 arguments, got 3", error.getMessage());
    }
  }

  @Nested
  public final class Float {

    @Test
    public void floatConvertsSupportedNumericTypes() {
      assertEquals(0.0d, $float$());
      assertEquals(0.0d, $float$(false));
      assertEquals(1.0d, $float$(true));
      assertEquals(-42.0d, $float$(-42L));
      assertEquals(42.0d, $float$(42L));
      assertEquals(2.5d, $float$(2.5d));
      assertThrows(PyOverflowError.class, () -> $float$(BigInteger.ONE.shiftLeft(2048)));
    }

    @Test
    public void floatParsesPythonFloatStrings() {
      assertEquals(1234.56d, $float$("1_234.56"));
      assertEquals(0.125d, $float$(".125"));
      assertEquals(1.0d, $float$("1."));
      assertEquals(-0.5d, $float$("  -.5  "));
      assertEquals(12.5d, $float$("١٢.٥"));
      assertEquals(Double.POSITIVE_INFINITY, $float$("inf"));
      assertEquals(Double.NEGATIVE_INFINITY, $float$("-Infinity"));
      assertTrue(Double.isNaN((Double) $float$("NaN")));
    }

    @Test
    public void floatParsesScientificNotationAndBoundaryValues() {
      // Exponent syntax
      assertEquals(1_000.0d, $float$("1e3"));
      assertEquals(0.0125d, $float$("1.25E-2"));
      assertEquals(50.0d, $float$(".5e+2"));
      assertEquals(100.0d, $float$("1.e2"));

      // Double boundaries
      assertEquals(Double.MAX_VALUE, $float$("1.7976931348623157e308"));
      assertEquals(Double.POSITIVE_INFINITY, $float$("1e400"));
      assertEquals(0.0d, $float$("1e-4000"));

      // Negative zero must retain its sign bit
      assertEquals(Long.MIN_VALUE,
          Double.doubleToRawLongBits((Double) $float$("-0.0")));
    }

    @Test
    public void floatParsesUnicodeDigitsAndExponentUnderscores() {
      assertEquals(12.34d, $float$("1_2.3_4"));
      assertEquals(1.0e10d, $float$("1e1_0"));
      assertEquals(1_200.0d, $float$("1.2e\u0663"));

      // Mixed ASCII and Unicode decimal digits
      assertEquals(12.3d, $float$("1٢.٣"));

      // Supplementary-plane Unicode decimal digits
      assertEquals(1.2d, $float$("\uD835\uDFD9.\uD835\uDFDA"));
    }

    @Test
    public void floatParsesAllSpecialValuesCaseInsensitively() {
      assertEquals(Double.POSITIVE_INFINITY, $float$("+iNfInity"));
      assertEquals(Double.NEGATIVE_INFINITY, $float$("-InF"));

      assertTrue(Double.isNaN((Double) $float$("-nAn")));
    }

    @Test
    public void floatRejectsMalformedExponentsAndSeparators() {
      for (var value : new String[]{
          "1e",
          "1e+",
          "1e-",
          "1._2",
          "1e+_2",
          "1e2_",
          "1e2e3",
          "1.2.3",
          "1f",
          "1d",
          "Infinityx",
          "NaNx",
          "1\u00A02",
          "\u00B2"
      }) {
        assertThrows(PyValueError.class, () -> $float$(value));
      }

      assertThrows(PyValueError.class, () -> $float$(" \t\n\u00A0"));
    }

    @Test
    public void floatTrimsUnicodeNextLineWhitespace() {
      assertEquals(12.5d, $float$("\u008512.5\u0085"));
      assertEquals(12.5d, $float$("\u00A012.5\u00A0"));
      assertEquals(12.5d, $float$("\u300012.5\u3000"));

      assertThrows(PyValueError.class, () -> $float$("\u200B12.5"));
    }

    @Test
    public void floatRejectsInvalidStrings() {
      for (var value : new String[]{"", "+", ".", "1__0", "_1.0", "1_.0", "1e_2", "0x1p0"}) {
        assertThrows(PyValueError.class, () -> $float$(value));
      }
    }

    @Test
    public void floatRejectsUnsupportedTypesAndExcessArguments() {
      assertThrows(PyTypeError.class, () -> $float$(PyNone.INSTANCE));
      var error = assertThrows(PyTypeError.class, () -> $float$(1L, 2L));
      assertEquals("float expected at most 1 argument, got 2", error.getMessage());
    }
  }

  @Nested
  public final class NaNGenerator  {
    @Test
    public void oneNaNIsEquals() {
      var nan = (Double) TypeIntrinsics.NaNGenerator.nextNaN();

      assertTrue(Double.isNaN(nan));
      assertFalse(ComparisonIntrinsics.$eq$(nan, nan));
    }

    @Test
    public void twoNaNAreDifferent() {
      var nan1 = (Double) TypeIntrinsics.NaNGenerator.nextNaN();
      var nan2 = (Double) TypeIntrinsics.NaNGenerator.nextNaN();

      assertTrue(Double.isNaN((Double) nan1));
      assertTrue(Double.isNaN((Double) nan2));
      assertFalse(ComparisonIntrinsics.$eq$(nan1, nan2));
    }

    @Test
    public void oneNaNInListAreEquals() {
      var nan = (Double) TypeIntrinsics.NaNGenerator.nextNaN();
      var list1 = new PyList<>(nan);
      var list2 = new PyList<>(nan);
      assertTrue(ComparisonIntrinsics.$eq$(list1, list2));
    }

    @Test
    public void defaultNaNInListAreEquals() {
      var nan = (Double) Double.NaN;
      var list1 = new PyList<>(nan);
      var list2 = new PyList<>(nan);
      assertTrue(ComparisonIntrinsics.$eq$(list1, list2));
    }

    @Test
    public void twoInListAreDifferent() {
      var list1 = new PyList<>(TypeIntrinsics.NaNGenerator.nextNaN());
      var list2 = new PyList<>(TypeIntrinsics.NaNGenerator.nextNaN());
      assertFalse(ComparisonIntrinsics.$eq$(list1, list2));
    }
  }
}
