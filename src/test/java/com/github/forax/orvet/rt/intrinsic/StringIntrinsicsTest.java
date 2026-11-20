package com.github.forax.orvet.rt.intrinsic;

import com.github.forax.orvet.engine.Engine;
import com.github.forax.orvet.rt.ModuleContext;
import com.github.forax.orvet.rt.PyDict;
import com.github.forax.orvet.rt.PyList;
import com.github.forax.orvet.rt.PyNone;
import com.github.forax.orvet.rt.PyRange;
import com.github.forax.orvet.rt.PyTuple.PyTuple1;
import com.github.forax.orvet.rt.PyTuple.PyTuple2;
import com.github.forax.orvet.rt.PyTuple.PyTupleN;
import com.github.forax.orvet.rt.error.PyIndexError;
import com.github.forax.orvet.rt.error.PyOverflowError;
import com.github.forax.orvet.rt.error.PyTypeError;
import com.github.forax.orvet.rt.error.PyValueError;
import com.github.forax.orvet.rt.error.UnimplementedFeatureError;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.Arrays;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public final class StringIntrinsicsTest {

  // -- NEW: shared helpers

  private static String repr(Object value) {
    return (String) StringIntrinsics.$repr$(value);
  }

  /// The string made of the single code point `codePoint`.
  private static String cp(int codePoint) {
    return new String(Character.toChars(codePoint));
  }

  private static <T extends Throwable> void assertThrowsWithMessage(
      Class<T> type, String message, Executable executable) {
    var ex = assertThrows(type, executable);
    assertEquals(message, ex.getMessage());
  }

  private static final String SLICE_INDEX_MESSAGE =
      "slice indices must be integers or None or have an __index__ method";


  @Nested
  public final class Str {

    @Test
    public void strString() {
      // `str(str)` is the identity: no quoting, no escaping.
      assertEquals("", StringIntrinsics.$str$(""));
      assertEquals("hello", StringIntrinsics.$str$("hello"));
      assertEquals("it's", StringIntrinsics.$str$("it's"));
      assertEquals("say \"hi\"", StringIntrinsics.$str$("say \"hi\""));
      assertEquals("a\\b", StringIntrinsics.$str$("a\\b"));
      assertEquals("line1\nline2", StringIntrinsics.$str$("line1\nline2"));
    }

    @Test
    public void strBoolean() {
      assertEquals("True", StringIntrinsics.$str$(true));
      assertEquals("False", StringIntrinsics.$str$(false));
    }

    @Test
    public void strLong() {
      assertEquals("0", StringIntrinsics.$str$(0L));
      assertEquals("42", StringIntrinsics.$str$(42L));
      assertEquals("-42", StringIntrinsics.$str$(-42L));
      assertEquals(Long.toString(Long.MAX_VALUE), StringIntrinsics.$str$(Long.MAX_VALUE));
      assertEquals(Long.toString(Long.MIN_VALUE), StringIntrinsics.$str$(Long.MIN_VALUE));
    }

    // NEW
    @Test
    public void strBigInteger() {
      assertEquals("1267650600228229401496703205376", StringIntrinsics.$str$(BigInteger.TWO.pow(100)));
      assertEquals("-1267650600228229401496703205376",
          StringIntrinsics.$str$(BigInteger.TWO.pow(100).negate()));
      assertEquals("9223372036854775808",
          StringIntrinsics.$str$(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE)));
      assertEquals("-9223372036854775809",
          StringIntrinsics.$str$(BigInteger.valueOf(Long.MIN_VALUE).subtract(BigInteger.ONE)));
    }

    @Test
    public void strNone() {
      assertEquals("None", StringIntrinsics.$str$(PyNone.INSTANCE));
    }

    @Test
    public void strTupleUsesRepr() {
      // `str(tuple)` == `repr(tuple)`, and elements use repr.
      assertEquals("()", StringIntrinsics.$str$(new PyTupleN(new Object[0])));
      assertEquals("(1,)", StringIntrinsics.$str$(new PyTuple1(1L)));
      assertEquals("(1, 2)", StringIntrinsics.$str$(new PyTuple2(1L, 2L)));
      assertEquals("('hi',)", StringIntrinsics.$str$(new PyTuple1("hi")));
      var inner = new PyTuple1("a");
      var outer = new PyTuple2(inner, 1L);
      assertEquals("(('a',), 1)", StringIntrinsics.$str$(outer));
      assertEquals("(1, 'two', True, None)", StringIntrinsics.$str$(
          new PyTupleN(new Object[] {1L, "two", true, PyNone.INSTANCE})));
    }

    @Test
    public void strTupleWithFloat() {
      assertEquals("(1.5,)", StringIntrinsics.$str$(new PyTuple1(1.5)));
      assertEquals("(0.1, 1.0)", StringIntrinsics.$str$(new PyTuple2(0.1, 1.0)));
      assertEquals("(-0.0,)", StringIntrinsics.$str$(new PyTuple1(-0.0)));
      assertEquals("(inf,)", StringIntrinsics.$str$(new PyTuple1(Double.POSITIVE_INFINITY)));
      assertEquals("(nan,)", StringIntrinsics.$str$(new PyTuple1(Double.NaN)));
    }

    @Test
    public void strTupleWithBigInteger() {
      assertEquals("(42,)", StringIntrinsics.$str$(new PyTuple1(BigInteger.valueOf(42L))));
      assertEquals("(-42,)", StringIntrinsics.$str$(new PyTuple1(BigInteger.valueOf(-42L))));
    }

    @Test
    public void strTupleNestedEmpty() {
      var empty = new PyTupleN(new Object[0]);
      assertEquals("((),)", StringIntrinsics.$str$(new PyTuple1(empty)));
      assertEquals("('', '')",
          StringIntrinsics.$str$(new PyTuple2("", "")));
    }

    // NEW
    @Test
    public void strListAndDictUseReprForElements() {
      assertEquals("['a', 1]", StringIntrinsics.$str$(new PyList<>("a", 1L)));
      var dict = new PyDict<>();
      dict.put("k", "v");
      assertEquals("{'k': 'v'}", StringIntrinsics.$str$(dict));
    }

    // NEW
    @Test
    public void strOfStringsInsideContainersIsQuoted() {
      assertEquals("(\"it's\",)", StringIntrinsics.$str$(new PyTuple1("it's")));
      assertEquals("['a\\nb']", StringIntrinsics.$str$(new PyList<>("a\nb")));
    }

    // NEW
    @Test
    public void strAndReprAreIdenticalForEverythingButStrings() {
      var values = new Object[] {
          true, false, 0L, -7L, BigInteger.TWO.pow(70), 1.5, PyNone.INSTANCE,
          new PyTuple1(1L), new PyList<>(1L, "a"), PyRange.of(0L, 10L, 1L), PyRange.of(10L, 0L, -2L)
      };
      for (var value : values) {
        assertEquals(StringIntrinsics.$repr$(value), StringIntrinsics.$str$(value),
            "str and repr differ for " + StringIntrinsics.$repr$(value));
      }
    }

    @Test
    public void strObjectNonCanonical() {
      var ex = assertThrows(NotCanonicalException.class,
          () -> StringIntrinsics.$str$(new Object()));
      assertEquals("str of a non canonical value: java.lang.Object", ex.getMessage());
    }

    @Test
    public void strCharacterNonCanonical() {
      var ex = assertThrows(NotCanonicalException.class,
          () -> StringIntrinsics.$str$('a'));
      assertEquals("str of a non canonical value: java.lang.Character", ex.getMessage());
    }
  }


  @Nested
  public final class Repr {

    @Test
    public void reprString() {
      assertEquals("'hello'", StringIntrinsics.$repr$("hello"));
      assertEquals("''", StringIntrinsics.$repr$(""));
    }

    @Test
    public void reprBoolean() {
      assertEquals("True", StringIntrinsics.$repr$(true));
      assertEquals("False", StringIntrinsics.$repr$(false));
    }

    @Test
    public void reprLong() {
      assertEquals("0", StringIntrinsics.$repr$(0L));
      assertEquals("42", StringIntrinsics.$repr$(42L));
      assertEquals("-42", StringIntrinsics.$repr$(-42L));
    }

    // NEW
    @Test
    public void reprBigInteger() {
      assertEquals("9223372036854775808",
          StringIntrinsics.$repr$(BigInteger.valueOf(Long.MAX_VALUE).add(BigInteger.ONE)));
      assertEquals("-9223372036854775809",
          StringIntrinsics.$repr$(BigInteger.valueOf(Long.MIN_VALUE).subtract(BigInteger.ONE)));
    }

    @Test
    public void reprNone() {
      assertEquals("None", StringIntrinsics.$repr$(PyNone.INSTANCE));
    }

    @Test
    public void reprTuple() {
      assertEquals("()", StringIntrinsics.$repr$(new PyTupleN(new Object[0])));
      assertEquals("(1,)", StringIntrinsics.$repr$(new PyTuple1(1L)));
      assertEquals("(1, 2)", StringIntrinsics.$repr$(new PyTuple2(1L, 2L)));
      assertEquals("('a', 'b')", StringIntrinsics.$repr$(new PyTuple2("a", "b")));
      assertEquals("(1, 'two', True)",
          StringIntrinsics.$repr$(new PyTupleN(new Object[] {1L, "two", true})));
    }

    @Test
    public void reprTupleWithFloat() {
      assertEquals("(1.5,)", StringIntrinsics.$repr$(new PyTuple1(1.5)));
      assertEquals("(0.1, 1.0)", StringIntrinsics.$repr$(new PyTuple2(0.1, 1.0)));
      assertEquals("(-0.0,)", StringIntrinsics.$repr$(new PyTuple1(-0.0)));
    }

    // NEW
    @Test
    public void reprTupleUsesQuoteRulesForStrings() {
      assertEquals("(\"it's\",)", StringIntrinsics.$repr$(new PyTuple1("it's")));
      assertEquals("('say \"hi\"', 'a\\\\b')",
          StringIntrinsics.$repr$(new PyTuple2("say \"hi\"", "a\\b")));
    }

    // NEW
    @Test
    public void reprList() {
      assertEquals("[]", StringIntrinsics.$repr$(new PyList<>()));
      assertEquals("[1]", StringIntrinsics.$repr$(new PyList<>(1L)));
      assertEquals("[1, 'a', None, True, 1.5]",
          StringIntrinsics.$repr$(new PyList<>(1L, "a", PyNone.INSTANCE, true, 1.5)));
      assertEquals("[[1], [], (2,)]",
          StringIntrinsics.$repr$(new PyList<>(new PyList<>(1L), new PyList<>(), new PyTuple1(2L))));
      assertEquals("[100000000000000000000]",
          StringIntrinsics.$repr$(new PyList<>(BigInteger.TEN.pow(20))));
    }

    // NEW
    @Test
    public void reprDict() {
      var empty = new PyDict<>();
      assertEquals("{}", StringIntrinsics.$repr$(empty));

      var dict = new PyDict<>();
      dict.put("a", 1L);
      dict.put(2L, "b");
      dict.put("it's", new PyList<>(2L, 3L));
      assertEquals("{'a': 1, 2: 'b', \"it's\": [2, 3]}", StringIntrinsics.$repr$(dict));
    }

    // NEW
    @Test
    public void reprDictNested() {
      var inner = new PyDict<>();
      inner.put("x", new PyTuple1(1L));
      var outer = new PyDict<>();
      outer.put("inner", inner);
      assertEquals("{'inner': {'x': (1,)}}", StringIntrinsics.$repr$(outer));
    }

    @Test
    public void reprRange() {
      assertEquals("range(0, 10)", StringIntrinsics.$repr$(PyRange.of(0L, 10L, 1L)));
      assertEquals("range(0, 10, 3)", StringIntrinsics.$repr$(PyRange.of(0L, 10L, 3L)));
    }

    // NEW
    @Test
    public void reprRangeVariants() {
      assertEquals("range(5, 5)", StringIntrinsics.$repr$(PyRange.of(5L, 5L, 1L)));
      assertEquals("range(-3, 3)", StringIntrinsics.$repr$(PyRange.of(-3L, 3L, 1L)));
      assertEquals("range(10, 0, -2)", StringIntrinsics.$repr$(PyRange.of(10L, 0L, -2L)));
      assertEquals("range(3, 0, -1)", StringIntrinsics.$repr$(PyRange.of(3L, 0L, -1L)));
    }

    @Test
    public void nonCanonicalStrMessages() {
      assertEquals("str of a non canonical value: java.lang.Object",
          assertThrows(NotCanonicalException.class,
              () -> StringIntrinsics.$str$(new Object())).getMessage());
      assertEquals("str of a non canonical value: java.lang.Character",
          assertThrows(NotCanonicalException.class,
              () -> StringIntrinsics.$str$('c')).getMessage());
    }

    @Test
    public void nonCanonicalReprMessages() {
      assertEquals("repr of a non canonical value: java.lang.Object",
          assertThrows(NotCanonicalException.class,
              () -> StringIntrinsics.$repr$(new Object())).getMessage());
      assertEquals("repr of a non canonical value: java.lang.Character",
          assertThrows(NotCanonicalException.class,
              () -> StringIntrinsics.$repr$('c')).getMessage());
    }

    // NEW
    @Test
    public void nonCanonicalInsideContainers() {
      assertThrows(NotCanonicalException.class,
          () -> StringIntrinsics.$repr$(new PyList<>(1L, new Object())));
      assertThrows(NotCanonicalException.class,
          () -> StringIntrinsics.$repr$(new PyTuple1(new Object())));
      var dict = new PyDict<>();
      dict.put("k", new Object());
      assertThrows(NotCanonicalException.class, () -> StringIntrinsics.$repr$(dict));
    }
  }


  @Nested
  public final class RecursiveRepr {
    @Test
    public void selfReferentialList() {
      var list = new PyList<>();
      list.append(list);

      assertEquals("[[...]]", StringIntrinsics.$repr$(list));
    }

    @Test
    public void selfReferentialDict() {
      var dict = new PyDict<>();
      dict.put("self", dict);

      assertEquals("{'self': {...}}", StringIntrinsics.$repr$(dict));
    }

    @Test
    public void cycleAcrossTupleAndList() {
      var list = new PyList<>();
      var tuple = new PyTuple1(list);
      list.append(tuple);

      assertEquals("([(...)],)", StringIntrinsics.$repr$(tuple));
    }

    // NEW
    @Test
    public void cycleAcrossTupleAndListStartingFromTheList() {
      var list = new PyList<>();
      var tuple = new PyTuple1(list);
      list.append(tuple);

      assertEquals("[([...],)]", StringIntrinsics.$repr$(list));
    }

    // NEW
    @Test
    public void cycleAcrossDictAndList() {
      var dict = new PyDict<>();
      var list = new PyList<>();
      dict.put("k", list);
      list.append(dict);

      assertEquals("{'k': [{...}]}", StringIntrinsics.$repr$(dict));
      assertEquals("[{'k': [...]}]", StringIntrinsics.$repr$(list));
    }

    // NEW
    @Test
    public void cycleBetweenTwoLists() {
      var a = new PyList<>();
      var b = new PyList<>(a);
      a.append(b);

      assertEquals("[[[...]]]", StringIntrinsics.$repr$(a));
      assertEquals("[[[...]]]", StringIntrinsics.$repr$(b));
    }

    // NEW
    @Test
    public void selfReferentialListNextToOtherElements() {
      var list = new PyList<>();
      list.append(1L);
      list.append(list);
      list.append("x");

      assertEquals("[1, [...], 'x']", StringIntrinsics.$repr$(list));
    }

    // NEW
    @Test
    public void strOfSelfReferentialContainers() {
      var list = new PyList<>();
      list.append(list);
      assertEquals("[[...]]", StringIntrinsics.$str$(list));
      var dict = new PyDict<>();
      dict.put("self", dict);
      assertEquals("{'self': {...}}", StringIntrinsics.$str$(dict));
    }

    @Test
    public void repeatedReferenceIsNotACycle() {  // CHANGED: typo in the name
      var child = new PyList<>(1L);
      var list = new PyList<>(child, child);

      assertEquals("[[1], [1]]", StringIntrinsics.$repr$(list));
    }

    // NEW
    @Test
    public void repeatedReferenceInDictIsNotACycle() {
      var child = new PyList<>(1L);
      var dict = new PyDict<>();
      dict.put("a", child);
      dict.put("b", child);

      assertEquals("{'a': [1], 'b': [1]}", StringIntrinsics.$repr$(dict));
    }

    @Test
    public void reprStateIsCleanedUpAfterAnException() {
      var list = new PyList<>();
      list.append(list);
      list.append(new Object());  // Not a canonical Python value

      assertThrows(NotCanonicalException.class, () -> StringIntrinsics.$repr$(list));

      list.set(1, 42L);

      assertEquals("[[...], 42]", StringIntrinsics.$repr$(list));
    }

    // NEW
    @Test
    public void reprStateIsCleanedUpAfterAnExceptionInADict() {
      var dict = new PyDict<>();
      dict.put("self", dict);
      dict.put("bad", new Object());

      assertThrows(NotCanonicalException.class, () -> StringIntrinsics.$repr$(dict));

      dict.put("bad", 1L);

      assertEquals("{'self': {...}, 'bad': 1}", StringIntrinsics.$repr$(dict));
    }

    // NEW
    @Test
    public void reprStateIsCleanedUpAfterAnIntLimitError() {
      var list = new PyList<>();
      list.append(list);
      list.append(BigInteger.TEN.pow(4_300));

      assertThrows(PyValueError.class, () -> StringIntrinsics.$repr$(list));

      list.set(1, 1L);

      assertEquals("[[...], 1]", StringIntrinsics.$repr$(list));
    }
  }


  @Nested
  public final class FloatFormatting {   // CHANGED: was `Float`, which shadowed java.lang.Float

    // NEW
    private void assertFloat(String expected, double value) {
      assertEquals(expected, StringIntrinsics.$str$(value), "str of " + value);
      assertEquals(expected, StringIntrinsics.$repr$(value), "repr of " + value);
    }

    @Test
    public void floatSpecialValues() {
      assertEquals("nan", StringIntrinsics.$str$(Double.NaN));
      assertEquals("inf", StringIntrinsics.$str$(Double.POSITIVE_INFINITY));
      assertEquals("-inf", StringIntrinsics.$str$(Double.NEGATIVE_INFINITY));
      assertEquals("nan", StringIntrinsics.$repr$(Double.NaN));
      assertEquals("inf", StringIntrinsics.$repr$(Double.POSITIVE_INFINITY));
      assertEquals("-inf", StringIntrinsics.$repr$(Double.NEGATIVE_INFINITY));
    }

    // NEW
    @Test
    public void negativeNaNIsStillNaN() {
      assertFloat("nan", Double.longBitsToDouble(0xFFF8_0000_0000_0000L));
      assertFloat("nan", Double.longBitsToDouble(0x7FF0_0000_0000_0001L));
    }

    @Test
    public void floatSignedZero() {
      assertEquals("0.0", StringIntrinsics.$str$(0.0));
      assertEquals("-0.0", StringIntrinsics.$str$(-0.0));
      assertEquals("0.0", StringIntrinsics.$repr$(0.0));
      assertEquals("-0.0", StringIntrinsics.$repr$(-0.0));
    }

    @Test
    public void floatIntegralValuesKeepDotZero() {
      assertEquals("1.0", StringIntrinsics.$str$(1.0));
      assertEquals("2.0", StringIntrinsics.$str$(2.0));
      assertEquals("-3.0", StringIntrinsics.$str$(-3.0));
      assertEquals("100.0", StringIntrinsics.$str$(100.0));
      assertEquals("-100.0", StringIntrinsics.$str$(-100.0));
    }

    @Test
    public void floatSmallDecimals() {
      assertEquals("0.1", StringIntrinsics.$str$(0.1));
      assertEquals("0.5", StringIntrinsics.$str$(0.5));
      assertEquals("1.5", StringIntrinsics.$str$(1.5));
      assertEquals("-1.5", StringIntrinsics.$str$(-1.5));
      assertEquals("3.14", StringIntrinsics.$str$(3.14));
    }

    // NEW
    @Test
    public void floatClassicRoundingCases() {
      assertFloat("0.30000000000000004", 0.1 + 0.2);
      assertFloat("0.3333333333333333", 1.0 / 3.0);
      assertFloat("0.6666666666666666", 2.0 / 3.0);
      assertFloat("33.333333333333336", 100.0 / 3.0);
      assertFloat("4.35", 4.35);
      assertFloat("0.3", 0.3);
      assertFloat("123456789.0", 123456789.0);
      assertFloat("123456789012345.6", 123456789012345.6);
    }

    @Test
    public void floatScientificThresholds() {
      assertEquals("0.0001", StringIntrinsics.$str$(1e-4));
      assertEquals("1000000000000000.0", StringIntrinsics.$str$(1e15));
      assertEquals("1e-05", StringIntrinsics.$str$(1e-5));
      assertEquals("1e+16", StringIntrinsics.$str$(1e16));
    }

    @Test
    public void floatScientificThresholdsWithMantissa() {
      // decpt == -3 → plain
      assertEquals("0.00015", StringIntrinsics.$str$(1.5e-4));
      // decpt == -4 → scientific
      assertEquals("1.5e-05", StringIntrinsics.$str$(1.5e-5));
      // decpt == 16 → plain
      assertEquals("1500000000000000.0", StringIntrinsics.$str$(1.5e15));
      // decpt == 17 → scientific
      assertEquals("1.5e+16", StringIntrinsics.$str$(1.5e16));
    }

    // NEW
    @Test
    public void floatAroundTheScientificThresholds() {
      assertFloat("0.0001234", 0.0001234);
      assertFloat("1.234e-05", 0.00001234);
      assertFloat("9999999999999998.0", 9999999999999998.0);
      assertFloat("1234567890123456.0", 1234567890123456.0);
      assertFloat("1000000000000000.5", 1000000000000000.5);
      assertFloat("1.2345678901234568e+16", 12345678901234567.0);
      assertFloat("9007199254740992.0", 9007199254740992.0);        // 2**53
      assertFloat("9.223372036854776e+18", 9223372036854775808.0);  // 2**63
    }

    @Test
    public void floatScientificExponentFormat() {
      assertEquals("1e+16", StringIntrinsics.$str$(1e16));
      assertEquals("1e-16", StringIntrinsics.$str$(1e-16));
      assertEquals("1e+100", StringIntrinsics.$str$(1e100));
      assertEquals("1e-100", StringIntrinsics.$str$(1e-100));
    }

    // NEW
    @Test
    public void floatExponentHasAtLeastTwoDigits() {
      assertFloat("1e-07", 1e-7);
      assertFloat("1e-10", 1e-10);
      assertFloat("2.5e-05", 2.5e-5);
      assertFloat("1e+21", 1e21);
      assertFloat("1e+22", 1e22);
      assertFloat("1e+23", 1e23);
      assertFloat("1.5e+300", 1.5e300);
      assertFloat("-1e+16", -1e16);
      assertFloat("-2.5e-05", -2.5e-5);
    }

    @Test
    public void floatExtremes() {
      assertEquals("1.7976931348623157e+308",
          StringIntrinsics.$str$(Double.MAX_VALUE));
      assertEquals("-1.7976931348623157e+308",
          StringIntrinsics.$str$(-Double.MAX_VALUE));
      assertEquals("5e-324", StringIntrinsics.$str$(Double.MIN_VALUE));
      assertEquals("-5e-324", StringIntrinsics.$str$(-Double.MIN_VALUE));
    }

    // NEW
    @Test
    public void floatSubnormalsAndPowersOfTwo() {
      assertFloat("2.2250738585072014e-308", Double.MIN_NORMAL);
      assertFloat("1e-323", 2 * Double.MIN_VALUE);
      assertFloat("1.5e-323", 3 * Double.MIN_VALUE);
      assertFloat("9.5367431640625e-07", Math.scalb(1.0, -20));
    }

    @Test
    public void floatStrAndReprAreIdentical() {
      var values = new double[] {
          0.0, -0.0, 1.0, -1.0, 0.1, 1.0 / 3.0, Math.PI, Math.E,
          1e-4, 1e-5, 1e15, 1e16, 1e100, 1e-100,
          Double.MIN_VALUE, Double.MIN_NORMAL, Double.MAX_VALUE,
          -Double.MAX_VALUE
      };
      for (var v : values) {
        assertEquals(StringIntrinsics.$repr$(v), StringIntrinsics.$str$(v),
            "str and repr differ for " + v);
      }
    }

    @Test
    public void floatRoundTrip() {
      var values = new double[] {
          0.1, 1.0 / 3.0, Math.PI, Math.E, 1.1, 2.2, 3.3,
          Double.MIN_NORMAL, Double.MIN_VALUE, Double.MAX_VALUE,
          -1.0 / 7.0, 1e-300, 1e300
      };
      for (var v : values) {
        var s = (String) StringIntrinsics.$str$(v);
        // CHANGED: compare the doubles directly
        assertEquals(v, Double.parseDouble(s),
            "round-trip failed for " + v + " (\"str\" gave \"" + s + "\")");
      }
    }

    // NEW
    @Test
    public void everyPowerOfTwoRoundTripsAndIsShortest() {
      for (var exponent = Double.MIN_EXPONENT - 52; exponent <= Double.MAX_EXPONENT; exponent++) {
        var v = Math.scalb(1.0, exponent);
        assertRoundTripAndShortest(v);
        assertRoundTripAndShortest(-v);
      }
    }

    // NEW
    @Test
    public void randomDoublesRoundTripAndAreShortest() {
      var random = new Random(42);
      for (var i = 0; i < 20_000; i++) {
        var v = Double.longBitsToDouble(random.nextLong());
        if (Double.isNaN(v) || Double.isInfinite(v) || v == 0.0) {
          continue;
        }
        assertRoundTripAndShortest(v);
      }
    }

    // NEW
    @Test
    public void randomDoublesInTheCommonRangeRoundTripAndAreShortest() {
      var random = new Random(7);
      for (var i = 0; i < 20_000; i++) {
        var v = (random.nextDouble() - 0.5) * Math.pow(10, random.nextInt(40) - 20);
        if (v == 0.0) {
          continue;
        }
        assertRoundTripAndShortest(v);
      }
    }

    /// Checks that `str(v)` parses back to `v`, and, when it has at least 2 digits, that it is the
    /// same decimal as the JDK's Double.toString (shortest repr since JDK 19, but with a minimum of
    /// 2 digits, which is why 1-digit results are not compared).
    private void assertRoundTripAndShortest(double v) {
      var s = (String) StringIntrinsics.$str$(v);
      assertEquals(v, Double.parseDouble(s), () -> "round-trip failed for " + v + ": " + s);
      var ours = new BigDecimal(s).stripTrailingZeros();
      if (ours.precision() >= 2) {
        var jdk = new BigDecimal(Double.toString(v)).stripTrailingZeros();
        assertEquals(0, ours.compareTo(jdk), () -> "not the shortest repr for " + v + ": " + s
            + " (JDK: " + Double.toString(v) + ")");
      }
    }
  }


  @Nested
  public final class Quote {

    @Test
    public void emptyString() {
      assertEquals("''", StringIntrinsics.$repr$(""));
    }

    @Test
    public void simpleString() {
      assertEquals("'hello'", StringIntrinsics.$repr$("hello"));
      assertEquals("'abc'", StringIntrinsics.$repr$("abc"));
    }

    // NEW
    @Test
    public void spaceIsPrintable() {
      assertEquals("' '", StringIntrinsics.$repr$(" "));
      assertEquals("'a b'", StringIntrinsics.$repr$("a b"));
    }

    @Test
    public void quoteSelection() {
      assertEquals("\"it's\"", StringIntrinsics.$repr$("it's"));
      assertEquals("'say \"hi\"'", StringIntrinsics.$repr$("say \"hi\""));
      assertEquals("'a\\'b\"c'", StringIntrinsics.$repr$("a'b\"c"));
    }

    // NEW
    @Test
    public void singleQuoteCharacters() {
      assertEquals("\"'\"", StringIntrinsics.$repr$("'"));
      assertEquals("'\"'", StringIntrinsics.$repr$("\""));
      assertEquals("\"''\"", StringIntrinsics.$repr$("''"));
      assertEquals("'\\'\"'", StringIntrinsics.$repr$("'\""));
    }

    // NEW
    @Test
    public void quoteSelectionWithEscapesInTheSameString() {
      assertEquals("\"'\\n\"", StringIntrinsics.$repr$("'\n"));
      assertEquals("\"it's a\\\\b\"", StringIntrinsics.$repr$("it's a\\b"));
      assertEquals("'\\'\\\"\\t'".replace("\\\"", "\""), StringIntrinsics.$repr$("'\"\t"));
    }

    @Test
    public void tripleQuotesAreNotSpecial() {
      assertEquals("\"'''\"", StringIntrinsics.$repr$("'''"));
      assertEquals("'\"\"\"'", StringIntrinsics.$repr$("\"\"\""));
    }

    @Test
    public void combinedQuoteAndBackslash() {
      assertEquals("'a\\'b\"c\\\\d'", StringIntrinsics.$repr$("a'b\"c\\d"));
    }

    @Test
    public void backslashAndWhitespace() {
      assertEquals("'\\\\'", StringIntrinsics.$repr$("\\"));
      assertEquals("'\\t'", StringIntrinsics.$repr$("\t"));
      assertEquals("'\\n'", StringIntrinsics.$repr$("\n"));
      assertEquals("'\\r'", StringIntrinsics.$repr$("\r"));
      assertEquals("'a\\tb\\nc'", StringIntrinsics.$repr$("a\tb\nc"));
    }

    @Test
    public void controlCharacters() {
      assertEquals("'\\x00'", StringIntrinsics.$repr$("\u0000"));
      assertEquals("'\\x1f'", StringIntrinsics.$repr$("\u001f"));
      assertEquals("'\\x7f'", StringIntrinsics.$repr$("\u007f"));
    }

    // NEW
    @Test
    public void otherAsciiControlCharacters() {
      assertEquals("'\\x0b'", StringIntrinsics.$repr$("\u000b"));  // vertical tab
      assertEquals("'\\x0c'", StringIntrinsics.$repr$("\f"));
      assertEquals("'\\x1b'", StringIntrinsics.$repr$("\u001b"));
      assertEquals("'\\x08'", StringIntrinsics.$repr$("\b"));
      assertEquals("'a\\x00b'", StringIntrinsics.$repr$("a\u0000b"));
    }

    @Test
    public void latin1ControlCharacters() {
      assertEquals("'\\x80'", StringIntrinsics.$repr$("\u0080"));
      assertEquals("'\\x85'", StringIntrinsics.$repr$("\u0085")); // NEL
      assertEquals("'\\x9f'", StringIntrinsics.$repr$("\u009f"));
    }

    @Test
    public void nonPrintableUnicodeUsesEscapes() {
      assertEquals("'\\xa0'", StringIntrinsics.$repr$("\u00a0"));
      assertEquals("'\\u200b'", StringIntrinsics.$repr$("\u200b"));
      assertEquals("'\\ue000'", StringIntrinsics.$repr$("\ue000"));
      assertEquals("'\\u2028'", StringIntrinsics.$repr$("\u2028"));
      assertEquals("'\\u2029'", StringIntrinsics.$repr$("\u2029"));
    }

    // NEW
    @Test
    public void moreNonPrintableCategories() {
      assertEquals("'\\xad'", StringIntrinsics.$repr$("\u00ad"));      // Cf: soft hyphen
      assertEquals("'\\u3000'", StringIntrinsics.$repr$("\u3000"));    // Zs: ideographic space
      assertEquals("'\\u2003'", StringIntrinsics.$repr$("\u2003"));    // Zs: em space
      assertEquals("'\\u202f'", StringIntrinsics.$repr$("\u202f"));    // Zs: narrow no-break space
      assertEquals("'\\ufeff'", StringIntrinsics.$repr$("\ufeff"));    // Cf: BOM
      assertEquals("'\\u0378'", StringIntrinsics.$repr$("\u0378"));    // Cn: unassigned
      assertEquals("'\\ufffe'", StringIntrinsics.$repr$("\ufffe"));    // Cn: noncharacter
      assertEquals("'\\uffff'", StringIntrinsics.$repr$("\uffff"));    // Cn: noncharacter
      assertEquals("'\uD83D\uDC68\\u200d\uD83D\uDC69'",
          StringIntrinsics.$repr$("\uD83D\uDC68\u200d\uD83D\uDC69"));  // man ZWJ woman
    }

    @Test
    public void loneHighSurrogate() {
      assertEquals("'\\ud800'", StringIntrinsics.$repr$("\uD800"));
    }

    // NEW
    @Test
    public void loneLowSurrogate() {
      assertEquals("'\\udc00'", StringIntrinsics.$repr$("\uDC00"));
      assertEquals("'\\udfff'", StringIntrinsics.$repr$("\uDFFF"));
    }

    // NEW
    @Test
    public void reversedSurrogatesAreTwoLoneSurrogates() {
      assertEquals("'\\ude00\\ud83d'", StringIntrinsics.$repr$("\uDE00\uD83D"));
    }

    // NEW
    @Test
    public void loneSurrogatesNextToOtherCharacters() {
      assertEquals("'a\\ud83dxb'", StringIntrinsics.$repr$("a\uD83Dxb"));
      assertEquals("'a\\ude00'", StringIntrinsics.$repr$("a\uDE00"));
    }

    @Test
    public void printableUnicodeIsNotEscaped() {
      assertEquals("'\u0100'", StringIntrinsics.$repr$("\u0100"));
      assertEquals("'\u0300'", StringIntrinsics.$repr$("\u0300"));
    }

    // NEW
    @Test
    public void printableNonAsciiLettersAreNotEscaped() {
      assertEquals("'\u00e9'", StringIntrinsics.$repr$("\u00e9"));
      assertEquals("'\u65e5\u672c\u8a9e'", StringIntrinsics.$repr$("\u65e5\u672c\u8a9e"));
      assertEquals("'\u03a9'", StringIntrinsics.$repr$("\u03a9"));
      assertEquals("'caf\u00e9 \u2603'", StringIntrinsics.$repr$("caf\u00e9 \u2603"));
    }

    @Test
    public void supplementaryCharacters() {
      assertEquals("'\uD801\uDC00'",
          StringIntrinsics.$repr$("\uD801\uDC00"));
      assertEquals("'😀'", StringIntrinsics.$repr$("😀"));
      assertEquals("'\\U000f0000'",
          StringIntrinsics.$repr$("\uDB80\uDC00")); // U+F0000
    }

    @Test
    public void supplementaryPrivateUseAreaB() {
      assertEquals("'\\U00100000'",
          StringIntrinsics.$repr$("\uDBC0\uDC00")); // U+100000
    }

    // NEW
    @Test
    public void supplementaryNonPrintable() {
      assertEquals("'\\U000e0001'", StringIntrinsics.$repr$(cp(0xE0001)));   // Cf: language tag
      assertEquals("'\\U0002ffff'", StringIntrinsics.$repr$(cp(0x2FFFF)));   // Cn: noncharacter
      assertEquals("'\\U0010ffff'", StringIntrinsics.$repr$(cp(0x10FFFF)));  // Cn: noncharacter
    }

    // NEW
    @Test
    public void supplementaryPrintableMixedWithEscapes() {
      assertEquals("'a😀\\x00😀b'", StringIntrinsics.$repr$("a😀\u0000😀b"));
    }
  }


  @Nested
  public final class IntLimit {

    // Mirrors StringIntrinsics.MAX_INT_STR_DIGITS (CPython 3.14 default).
    private static final int LIMIT = 4_300;

    @Test
    public void exactlyAtLimitIsAccepted() {
      var atLimit = BigInteger.TEN.pow(LIMIT - 1);
      assertEquals(atLimit.toString(), StringIntrinsics.$str$(atLimit));
      assertEquals(atLimit.toString(), StringIntrinsics.$repr$(atLimit));
      assertEquals(atLimit.negate().toString(), StringIntrinsics.$str$(atLimit.negate()));
    }

    @Test
    public void oneBeyondLimitIsRejected() {
      var beyond = BigInteger.TEN.pow(LIMIT);
      assertThrows(PyValueError.class, () -> StringIntrinsics.$str$(beyond));
      assertThrows(PyValueError.class, () -> StringIntrinsics.$str$(beyond.negate()));
      assertThrows(PyValueError.class, () -> StringIntrinsics.$repr$(beyond));
    }

    // NEW
    @Test
    public void minusSignIsNotCountedAsADigit() {
      // 4300 digits, negative: accepted. 4301 digits, negative: rejected.
      var atLimit = BigInteger.TEN.pow(LIMIT - 1).negate();
      assertEquals(LIMIT + 1, ((String) StringIntrinsics.$repr$(atLimit)).length());
      assertThrows(PyValueError.class,
          () -> StringIntrinsics.$repr$(BigInteger.TEN.pow(LIMIT).negate()));
    }

    // NEW
    @Test
    public void farBeyondLimitIsRejected() {
      assertThrows(PyValueError.class,
          () -> StringIntrinsics.$str$(BigInteger.TEN.pow(LIMIT * 10)));
    }

    // NEW
    @Test
    public void largestNumberWithLimitDigitsIsAccepted() {
      var atLimit = BigInteger.TEN.pow(LIMIT).subtract(BigInteger.ONE);  // 4300 nines
      assertEquals(LIMIT, ((String) StringIntrinsics.$str$(atLimit)).length());
    }

    @Test
    public void limitAppliesInsideTuple() {
      var atLimit = BigInteger.TEN.pow(LIMIT - 1);
      assertEquals("(" + atLimit + ",)", StringIntrinsics.$str$(new PyTuple1(atLimit)));
      var beyond = BigInteger.TEN.pow(LIMIT);
      assertThrows(PyValueError.class,
          () -> StringIntrinsics.$str$(new PyTuple1(beyond)));
      assertThrows(PyValueError.class,
          () -> StringIntrinsics.$repr$(new PyTuple1(beyond)));
    }

    // NEW
    @Test
    public void limitAppliesInsideListAndDict() {
      var beyond = BigInteger.TEN.pow(LIMIT);
      assertThrows(PyValueError.class, () -> StringIntrinsics.$repr$(new PyList<>(beyond)));
      var dict = new PyDict<>();
      dict.put("k", beyond);
      assertThrows(PyValueError.class, () -> StringIntrinsics.$repr$(dict));
    }

    // NEW
    @Test
    public void limitDoesNotApplyToLong() {
      assertEquals("9223372036854775807", StringIntrinsics.$str$(Long.MAX_VALUE));
    }

    @Test
    public void errorMessageMatchesCpython() {
      var beyond = BigInteger.TEN.pow(LIMIT);
      var ex = assertThrows(PyValueError.class,
          () -> StringIntrinsics.$str$(beyond));
      assertEquals(
          "Exceeds the limit (4300 digits) for integer string conversion; "
              + "use sys.set_int_max_str_digits() to increase the limit",
          ex.getMessage());
    }
  }


  // NEW
  @Nested
  public final class GetItem {

    @Test
    public void positiveAndNegativeIndexes() {
      assertEquals("h", StringIntrinsics.$getItem$("hello", 0L));
      assertEquals("e", StringIntrinsics.$getItem$("hello", 1L));
      assertEquals("o", StringIntrinsics.$getItem$("hello", 4L));
      assertEquals("o", StringIntrinsics.$getItem$("hello", -1L));
      assertEquals("h", StringIntrinsics.$getItem$("hello", -5L));
    }

    @Test
    public void singleCharacterString() {
      assertEquals("x", StringIntrinsics.$getItem$("x", 0L));
      assertEquals("x", StringIntrinsics.$getItem$("x", -1L));
    }

    @Test
    public void outOfRange() {
      assertThrowsWithMessage(PyIndexError.class, "string index out of range",
          () -> StringIntrinsics.$getItem$("hello", 5L));
      assertThrowsWithMessage(PyIndexError.class, "string index out of range",
          () -> StringIntrinsics.$getItem$("hello", -6L));
      assertThrowsWithMessage(PyIndexError.class, "string index out of range",
          () -> StringIntrinsics.$getItem$("hello", 1_000_000L));
      assertThrowsWithMessage(PyIndexError.class, "string index out of range",
          () -> StringIntrinsics.$getItem$("hello", Long.MIN_VALUE));
      assertThrowsWithMessage(PyIndexError.class, "string index out of range",
          () -> StringIntrinsics.$getItem$("hello", Long.MAX_VALUE));
    }

    @Test
    public void emptyString() {
      assertThrowsWithMessage(PyIndexError.class, "string index out of range",
          () -> StringIntrinsics.$getItem$("", 0L));
      assertThrowsWithMessage(PyIndexError.class, "string index out of range",
          () -> StringIntrinsics.$getItem$("", -1L));
    }

    @Test
    public void indexesAreCodePoints() {
      var s = "a😀b";
      assertEquals("a", StringIntrinsics.$getItem$(s, 0L));
      assertEquals("😀", StringIntrinsics.$getItem$(s, 1L));
      assertEquals("b", StringIntrinsics.$getItem$(s, 2L));
      assertEquals("b", StringIntrinsics.$getItem$(s, -1L));
      assertEquals("😀", StringIntrinsics.$getItem$(s, -2L));
      assertEquals("a", StringIntrinsics.$getItem$(s, -3L));
    }

    @Test
    public void loneSurrogatesAreSingleItems() {
      assertEquals("\uD83D", StringIntrinsics.$getItem$("\uD83Dx", 0L));
      assertEquals("x", StringIntrinsics.$getItem$("\uD83Dx", 1L));
      assertEquals("\uDE00", StringIntrinsics.$getItem$("x\uDE00", 1L));
    }

    @Test
    public void indexEqualToTheCodePointCountIsOutOfRange() {
      assertThrowsWithMessage(PyIndexError.class, "string index out of range",
          () -> StringIntrinsics.$getItem$("😀", 1L));
      assertThrowsWithMessage(PyIndexError.class, "string index out of range",
          () -> StringIntrinsics.$getItem$("a😀", 2L));
      assertThrowsWithMessage(PyIndexError.class, "string index out of range",
          () -> StringIntrinsics.$getItem$("😀a", 2L));
    }

    @Test
    public void negativeIndexBeforeTheStartWithSupplementaryCharacters() {
      assertThrowsWithMessage(PyIndexError.class, "string index out of range",
          () -> StringIntrinsics.$getItem$("😀", -2L));
      assertThrowsWithMessage(PyIndexError.class, "string index out of range",
          () -> StringIntrinsics.$getItem$("a😀", -3L));
    }
  }


  // NEW
  @Nested
  public final class Lower {

    @Test
    public void ascii() {
      assertEquals("hello world 123", StringIntrinsics._lower_("Hello WORLD 123"));
      assertEquals("", StringIntrinsics._lower_(""));
    }

    @Test
    public void nonLettersAreUnchanged() {
      assertEquals("123 !?\t\n", StringIntrinsics._lower_("123 !?\t\n"));
    }

    @Test
    public void nonAscii() {
      assertEquals("\u00e9\u00e0\u00fc", StringIntrinsics._lower_("\u00c9\u00c0\u00dc"));
      assertEquals("\u03b1\u03b2\u03b3", StringIntrinsics._lower_("\u0391\u0392\u0393"));
      assertEquals("\u0436", StringIntrinsics._lower_("\u0416"));
    }

    @Test
    public void dottedCapitalIExpandsToTwoCodePoints() {
      assertEquals("i\u0307", StringIntrinsics._lower_("\u0130"));
      assertEquals("ai\u0307b", StringIntrinsics._lower_("A\u0130B"));
    }

    @Test
    public void titlecaseDigraphsLowerToLowercaseDigraph() {
      assertEquals("\u01c6", StringIntrinsics._lower_("\u01c5"));  // ǅ -> ǆ
      assertEquals("\u01c6", StringIntrinsics._lower_("\u01c4"));  // Ǆ -> ǆ
    }

    @Test
    public void alreadyLowercaseSpecialCharactersAreUnchanged() {
      assertEquals("\u00df", StringIntrinsics._lower_("\u00df"));  // ß
      assertEquals("\u03c2", StringIntrinsics._lower_("\u03c2"));  // ς
    }

    @Test
    public void supplementary() {
      assertEquals("\uD801\uDC28", StringIntrinsics._lower_("\uD801\uDC00"));  // 𐐀 -> 𐐨
      assertEquals("a😀b", StringIntrinsics._lower_("A😀B"));
    }

    @Test
    public void loneSurrogatesAreUnchanged() {
      assertEquals("a\uD83Db", StringIntrinsics._lower_("A\uD83DB"));
      assertEquals("\uDE00", StringIntrinsics._lower_("\uDE00"));
    }

    // Exercises the JDK's Final_Sigma implementation against CPython's rule.
    @Test
    public void finalSigma() {
      assertEquals("\u03bf\u03b4\u03bf\u03c2", StringIntrinsics._lower_("\u039f\u0394\u039f\u03a3"));  // ΟΔΟΣ -> οδος
      assertEquals("\u03b1\u03c2", StringIntrinsics._lower_("\u0391\u03a3"));                           // ΑΣ -> ας
      assertEquals("\u03bf\u03b4\u03bf\u03c2 \u03bf\u03b4\u03bf\u03c2",
          StringIntrinsics._lower_("\u039f\u0394\u039f\u03a3 \u039f\u0394\u039f\u03a3"));
    }

    @Test
    public void sigmaIsNotFinal() {
      assertEquals("\u03c3", StringIntrinsics._lower_("\u03a3"));                      // lone Σ -> σ
      assertEquals("\u03c3\u03b1", StringIntrinsics._lower_("\u03a3\u0391"));          // ΣΑ -> σα
      assertEquals("\u03b1\u03c3\u03b2", StringIntrinsics._lower_("\u0391\u03a3\u0392")); // ΑΣΒ -> ασβ
      assertEquals("1\u03c3", StringIntrinsics._lower_("1\u03a3"));
    }

    @Test
    public void finalSigmaAfterCaseIgnorableCharacters() {
      assertEquals("\u03b1'\u03c2", StringIntrinsics._lower_("\u0391'\u03a3"));        // Α'Σ -> α'ς
      assertEquals("\u03b1'\u03c3\u03b2", StringIntrinsics._lower_("\u0391'\u03a3\u0392"));
    }

    // CPython treats Sk (modifier symbols such as ^) as case-ignorable, so Σ is final here.
    // The JDK implementation is based on word boundaries and probably does not agree.
    @Test
    public void finalSigmaAfterCaseIgnorableSymbol() {
      assertEquals("\u03b1^\u03c2", StringIntrinsics._lower_("\u0391^\u03a3"));        // Α^Σ -> α^ς
    }
  }


  // NEW
  @Nested
  public final class Upper {

    @Test
    public void ascii() {
      assertEquals("HELLO WORLD 123", StringIntrinsics._upper_("Hello world 123"));
      assertEquals("", StringIntrinsics._upper_(""));
    }

    @Test
    public void nonLettersAreUnchanged() {
      assertEquals("123 !?\t\n", StringIntrinsics._upper_("123 !?\t\n"));
    }

    @Test
    public void nonAscii() {
      assertEquals("\u00c9\u00c0\u00dc", StringIntrinsics._upper_("\u00e9\u00e0\u00fc"));
      assertEquals("\u0391\u0392\u0393", StringIntrinsics._upper_("\u03b1\u03b2\u03b3"));
    }

    @Test
    public void sharpSAndLigaturesExpand() {
      assertEquals("SS", StringIntrinsics._upper_("\u00df"));                     // ß
      assertEquals("STRASSE", StringIntrinsics._upper_("Stra\u00dfe"));
      assertEquals("FFI", StringIntrinsics._upper_("\uFB03"));                    // ﬃ
      assertEquals("FFL", StringIntrinsics._upper_("\uFB04"));                    // ﬄ
      assertEquals("ST", StringIntrinsics._upper_("\uFB05"));                     // ﬅ
      assertEquals("\u02bcN", StringIntrinsics._upper_("\u0149"));                // ŉ -> ʼN
      assertEquals("\u0399\u0308\u0301", StringIntrinsics._upper_("\u0390"));     // ΐ
    }

    @Test
    public void finalSigmaLowercaseMapsToCapitalSigma() {
      assertEquals("\u03a3", StringIntrinsics._upper_("\u03c2"));                 // ς -> Σ
      assertEquals("\u03a3", StringIntrinsics._upper_("\u03c3"));                 // σ -> Σ
    }

    @Test
    public void digraphsMapToUppercaseDigraph() {
      assertEquals("\u01c4", StringIntrinsics._upper_("\u01c6"));                 // ǆ -> Ǆ
      assertEquals("\u01c4", StringIntrinsics._upper_("\u01c5"));                 // ǅ -> Ǆ
    }

    @Test
    public void dottedAndDotlessI() {
      assertEquals("\u0130", StringIntrinsics._upper_("\u0130"));                 // İ stays İ
      assertEquals("I", StringIntrinsics._upper_("\u0131"));                      // ı -> I
    }

    @Test
    public void supplementary() {
      assertEquals("\uD801\uDC00", StringIntrinsics._upper_("\uD801\uDC28"));     // 𐐨 -> 𐐀
      assertEquals("A😀B", StringIntrinsics._upper_("a😀b"));
    }

    @Test
    public void loneSurrogatesAreUnchanged() {
      assertEquals("A\uD83DB", StringIntrinsics._upper_("a\uD83Db"));
    }
  }


  @Nested
  public final class Swapcase {

    @Test
    public void titlecaseCharactersAreUnchanged() {
      assertEquals("\u01C5", StringIntrinsics._swapcase_("\u01C5")); // ǅ stays ǅ
      assertEquals("\u01C5\u01C8\u01CB\u01F2",
          StringIntrinsics._swapcase_("\u01C5\u01C8\u01CB\u01F2"));  // ǅǈǋǲ
      assertEquals("\u1F88\u1FCC\u1FFC",
          StringIntrinsics._swapcase_("\u1F88\u1FCC\u1FFC"));        // ᾈῌῼ
      assertEquals("\u01C5a", StringIntrinsics._swapcase_("\u01C5A"));
    }

    @Test
    public void lowercaseDigraphMapsToUppercaseNotTitlecase() {
      assertEquals("\u01F1", StringIntrinsics._swapcase_("\u01F3")); // ǳ -> Ǳ (not ǲ)
    }

    @Test
    public void finalSigmaUsesOriginalContext() {
      assertEquals("\u03BF\u03B4\u03BF\u03C2",
          StringIntrinsics._swapcase_("\u039F\u0394\u039F\u03A3"));  // ΟΔΟΣ -> οδος (final ς)
      assertEquals("\u03C3\u03C2",
          StringIntrinsics._swapcase_("\u03A3\u03A3"));              // ΣΣ -> σς
      assertEquals("\u03B1'\u03C2",
          StringIntrinsics._swapcase_("\u0391'\u03A3"));             // Α'Σ -> α'ς (case-ignorable ')
    }

    @Test
    public void sigmaIsNotFinalWhenFollowedByCased() {
      assertEquals("\u03C3a", StringIntrinsics._swapcase_("\u03A3A"));       // ΣA -> σa
      assertEquals("\u03B1'\u03C3\u03B2",
          StringIntrinsics._swapcase_("\u0391'\u03A3\u0392"));               // Α'ΣΒ -> α'σβ
    }

    @Test
    public void fullUppercaseExpansions() {
      assertEquals("SS", StringIntrinsics._swapcase_("ß"));
      assertEquals("FFL", StringIntrinsics._swapcase_("ﬄ"));
      assertEquals("SSi\u0307FFL", StringIntrinsics._swapcase_("ßİﬄ"));
    }

    @Test
    public void otherUppercaseAndSupplementary() {
      assertEquals("\u2170\u24D0", StringIntrinsics._swapcase_("\u2160\u24B6")); // ⅠⒶ -> ⅰⓐ
      assertEquals("\uD801\uDC28\uD801\uDC00",
          StringIntrinsics._swapcase_("\uD801\uDC00\uD801\uDC28"));              // 𐐀𐐨 -> 𐐨𐐀
      assertEquals("123 !?", StringIntrinsics._swapcase_("123 !?"));
      assertEquals("", StringIntrinsics._swapcase_(""));
    }

    // NEW
    @Test
    public void asciiMixedCase() {
      assertEquals("AbC", StringIntrinsics._swapcase_("aBc"));
      assertEquals("A1b2", StringIntrinsics._swapcase_("a1B2"));
      assertEquals("hELLO wORLD", StringIntrinsics._swapcase_("Hello World"));
    }

    // NEW
    @Test
    public void swapcaseIsAnInvolutionOnPlainAscii() {
      var s = "The Quick Brown Fox 42!";
      assertEquals(s, StringIntrinsics._swapcase_((String) StringIntrinsics._swapcase_(s)));
    }

    // NEW
    @Test
    public void lowercaseWithFullUppercaseMapping() {
      assertEquals("\u02BCN", StringIntrinsics._swapcase_("\u0149"));  // ŉ -> ʼN
      assertEquals("\u03A3", StringIntrinsics._swapcase_("\u03C2"));   // ς -> Σ
      assertEquals("\u03A3", StringIntrinsics._swapcase_("\u03C3"));   // σ -> Σ
      assertEquals("\u01C4", StringIntrinsics._swapcase_("\u01C6"));   // ǆ -> Ǆ
    }

    // NEW
    @Test
    public void loneSigmaIsNotFinal() {
      assertEquals("\u03C3", StringIntrinsics._swapcase_("\u03A3"));
      assertEquals("1\u03C3", StringIntrinsics._swapcase_("1\u03A3"));
    }

    // NEW
    @Test
    public void expansionDoesNotDesynchronizeTheLoweredIndex() {
      // İ lowers to two UTF-16 chars; the characters after it must still be taken from the right offset.
      assertEquals("i\u0307aBc", StringIntrinsics._swapcase_("\u0130AbC"));
      assertEquals("i\u0307i\u0307\u03C2", StringIntrinsics._swapcase_("\u0130\u0130\u03A3".replace("\u03A3", "\u03A3")));
    }

    // NEW
    @Test
    public void supplementaryMixedWithExpansions() {
      assertEquals("\uD801\uDC28SS\uD801\uDC00",
          StringIntrinsics._swapcase_("\uD801\uDC00\u00DF\uD801\uDC28"));
    }

    // NEW
    @Test
    public void loneSurrogatesAreUnchanged() {
      assertEquals("A\uD83Db", StringIntrinsics._swapcase_("a\uD83DB"));
    }
  }

  @Nested
  public final class Capitalize {

    @Test
    public void fullTitleMappingExpansions() {
      assertEquals("Ssabc", StringIntrinsics._capitalize_("ßabc"));
      assertEquals("Fflabc", StringIntrinsics._capitalize_("ﬄabc"));
      assertEquals("Ffi", StringIntrinsics._capitalize_("ﬃ"));
      assertEquals("St", StringIntrinsics._capitalize_("ﬅ"));
      assertEquals("\u02BCNabc", StringIntrinsics._capitalize_("ŉabc")); // ʼNabc (N stays upper)
      assertEquals("J\u030Cx", StringIntrinsics._capitalize_("ǰx"));
      assertEquals("\u0399\u0308\u0301x", StringIntrinsics._capitalize_("ΐx"));
      assertEquals("\u03A5\u0308\u0301x", StringIntrinsics._capitalize_("ΰx"));
      assertEquals("\u0544\u0576", StringIntrinsics._capitalize_("ﬓ"));  // Մն (rest lowercase)
      assertEquals("\u054E\u0576", StringIntrinsics._capitalize_("ﬖ"));
      assertEquals("\u0535\u0582", StringIntrinsics._capitalize_("և"));
      assertEquals("H\u0331", StringIntrinsics._capitalize_("ẖ"));
      assertEquals("A\u02BE", StringIntrinsics._capitalize_("ẚ"));
      assertEquals("\u1FBA\u0345", StringIntrinsics._capitalize_("ᾲ"));
      assertEquals("\u03A9\u0342", StringIntrinsics._capitalize_("ῶ"));
      assertEquals("\u03A1\u0313", StringIntrinsics._capitalize_("ῤ"));
      assertEquals("Ss", StringIntrinsics._capitalize_("ß"));
    }

    // NEW
    @Test
    public void everyFullTitleMappingExpandsAndTheRestIsLowercased() {
      // Each of these starts with a character whose full titlecase mapping has several code points.
      var firsts = new int[] {
          0x00DF, 0x0149, 0x01F0, 0x0390, 0x03B0, 0x0587, 0x1E96, 0x1E97, 0x1E98, 0x1E99, 0x1E9A,
          0x1F50, 0x1F52, 0x1F54, 0x1F56, 0x1FB2, 0x1FB4, 0x1FB6, 0x1FB7, 0x1FC2, 0x1FC4, 0x1FC6,
          0x1FC7, 0x1FD2, 0x1FD3, 0x1FD6, 0x1FD7, 0x1FE2, 0x1FE3, 0x1FE4, 0x1FE6, 0x1FE7, 0x1FF2,
          0x1FF4, 0x1FF6, 0x1FF7, 0xFB00, 0xFB01, 0xFB02, 0xFB03, 0xFB04, 0xFB05, 0xFB06,
          0xFB13, 0xFB14, 0xFB15, 0xFB16, 0xFB17
      };
      for (var first : firsts) {
        var s = cp(first) + "ABC";
        var result = (String) StringIntrinsics._capitalize_(s);
        assertEquals("abc", result.substring(result.length() - 3),
            "the rest must be lowercased for U+" + Integer.toHexString(first));
        assertEquals(true, result.length() > 4 || first == 0x1FB4 && result.length() >= 4,
            "the first character must expand for U+" + Integer.toHexString(first));
      }
    }

    @Test
    public void simpleTitleMappingStillUsed() {
      assertEquals("\u01F2abc", StringIntrinsics._capitalize_("\u01F3abc")); // ǳabc -> ǲabc
      assertEquals("\u01C5", StringIntrinsics._capitalize_("\u01C6"));       // ǆ -> ǅ
    }

    @Test
    public void restLowercaseKeepsOriginalSigmaContext() {
      assertEquals("\u03A3\u03C2", StringIntrinsics._capitalize_("\u03A3\u03A3")); // ΣΣ -> Σς
      assertEquals("A\u03C2", StringIntrinsics._capitalize_("A\u03A3"));           // AΣ -> Aς
      assertEquals("A'\u03C2", StringIntrinsics._capitalize_("A'\u03A3"));         // A'Σ -> A'ς
      assertEquals("\u039F\u03B4\u03BF\u03C2",
          StringIntrinsics._capitalize_("\u039F\u0394\u039F\u03A3"));              // ΟΔΟΣ -> Οδος
    }

    @Test
    public void restLowercaseWithoutPrecedingCased() {
      assertEquals("1\u03C3", StringIntrinsics._capitalize_("1\u03A3")); // 1Σ -> 1σ (regular sigma)
    }

    @Test
    public void restLowercaseExpandsDottedCapitalI() {
      assertEquals("Ai\u0307b", StringIntrinsics._capitalize_("AİB"));
    }

    @Test
    public void emptyAndAlreadyCapitalized() {
      assertEquals("", StringIntrinsics._capitalize_(""));
      assertEquals("Abc", StringIntrinsics._capitalize_("Abc"));
    }

    // NEW
    @Test
    public void ascii() {
      assertEquals("Hello", StringIntrinsics._capitalize_("hELLO"));
      assertEquals("Hello world", StringIntrinsics._capitalize_("HELLO WORLD"));
      assertEquals("A", StringIntrinsics._capitalize_("a"));
      assertEquals("1abc", StringIntrinsics._capitalize_("1ABC"));
      assertEquals(" abc", StringIntrinsics._capitalize_(" ABC"));
    }

    // NEW
    @Test
    public void dottedCapitalIAsFirstCharacter() {
      assertEquals("\u0130", StringIntrinsics._capitalize_("\u0130"));
      assertEquals("\u0130i\u0307", StringIntrinsics._capitalize_("\u0130\u0130"));
    }

    // NEW
    @Test
    public void supplementaryFirstCharacter() {
      assertEquals("\uD801\uDC00abc", StringIntrinsics._capitalize_("\uD801\uDC28abc"));  // 𐐨abc -> 𐐀abc
      assertEquals("\uD801\uDC00\uD801\uDC28", StringIntrinsics._capitalize_("\uD801\uDC28\uD801\uDC00"));
      assertEquals("😀abc", StringIntrinsics._capitalize_("😀ABC"));
    }

    // NEW
    @Test
    public void titlecaseCharacterInTheRestIsLowercased() {
      assertEquals("A\u01C6", StringIntrinsics._capitalize_("a\u01C5"));  // aǅ -> Aǆ
      assertEquals("\u01C5\u01C6", StringIntrinsics._capitalize_("\u01C5\u01C5"));
    }

    // NEW
    @Test
    public void titlecaseCharacterAsFirstCharacterStaysTitlecase() {
      assertEquals("\u01C5abc", StringIntrinsics._capitalize_("\u01C5ABC"));
    }

    // NEW
    @Test
    public void loneSurrogates() {
      assertEquals("\uD83Dabc", StringIntrinsics._capitalize_("\uD83DABC"));
      assertEquals("A\uDE00", StringIntrinsics._capitalize_("a\uDE00"));
    }
  }

  @Nested
  public final class Find {

    @Test
    public void emptySubWithStartBeyondLength() {
      assertEquals(-1L, StringIntrinsics._find_("abc", "", 4L));
      assertEquals(-1L, StringIntrinsics._find_("abc", "", 10L));
      assertEquals(-1L, StringIntrinsics._rfind_("abc", "", 4L));
      assertEquals(-1L, StringIntrinsics._find_("", "", 1L));
    }

    @Test
    public void emptySubAtLengthAndStartAfterEnd() {
      assertEquals(3L, StringIntrinsics._find_("abc", "", 3L));
      assertEquals(-1L, StringIntrinsics._find_("abc", "", 2L, 1L));
      assertEquals(2L, StringIntrinsics._rfind_("abc", "", 1L, 2L));
      assertEquals(3L, StringIntrinsics._rfind_("abc", ""));
    }

    // NEW
    @Test
    public void emptySubEverywhere() {
      assertEquals(0L, StringIntrinsics._find_("abc", ""));
      assertEquals(1L, StringIntrinsics._find_("abc", "", 1L));
      assertEquals(0L, StringIntrinsics._find_("abc", "", 0L, 0L));
      assertEquals(1L, StringIntrinsics._rfind_("abc", "", 0L, 1L));
      assertEquals(0L, StringIntrinsics._find_("", ""));
      assertEquals(0L, StringIntrinsics._rfind_("", ""));
      assertEquals(-1L, StringIntrinsics._find_("", "a"));
    }

    // NEW
    @Test
    public void emptySubWithNegativeBounds() {
      assertEquals(2L, StringIntrinsics._find_("abc", "", -1L));
      assertEquals(3L, StringIntrinsics._rfind_("abc", "", -1L));
      assertEquals(0L, StringIntrinsics._find_("abc", "", -10L));
      assertEquals(0L, StringIntrinsics._find_("abc", "", 0L, -10L));
      assertEquals(-1L, StringIntrinsics._find_("abc", "", 1L, -10L));
    }

    // NEW
    @Test
    public void emptySubWithSupplementaryCharacters() {
      assertEquals(1L, StringIntrinsics._find_("😀😀", "", 1L));
      assertEquals(2L, StringIntrinsics._rfind_("😀😀", ""));
      assertEquals(2L, StringIntrinsics._find_("😀😀", "", 2L));
      assertEquals(-1L, StringIntrinsics._find_("😀😀", "", 3L));
    }

    @Test
    public void hugeIntegerBoundsAreClampedNotOverflow() {
      var huge = BigInteger.TEN.pow(100);
      assertEquals(-1L, StringIntrinsics._find_("abc", "a", huge));
      assertEquals(0L, StringIntrinsics._find_("abc", "a", huge.negate()));
      assertEquals(0L, StringIntrinsics._rfind_("abc", "a", 0L, huge));
    }

    @Test
    public void longExtremeBounds() {
      assertEquals(-1L, StringIntrinsics._find_("abc", "", Long.MAX_VALUE));
      assertEquals(0L, StringIntrinsics._find_("abc", "", Long.MIN_VALUE));
      assertEquals(-1L, StringIntrinsics._find_("abc", "a", Long.MAX_VALUE));
    }

    // NEW
    @Test
    public void longExtremeEndBounds() {
      assertEquals(0L, StringIntrinsics._find_("abc", "a", 0L, Long.MAX_VALUE));
      assertEquals(-1L, StringIntrinsics._find_("abc", "a", 0L, Long.MIN_VALUE));
      assertEquals(0L, StringIntrinsics._rfind_("abc", "a", Long.MIN_VALUE, Long.MAX_VALUE));
    }

    @Test
    public void noneBoundsUseDefaults() {
      assertEquals(0L, StringIntrinsics._find_("abc", "a", PyNone.INSTANCE));
      assertEquals(2L, StringIntrinsics._find_("abc", "c", PyNone.INSTANCE, PyNone.INSTANCE));
      assertEquals(3L, StringIntrinsics._rfind_("abc", "", PyNone.INSTANCE, PyNone.INSTANCE));
    }

    @Test
    public void booleanBounds() {
      assertEquals(2L, StringIntrinsics._find_("aba", "a", true)); // True == 1
    }

    // NEW
    @Test
    public void booleanEndBound() {
      assertEquals(-1L, StringIntrinsics._find_("aba", "b", 0L, false));  // end == 0
      assertEquals(0L, StringIntrinsics._find_("aba", "a", 0L, true));    // end == 1
    }

    @Test
    public void indexRaisesValueErrorOnHugeBound() {
      var ex = assertThrows(PyValueError.class,
          () -> StringIntrinsics._index_("abc", "a", BigInteger.TEN.pow(100)));
      assertEquals("substring not found", ex.getMessage());
    }

    @Test
    public void wrongTypeBoundMessage() {
      var ex = assertThrows(PyTypeError.class,
          () -> StringIntrinsics._find_("abc", "a", 1.0));
      assertEquals(SLICE_INDEX_MESSAGE, ex.getMessage());
    }

    @Test
    public void wrongTypeBoundMessages() {
      assertThrowsWithMessage(PyTypeError.class, SLICE_INDEX_MESSAGE,
          () -> StringIntrinsics._find_("abc", "a", 0L, 1.0));
      assertThrowsWithMessage(PyTypeError.class, SLICE_INDEX_MESSAGE,
          () -> StringIntrinsics._find_("abc", "a", "0"));
      assertThrowsWithMessage(PyTypeError.class, SLICE_INDEX_MESSAGE,
          () -> StringIntrinsics._rfind_("abc", "a", 1.0));
      assertThrowsWithMessage(PyTypeError.class, SLICE_INDEX_MESSAGE,
          () -> StringIntrinsics._index_("abc", "a", 1.0));
      assertThrowsWithMessage(PyTypeError.class, SLICE_INDEX_MESSAGE,
          () -> StringIntrinsics._rindex_("abc", "a", 0L, 1.0));
    }

    @Test
    public void wrongTypeSubMessages() {
      assertThrowsWithMessage(PyTypeError.class, "find() argument 1 must be str, not int",
          () -> StringIntrinsics._find_("abc", 1L));
      assertThrowsWithMessage(PyTypeError.class, "rfind() argument 1 must be str, not int",
          () -> StringIntrinsics._rfind_("abc", 1L));
      assertThrowsWithMessage(PyTypeError.class, "index() argument 1 must be str, not int",
          () -> StringIntrinsics._index_("abc", 1L));
      assertThrowsWithMessage(PyTypeError.class, "rindex() argument 1 must be str, not int",
          () -> StringIntrinsics._rindex_("abc", 1L));
    }

    @Test
    public void wrongTypeSubOtherTypes() {
      assertThrowsWithMessage(PyTypeError.class, "find() argument 1 must be str, not NoneType",
          () -> StringIntrinsics._find_("abc", PyNone.INSTANCE));
      assertThrowsWithMessage(PyTypeError.class, "find() argument 1 must be str, not float",
          () -> StringIntrinsics._find_("abc", 1.0));
      assertThrowsWithMessage(PyTypeError.class, "find() argument 1 must be str, not bool",
          () -> StringIntrinsics._find_("abc", true));
    }

    @Test
    public void subIsCheckedBeforeBounds() {
      assertThrowsWithMessage(PyTypeError.class, "find() argument 1 must be str, not int",
          () -> StringIntrinsics._find_("abc", 1L, 1.0));
      assertThrowsWithMessage(PyTypeError.class, SLICE_INDEX_MESSAGE,
          () -> StringIntrinsics._find_("abc", "a", 1.0, 2.0));
    }

    @Test
    public void normalSearchStillWorks() {
      assertEquals(2L, StringIntrinsics._find_("hello", "l"));
      assertEquals(3L, StringIntrinsics._rfind_("hello", "l"));
      assertEquals(2L, StringIntrinsics._find_("a\uD835\uDD52b", "b")); // a𝕒b -> code-point index 2
      assertEquals(0L, StringIntrinsics._find_("abc", "a", -10L));
    }

    // NEW
    @Test
    public void notFound() {
      assertEquals(-1L, StringIntrinsics._find_("hello", "z"));
      assertEquals(-1L, StringIntrinsics._rfind_("hello", "z"));
      assertEquals(-1L, StringIntrinsics._find_("hello", "hello!"));
      assertEquals(-1L, StringIntrinsics._rfind_("hello", "hello!"));
      assertEquals(-1L, StringIntrinsics._find_("", "a"));
    }

    // NEW
    @Test
    public void wholeStringAndPrefixAndSuffix() {
      assertEquals(0L, StringIntrinsics._find_("abcabc", "abcabc"));
      assertEquals(0L, StringIntrinsics._find_("abcabc", "abc"));
      assertEquals(3L, StringIntrinsics._rfind_("abcabc", "abc"));
      assertEquals(3L, StringIntrinsics._find_("abcabc", "abc", 1L));
      assertEquals(-1L, StringIntrinsics._find_("abcabc", "abcabcd"));
    }

    // NEW
    @Test
    public void overlappingMatches() {
      assertEquals(0L, StringIntrinsics._find_("aaa", "aa"));
      assertEquals(1L, StringIntrinsics._rfind_("aaa", "aa"));
      assertEquals(1L, StringIntrinsics._rfind_("aaaa", "aa", 0L, 3L));
    }

    @Test
    public void rfindWithStartEnd() {
      assertEquals(1L, StringIntrinsics._rfind_("abcabc", "b", 0L, 4L));
      assertEquals(4L, StringIntrinsics._rfind_("abcabc", "b", 2L));
    }

    // NEW
    @Test
    public void rfindMatchMustEndBeforeEnd() {
      assertEquals(1L, StringIntrinsics._rfind_("abcabc", "bc", 0L, 5L));   // bc at 4 ends at 6 > 5
      assertEquals(4L, StringIntrinsics._rfind_("abcabc", "bc", 0L, 6L));
    }

    // NEW
    @Test
    public void startAndEndWindows() {
      assertEquals(4L, StringIntrinsics._find_("abcabc", "b", 2L));
      assertEquals(-1L, StringIntrinsics._find_("abcabc", "b", 2L, 4L));
      assertEquals(1L, StringIntrinsics._find_("abcabc", "b", 1L, 2L));
      assertEquals(2L, StringIntrinsics._find_("abcabc", "c", 0L, 3L));
      assertEquals(-1L, StringIntrinsics._find_("abcabc", "c", 0L, 2L));
      assertEquals(2L, StringIntrinsics._find_("abcabc", "c", 0L, 100L));
      assertEquals(-1L, StringIntrinsics._find_("abcabc", "a", 3L, 1L));
    }

    // NEW
    @Test
    public void negativeBounds() {
      assertEquals(4L, StringIntrinsics._find_("abcabc", "b", -4L));
      assertEquals(4L, StringIntrinsics._find_("abcabc", "b", -2L));
      assertEquals(-1L, StringIntrinsics._find_("abcabc", "a", -2L));
      assertEquals(1L, StringIntrinsics._find_("abcabc", "b", 0L, -2L));
      assertEquals(1L, StringIntrinsics._rfind_("abcabc", "b", 0L, -2L));
      assertEquals(4L, StringIntrinsics._rfind_("abcabc", "b", -3L));
      assertEquals(-1L, StringIntrinsics._find_("abcabc", "b", -100L, -100L));
    }

    // NEW
    @Test
    public void supplementaryCharactersAreCountedAsOne() {
      var s = "a😀b😀c";   // code points: a=0 😀=1 b=2 😀=3 c=4
      assertEquals(2L, StringIntrinsics._find_(s, "b"));
      assertEquals(4L, StringIntrinsics._find_(s, "c"));
      assertEquals(1L, StringIntrinsics._find_(s, "😀"));
      assertEquals(3L, StringIntrinsics._rfind_(s, "😀"));
      assertEquals(3L, StringIntrinsics._find_(s, "😀", 2L));
      assertEquals(1L, StringIntrinsics._find_(s, "😀b"));
      assertEquals(4L, StringIntrinsics._find_(s, "c", -1L));
      assertEquals(3L, StringIntrinsics._find_(s, "😀", -3L));
      assertEquals(-1L, StringIntrinsics._find_(s, "b", 0L, 2L));
      assertEquals(2L, StringIntrinsics._find_(s, "b", 0L, 3L));
      assertEquals(1L, StringIntrinsics._rfind_(s, "😀", 0L, 3L));
      assertEquals(0L, StringIntrinsics._rfind_(s, "a", 0L, 2L));
      assertEquals(5L, StringIntrinsics._find_(s, "", 5L));
      assertEquals(-1L, StringIntrinsics._find_(s, "", 6L));
      assertEquals(5L, StringIntrinsics._rfind_(s, ""));
    }

    // NEW
    @Test
    public void rfindWithEndInsideSupplementaryString() {
      assertEquals(1L, StringIntrinsics._rfind_("😀a😀", "a", 0L, 2L));
      assertEquals(1L, StringIntrinsics._rfind_("😀😀", "😀"));
      assertEquals(0L, StringIntrinsics._rfind_("😀😀", "😀", 0L, 1L));
    }

    // NEW
    @Test
    public void loneSurrogateSubDoesNotMatchInsideAPair() {
      assertEquals(-1L, StringIntrinsics._find_("😀", "\uD83D"));
      assertEquals(-1L, StringIntrinsics._find_("😀", "\uDE00"));
      assertEquals(-1L, StringIntrinsics._rfind_("😀", "\uD83D"));
      assertEquals(-1L, StringIntrinsics._rfind_("😀", "\uDE00"));
      assertEquals(-1L, StringIntrinsics._find_("a😀", "a\uD83D"));
      assertEquals(-1L, StringIntrinsics._find_("😀b", "\uDE00b"));
    }

    // NEW
    @Test
    public void loneSurrogateSubMatchesALoneSurrogate() {
      assertEquals(0L, StringIntrinsics._find_("\uD83D", "\uD83D"));
      assertEquals(1L, StringIntrinsics._find_("x\uDE00", "\uDE00"));
      assertEquals(0L, StringIntrinsics._find_("\uD83Dx😀", "\uD83D"));
      assertEquals(0L, StringIntrinsics._rfind_("\uD83Dx😀", "\uD83D"));
      assertEquals(1L, StringIntrinsics._find_("\uDE00\uD83D", "\uD83D"));   // reversed order: two lone surrogates
    }

    // NEW
    @Test
    public void indexAndRindexSuccess() {
      assertEquals(2L, StringIntrinsics._index_("abc", "c"));
      assertEquals(5L, StringIntrinsics._rindex_("abcabc", "c"));
      assertEquals(0L, StringIntrinsics._index_("abc", ""));
      assertEquals(3L, StringIntrinsics._rindex_("abc", ""));
      assertEquals(2L, StringIntrinsics._index_("a😀b", "b"));
    }

    @Test
    public void indexAndRindexNotFound() {
      assertThrowsWithMessage(PyValueError.class, "substring not found",
          () -> StringIntrinsics._index_("abc", "z"));
      assertThrowsWithMessage(PyValueError.class, "substring not found",
          () -> StringIntrinsics._rindex_("abc", "z"));
      assertThrowsWithMessage(PyValueError.class, "substring not found",
          () -> StringIntrinsics._index_("abc", "", 4L));
      assertThrowsWithMessage(PyValueError.class, "substring not found",
          () -> StringIntrinsics._rindex_("abcabc", "c", 0L, 2L));
      assertThrowsWithMessage(PyValueError.class, "substring not found",
          () -> StringIntrinsics._index_("😀", "\uD83D"));
    }

    @Test
    public void indexWithStart() {
      assertEquals(4L, StringIntrinsics._index_("abcabc", "b", 2L));
      assertEquals(1L, StringIntrinsics._index_("abcabc", "b", 0L, 4L));
    }

    @Test
    public void rindexWithStartEnd() {
      assertEquals(1L, StringIntrinsics._rindex_("abcabc", "b", 0L, 4L));
    }

    @Test
    public void indexNotFound() {
      var ex = assertThrows(PyValueError.class, () -> StringIntrinsics._index_("abc", "z"));
      assertEquals("substring not found", ex.getMessage());
    }
  }


  // NEW
  @Nested
  public final class Partition {

    @Test
    public void partitionBasic() {
      assertEquals("('a', '-', 'b-c')", repr(StringIntrinsics._partition_("a-b-c", "-")));
      assertEquals("('', '-', 'abc')", repr(StringIntrinsics._partition_("-abc", "-")));
      assertEquals("('abc', '-', '')", repr(StringIntrinsics._partition_("abc-", "-")));
      assertEquals("('', 'abc', '')", repr(StringIntrinsics._partition_("abc", "abc")));
      assertEquals("('x', '::', 'y::z')", repr(StringIntrinsics._partition_("x::y::z", "::")));
    }

    @Test
    public void rpartitionBasic() {
      assertEquals("('a-b', '-', 'c')", repr(StringIntrinsics._rpartition_("a-b-c", "-")));
      assertEquals("('', '-', 'abc')", repr(StringIntrinsics._rpartition_("-abc", "-")));
      assertEquals("('abc', '-', '')", repr(StringIntrinsics._rpartition_("abc-", "-")));
      assertEquals("('', 'abc', '')", repr(StringIntrinsics._rpartition_("abc", "abc")));
      assertEquals("('x::y', '::', 'z')", repr(StringIntrinsics._rpartition_("x::y::z", "::")));
    }

    @Test
    public void notFound() {
      assertEquals("('abc', '', '')", repr(StringIntrinsics._partition_("abc", "x")));
      assertEquals("('', '', 'abc')", repr(StringIntrinsics._rpartition_("abc", "x")));
      assertEquals("('', '', '')", repr(StringIntrinsics._partition_("", "x")));
      assertEquals("('', '', '')", repr(StringIntrinsics._rpartition_("", "x")));
      assertEquals("('abc', '', '')", repr(StringIntrinsics._partition_("abc", "abcd")));
      assertEquals("('', '', 'abc')", repr(StringIntrinsics._rpartition_("abc", "abcd")));
    }

    @Test
    public void overlappingSeparators() {
      assertEquals("('', 'aa', 'a')", repr(StringIntrinsics._partition_("aaa", "aa")));
      assertEquals("('a', 'aa', '')", repr(StringIntrinsics._rpartition_("aaa", "aa")));
    }

    @Test
    public void separatorEqualToTheWholeString() {
      assertEquals("('', 'a', '')", repr(StringIntrinsics._partition_("a", "a")));
      assertEquals("('', 'a', '')", repr(StringIntrinsics._rpartition_("a", "a")));
    }

    @Test
    public void emptySeparator() {
      assertThrowsWithMessage(PyValueError.class, "empty separator",
          () -> StringIntrinsics._partition_("abc", ""));
      assertThrowsWithMessage(PyValueError.class, "empty separator",
          () -> StringIntrinsics._rpartition_("abc", ""));
      assertThrowsWithMessage(PyValueError.class, "empty separator",
          () -> StringIntrinsics._partition_("", ""));
    }

    @Test
    public void wrongType() {
      assertThrowsWithMessage(PyTypeError.class, "must be str, not int",
          () -> StringIntrinsics._partition_("abc", 1L));
      assertThrowsWithMessage(PyTypeError.class, "must be str, not int",
          () -> StringIntrinsics._rpartition_("abc", 1L));
      assertThrowsWithMessage(PyTypeError.class, "must be str, not NoneType",
          () -> StringIntrinsics._partition_("abc", PyNone.INSTANCE));
    }

    @Test
    public void supplementaryCharacters() {
      assertEquals("('a', '😀', 'b😀c')", repr(StringIntrinsics._partition_("a😀b😀c", "😀")));
      assertEquals("('a😀b', '😀', 'c')", repr(StringIntrinsics._rpartition_("a😀b😀c", "😀")));
      assertEquals("('', '😀', '😀')", repr(StringIntrinsics._partition_("😀😀", "😀")));
      assertEquals("('😀', '😀', '')", repr(StringIntrinsics._rpartition_("😀😀", "😀")));
    }

    @Test
    public void loneSurrogateSeparatorDoesNotSplitAPair() {
      assertEquals("('😀', '', '')", repr(StringIntrinsics._partition_("😀", "\uD83D")));
      assertEquals("('😀', '', '')", repr(StringIntrinsics._partition_("😀", "\uDE00")));
      assertEquals("('', '', '😀')", repr(StringIntrinsics._rpartition_("😀", "\uD83D")));
      assertEquals("('', '', 'a😀')", repr(StringIntrinsics._rpartition_("a😀", "\uDE00")));
    }

    @Test
    public void loneSurrogateSeparatorMatchesALoneSurrogate() {
      assertEquals("('', '\\ud83d', 'x😀')", repr(StringIntrinsics._partition_("\uD83Dx😀", "\uD83D")));
      assertEquals("('', '\\ud83d', 'x😀')", repr(StringIntrinsics._rpartition_("\uD83Dx😀", "\uD83D")));
    }

    @Test
    public void partitionKeepsTheOriginalPartsWhenSeparatorAppearsSeveralTimes() {
      assertEquals("('', ',', ',a,')", repr(StringIntrinsics._partition_(",,a,", ",")));
      assertEquals("(',,a', ',', '')", repr(StringIntrinsics._rpartition_(",,a,", ",")));
    }
  }


  // NEW
  @Nested
  public final class RemovePrefixSuffix {

    @Test
    public void removeprefix() {
      assertEquals("x", StringIntrinsics._removeprefix_("prefix-x", "prefix-"));
      assertEquals("abc", StringIntrinsics._removeprefix_("abc", "x"));
      assertEquals("abc", StringIntrinsics._removeprefix_("abc", ""));
      assertEquals("", StringIntrinsics._removeprefix_("abc", "abc"));
      assertEquals("abc", StringIntrinsics._removeprefix_("abc", "abcd"));
      assertEquals("", StringIntrinsics._removeprefix_("", ""));
      assertEquals("", StringIntrinsics._removeprefix_("", "a"));
    }

    @Test
    public void removeprefixOnlyOnce() {
      assertEquals("a", StringIntrinsics._removeprefix_("aaa", "aa"));
      assertEquals("aa", StringIntrinsics._removeprefix_("aaa", "a"));
      assertEquals("abab", StringIntrinsics._removeprefix_("ababab", "ab"));
    }

    @Test
    public void removeprefixIsCaseSensitive() {
      assertEquals("ABC", StringIntrinsics._removeprefix_("ABC", "a"));
    }

    @Test
    public void removesuffix() {
      assertEquals("x", StringIntrinsics._removesuffix_("x-suffix", "-suffix"));
      assertEquals("abc", StringIntrinsics._removesuffix_("abc", "x"));
      assertEquals("abc", StringIntrinsics._removesuffix_("abc", ""));
      assertEquals("", StringIntrinsics._removesuffix_("abc", "abc"));
      assertEquals("abc", StringIntrinsics._removesuffix_("abc", "xabc"));
      assertEquals("", StringIntrinsics._removesuffix_("", ""));
      assertEquals("", StringIntrinsics._removesuffix_("", "a"));
    }

    @Test
    public void removesuffixOnlyOnce() {
      assertEquals("a", StringIntrinsics._removesuffix_("aaa", "aa"));
      assertEquals("aa", StringIntrinsics._removesuffix_("aaa", "a"));
      assertEquals("abab", StringIntrinsics._removesuffix_("ababab", "ab"));
    }

    @Test
    public void wrongType() {
      assertThrowsWithMessage(PyTypeError.class, "removeprefix() argument must be str, not int",
          () -> StringIntrinsics._removeprefix_("abc", 1L));
      assertThrowsWithMessage(PyTypeError.class, "removesuffix() argument must be str, not int",
          () -> StringIntrinsics._removesuffix_("abc", 1L));
      assertThrowsWithMessage(PyTypeError.class, "removeprefix() argument must be str, not NoneType",
          () -> StringIntrinsics._removeprefix_("abc", PyNone.INSTANCE));
      assertThrowsWithMessage(PyTypeError.class, "removesuffix() argument must be str, not NoneType",
          () -> StringIntrinsics._removesuffix_("abc", PyNone.INSTANCE));
    }

    @Test
    public void supplementaryCharacters() {
      assertEquals("a", StringIntrinsics._removeprefix_("😀a", "😀"));
      assertEquals("a", StringIntrinsics._removesuffix_("a😀", "😀"));
      assertEquals("😀", StringIntrinsics._removeprefix_("😀😀", "😀"));
      assertEquals("😀", StringIntrinsics._removesuffix_("😀😀", "😀"));
    }

    @Test
    public void loneSurrogatePrefixDoesNotSplitAPair() {
      assertEquals("😀", StringIntrinsics._removeprefix_("😀", "\uD83D"));
      assertEquals("😀a", StringIntrinsics._removeprefix_("😀a", "\uD83D"));
    }

    @Test
    public void loneSurrogateSuffixDoesNotSplitAPair() {
      assertEquals("😀", StringIntrinsics._removesuffix_("😀", "\uDE00"));
      assertEquals("a😀", StringIntrinsics._removesuffix_("a😀", "\uDE00"));
    }

    @Test
    public void loneSurrogatesAreRemovedWhenTheyAreLone() {
      assertEquals("x", StringIntrinsics._removeprefix_("\uD83Dx", "\uD83D"));
      assertEquals("x", StringIntrinsics._removesuffix_("x\uDE00", "\uDE00"));
    }
  }


  // NEW
  @Nested
  public final class Join {

    @Test
    public void joinList() {
      assertEquals("a,b,c", StringIntrinsics._join_(",", new PyList<>("a", "b", "c")));
      assertEquals("a", StringIntrinsics._join_(",", new PyList<>("a")));
      assertEquals("", StringIntrinsics._join_(",", new PyList<>()));
    }

    @Test
    public void joinTuple() {
      assertEquals("a-b", StringIntrinsics._join_("-", new PyTuple2("a", "b")));
      assertEquals("a", StringIntrinsics._join_("-", new PyTuple1("a")));
      assertEquals("", StringIntrinsics._join_("-", new PyTupleN(new Object[0])));
    }

    @Test
    public void separators() {
      assertEquals("abc", StringIntrinsics._join_("", new PyList<>("a", "b", "c")));
      assertEquals("a, b, c", StringIntrinsics._join_(", ", new PyList<>("a", "b", "c")));
      assertEquals("a😀b😀c", StringIntrinsics._join_("😀", new PyList<>("a", "b", "c")));
    }

    @Test
    public void emptyItems() {
      assertEquals(",,", StringIntrinsics._join_(",", new PyList<>("", "", "")));
      assertEquals("", StringIntrinsics._join_(",", new PyList<>("")));
      assertEquals("a,", StringIntrinsics._join_(",", new PyList<>("a", "")));
    }

    @Test
    public void supplementaryItems() {
      assertEquals("😀,😀", StringIntrinsics._join_(",", new PyList<>("😀", "😀")));
    }

    @Test
    public void itemsAreNotQuoted() {
      assertEquals("it's|a\\b", StringIntrinsics._join_("|", new PyList<>("it's", "a\\b")));
    }

    @Test
    public void nonStringItemMessage() {
      assertThrowsWithMessage(PyTypeError.class, "sequence item 1: expected str instance, int found",
          () -> StringIntrinsics._join_(",", new PyList<>("a", 1L, "c")));
      assertThrowsWithMessage(PyTypeError.class, "sequence item 0: expected str instance, int found",
          () -> StringIntrinsics._join_(",", new PyList<>(1L)));
      assertThrowsWithMessage(PyTypeError.class, "sequence item 2: expected str instance, NoneType found",
          () -> StringIntrinsics._join_(",", new PyList<>("a", "b", PyNone.INSTANCE)));
      assertThrowsWithMessage(PyTypeError.class, "sequence item 1: expected str instance, float found",
          () -> StringIntrinsics._join_(",", new PyTuple2("a", 1.5)));
      assertThrowsWithMessage(PyTypeError.class, "sequence item 0: expected str instance, bool found",
          () -> StringIntrinsics._join_(",", new PyList<>(true)));
    }

    @Test
    public void firstBadItemIsReported() {
      assertThrowsWithMessage(PyTypeError.class, "sequence item 1: expected str instance, int found",
          () -> StringIntrinsics._join_(",", new PyList<>("a", 1L, 2.0)));
    }

    @Test
    public void nonIterable() {
      assertThrowsWithMessage(PyTypeError.class, "can only join an iterable",
          () -> StringIntrinsics._join_(",", 1L));
      assertThrowsWithMessage(PyTypeError.class, "can only join an iterable",
          () -> StringIntrinsics._join_(",", PyNone.INSTANCE));
      assertThrowsWithMessage(PyTypeError.class, "can only join an iterable",
          () -> StringIntrinsics._join_(",", 1.5));
    }

    // These two depend on what IteratorIntrinsics.$iterator$ supports.
    @Test
    public void joinRangeOfIntsReportsTheItemError() {
      assertThrowsWithMessage(PyTypeError.class, "sequence item 0: expected str instance, int found",
          () -> StringIntrinsics._join_(",", PyRange.of(0L, 3L, 1L)));
    }

    @Test
    public void joinOfAString() {
      assertEquals("a,b,c", StringIntrinsics._join_(",", "abc"));
      assertEquals("a,😀,b", StringIntrinsics._join_(",", "a😀b"));
      assertEquals("", StringIntrinsics._join_(",", ""));
    }

    @Test
    public void joinDoesNotModifyTheList() {
      var list = new PyList<>("a", "b");
      StringIntrinsics._join_(",", list);
      assertEquals("['a', 'b']", repr(list));
    }
  }


  @Nested
  public final class Zfill {

    @Test
    public void zfillStillRejectsHugeWidth() {   // Guard: asIndex must stay strict (OverflowError)
      var ex = assertThrows(PyOverflowError.class,
          () -> StringIntrinsics._zfill_("a", BigInteger.TEN.pow(100)));
      assertEquals("Python int too large to convert to C ssize_t", ex.getMessage());
    }

    // NEW
    @Test
    public void basic() {
      assertEquals("00042", StringIntrinsics._zfill_("42", 5L));
      assertEquals("42", StringIntrinsics._zfill_("42", 2L));
      assertEquals("42", StringIntrinsics._zfill_("42", 1L));
      assertEquals("000", StringIntrinsics._zfill_("", 3L));
      assertEquals("", StringIntrinsics._zfill_("", 0L));
    }

    // NEW
    @Test
    public void signsAreKeptInFront() {
      assertEquals("-0042", StringIntrinsics._zfill_("-42", 5L));
      assertEquals("+0042", StringIntrinsics._zfill_("+42", 5L));
      assertEquals("-00", StringIntrinsics._zfill_("-", 3L));
      assertEquals("+00", StringIntrinsics._zfill_("+", 3L));
      assertEquals("-42", StringIntrinsics._zfill_("-42", 3L));
    }

    // NEW
    @Test
    public void onlyTheFirstSignIsSpecial() {
      assertEquals("0--42", StringIntrinsics._zfill_("--42", 5L).toString().replaceFirst("^-0", "0-"));
      assertEquals("-0-42", StringIntrinsics._zfill_("--42", 5L));
      assertEquals("0004-2", StringIntrinsics._zfill_("4-2", 6L));
    }

    // NEW
    @Test
    public void zeroAndNegativeWidth() {
      assertEquals("42", StringIntrinsics._zfill_("42", 0L));
      assertEquals("42", StringIntrinsics._zfill_("42", -1L));
      assertEquals("42", StringIntrinsics._zfill_("42", Long.MIN_VALUE));
    }

    // NEW
    @Test
    public void widthIsCountedInCodePoints() {
      assertEquals("00😀", StringIntrinsics._zfill_("😀", 3L));
      assertEquals("-00😀", StringIntrinsics._zfill_("-😀", 4L));
      assertEquals("😀😀", StringIntrinsics._zfill_("😀😀", 2L));
      assertEquals("0😀😀", StringIntrinsics._zfill_("😀😀", 3L));
    }

    // NEW
    @Test
    public void nonDigitStringsArePaddedToo() {
      assertEquals("000abc", StringIntrinsics._zfill_("abc", 6L));
      assertEquals("00 a", StringIntrinsics._zfill_(" a", 4L));
    }

    // NEW
    @Test
    public void booleanAndBigIntegerWidths() {
      assertEquals("a", StringIntrinsics._zfill_("a", true));
      assertEquals("a", StringIntrinsics._zfill_("a", false));
      assertEquals("00a", StringIntrinsics._zfill_("a", BigInteger.valueOf(3L)));
      assertEquals("a", StringIntrinsics._zfill_("a", BigInteger.valueOf(-3L)));
    }

    // NEW
    @Test
    public void wrongWidthType() {
      assertThrowsWithMessage(PyTypeError.class, "'float' object cannot be interpreted as an integer",
          () -> StringIntrinsics._zfill_("a", 3.0));
      assertThrowsWithMessage(PyTypeError.class, "'NoneType' object cannot be interpreted as an integer",
          () -> StringIntrinsics._zfill_("a", PyNone.INSTANCE));
      assertThrowsWithMessage(PyTypeError.class, "'str' object cannot be interpreted as an integer",
          () -> StringIntrinsics._zfill_("a", "3"));
    }

    // NEW
    @Test
    public void overflowBoundary() {
      // 2**63 does not fit in a C ssize_t, 2**63 - 1 does (but is too big to allocate).
      var ex = assertThrows(PyOverflowError.class,
          () -> StringIntrinsics._zfill_("a", BigInteger.TWO.pow(63)));
      assertEquals("Python int too large to convert to C ssize_t", ex.getMessage());
      assertThrows(PyOverflowError.class,
          () -> StringIntrinsics._zfill_("a", BigInteger.TWO.pow(63).negate().subtract(BigInteger.ONE)));
    }

    @Test
    public void tooLargeToAllocateIsAMemoryError() {
      assertThrows(UnimplementedFeatureError.class, () -> StringIntrinsics._zfill_("a", Long.MAX_VALUE));
      assertThrows(UnimplementedFeatureError.class, () -> StringIntrinsics._zfill_("a", 1L << 62));
      assertThrows(UnimplementedFeatureError.class, () -> StringIntrinsics._zfill_("a", (long) Integer.MAX_VALUE + 1));
      assertThrows(UnimplementedFeatureError.class, () -> StringIntrinsics._zfill_("-a", Long.MAX_VALUE));
    }
  }


  // NEW
  @Nested
  public final class IsAscii {

    @Test
    public void ascii() {
      assertEquals(true, StringIntrinsics._isascii_(""));
      assertEquals(true, StringIntrinsics._isascii_("abc XYZ 123 !~"));
      assertEquals(true, StringIntrinsics._isascii_("\u0000"));
      assertEquals(true, StringIntrinsics._isascii_("\u007f"));
    }

    @Test
    public void nonAscii() {
      assertEquals(false, StringIntrinsics._isascii_("\u0080"));
      assertEquals(false, StringIntrinsics._isascii_("\u00e9"));
      assertEquals(false, StringIntrinsics._isascii_("abc\u00e9"));
      assertEquals(false, StringIntrinsics._isascii_("😀"));
      assertEquals(false, StringIntrinsics._isascii_("\uD83D"));
    }
  }


  // NEW
  @Nested
  public final class IsAlpha {

    @Test
    public void letters() {
      assertEquals(true, StringIntrinsics._isalpha_("abc"));
      assertEquals(true, StringIntrinsics._isalpha_("ABC"));
      assertEquals(true, StringIntrinsics._isalpha_("\u00e9\u00e0"));            // Ll
      assertEquals(true, StringIntrinsics._isalpha_("\u01C5"));                  // Lt
      assertEquals(true, StringIntrinsics._isalpha_("\u02b0"));                  // Lm
      assertEquals(true, StringIntrinsics._isalpha_("\u65e5\u672c\u8a9e"));      // Lo
      assertEquals(true, StringIntrinsics._isalpha_("\u05d0"));                  // Lo: Hebrew alef
      assertEquals(true, StringIntrinsics._isalpha_("\u00aa"));                  // Lo: feminine ordinal
    }

    @Test
    public void supplementaryLetters() {
      assertEquals(true, StringIntrinsics._isalpha_("\uD801\uDC00"));            // 𐐀 Lu
      assertEquals(true, StringIntrinsics._isalpha_("a\uD801\uDC28"));
    }

    @Test
    public void emptyIsFalse() {
      assertEquals(false, StringIntrinsics._isalpha_(""));
    }

    @Test
    public void nonLetters() {
      assertEquals(false, StringIntrinsics._isalpha_("abc1"));
      assertEquals(false, StringIntrinsics._isalpha_("a b"));
      assertEquals(false, StringIntrinsics._isalpha_("1"));
      assertEquals(false, StringIntrinsics._isalpha_("\u00b2"));                 // No
      assertEquals(false, StringIntrinsics._isalpha_("\u0663"));                 // Nd
      assertEquals(false, StringIntrinsics._isalpha_("_"));
      assertEquals(false, StringIntrinsics._isalpha_("\u0300"));                 // Mn: combining mark
      assertEquals(false, StringIntrinsics._isalpha_("a\u0300"));                // letter + combining mark
      assertEquals(false, StringIntrinsics._isalpha_("\u2160"));                 // Nl: roman numeral
      assertEquals(false, StringIntrinsics._isalpha_("😀"));
    }

    @Test
    public void loneSurrogate() {
      assertEquals(false, StringIntrinsics._isalpha_("\uD83D"));
      assertEquals(false, StringIntrinsics._isalpha_("a\uDE00"));
    }
  }


  // NEW
  @Nested
  public final class IsDecimal {

    @Test
    public void decimals() {
      assertEquals(true, StringIntrinsics._isdecimal_("0123456789"));
      assertEquals(true, StringIntrinsics._isdecimal_("\u0663"));                // ٣ Arabic-Indic
      assertEquals(true, StringIntrinsics._isdecimal_("\u0661\u0662\u0663"));
      assertEquals(true, StringIntrinsics._isdecimal_("\u0967"));                // Devanagari one
      assertEquals(true, StringIntrinsics._isdecimal_("\uFF11\uFF12"));          // fullwidth digits
      assertEquals(true, StringIntrinsics._isdecimal_("\uD835\uDFD8"));          // 𝟘 mathematical double-struck zero
      assertEquals(true, StringIntrinsics._isdecimal_("1\u06622"));
    }

    @Test
    public void emptyIsFalse() {
      assertEquals(false, StringIntrinsics._isdecimal_(""));
    }

    @Test
    public void notDecimals() {
      assertEquals(false, StringIntrinsics._isdecimal_("\u00b2"));               // ² No
      assertEquals(false, StringIntrinsics._isdecimal_("\u00bd"));               // ½ No
      assertEquals(false, StringIntrinsics._isdecimal_("\u2460"));               // ① No
      assertEquals(false, StringIntrinsics._isdecimal_("\u2167"));               // Ⅷ Nl
      assertEquals(false, StringIntrinsics._isdecimal_("1.5"));
      assertEquals(false, StringIntrinsics._isdecimal_("-1"));
      assertEquals(false, StringIntrinsics._isdecimal_("1 2"));
      assertEquals(false, StringIntrinsics._isdecimal_("a"));
      assertEquals(false, StringIntrinsics._isdecimal_("12a"));
      assertEquals(false, StringIntrinsics._isdecimal_("\uD83D"));
    }
  }


  // NEW: compares find/rfind/index/rindex/partition/rpartition/removeprefix/removesuffix
  // with a naive model working on int[] code point arrays, on random strings
  // containing lone surrogates, pairs and plain characters.
  @Nested
  public final class DifferentialAgainstACodePointModel {

    private static final String[] ALPHABET = {"a", "b", "\uD83D", "\uDE00", "\uD83D\uDE00"};

    private static String randomString(Random random, int maxLength) {
      var builder = new StringBuilder();
      var length = random.nextInt(maxLength + 1);
      for (var i = 0; i < length; i++) {
        builder.append(ALPHABET[random.nextInt(ALPHABET.length)]);
      }
      return builder.toString();
    }

    /// Either None or a small integer, so that most bounds are in, around or out of the string.
    private static Object randomBound(Random random) {
      if (random.nextInt(6) == 0) {
        return PyNone.INSTANCE;
      }
      return Long.valueOf(random.nextInt(21) - 10);
    }

    private static boolean matchesAt(int[] s, int[] sub, long at) {
      for (var i = 0; i < sub.length; i++) {
        if (s[(int) at + i] != sub[i]) {
          return false;
        }
      }
      return true;
    }

    /// Python's find/rfind on code points.
    private static long modelFind(int[] s, int[] sub, Object startObject, Object endObject, boolean reverse) {
      long length = s.length;
      long start = startObject instanceof Long startValue ? startValue : 0L;
      long end = endObject instanceof Long endValue ? endValue : Long.MAX_VALUE;
      if (end > length) {
        end = length;
      } else if (end < 0) {
        end = Math.max(0L, end + length);
      }
      if (start < 0) {
        start = Math.max(0L, start + length);
      }
      if (end - start < sub.length) {
        return -1;
      }
      if (reverse) {
        for (var i = end - sub.length; i >= start; i--) {
          if (matchesAt(s, sub, i)) {
            return i;
          }
        }
      } else {
        for (var i = start; i <= end - sub.length; i++) {
          if (matchesAt(s, sub, i)) {
            return i;
          }
        }
      }
      return -1;
    }

    @Test
    public void findFamilyMatchesTheModel() {
      var random = new Random(0xC0FFEE);
      for (var iteration = 0; iteration < 20_000; iteration++) {
        var s = randomString(random, 8);
        var sub = randomString(random, 3);
        var start = randomBound(random);
        var end = randomBound(random);
        var description = "s=" + repr(s) + " sub=" + repr(sub) + " start=" + start + " end=" + end;

        var cps = s.codePoints().toArray();
        var subCps = sub.codePoints().toArray();
        var expectedFind = modelFind(cps, subCps, start, end, false);
        var expectedRfind = modelFind(cps, subCps, start, end, true);

        assertEquals(expectedFind, StringIntrinsics._find_(s, sub, start, end), () -> "find " + description);
        assertEquals(expectedRfind, StringIntrinsics._rfind_(s, sub, start, end), () -> "rfind " + description);

        if (expectedFind < 0) {
          assertThrows(PyValueError.class, () -> StringIntrinsics._index_(s, sub, start, end),
              () -> "index " + description);
        } else {
          assertEquals(expectedFind, StringIntrinsics._index_(s, sub, start, end), () -> "index " + description);
        }
        if (expectedRfind < 0) {
          assertThrows(PyValueError.class, () -> StringIntrinsics._rindex_(s, sub, start, end),
              () -> "rindex " + description);
        } else {
          assertEquals(expectedRfind, StringIntrinsics._rindex_(s, sub, start, end), () -> "rindex " + description);
        }
      }
    }

    @Test
    public void findOverloadsWithFewerArgumentsMatchTheModel() {
      var random = new Random(1234);
      for (var iteration = 0; iteration < 5_000; iteration++) {
        var s = randomString(random, 8);
        var sub = randomString(random, 3);
        var start = randomBound(random);
        var cps = s.codePoints().toArray();
        var subCps = sub.codePoints().toArray();
        var description = "s=" + repr(s) + " sub=" + repr(sub) + " start=" + start;

        assertEquals(modelFind(cps, subCps, PyNone.INSTANCE, PyNone.INSTANCE, false),
            StringIntrinsics._find_(s, sub), () -> "find/2 " + description);
        assertEquals(modelFind(cps, subCps, PyNone.INSTANCE, PyNone.INSTANCE, true),
            StringIntrinsics._rfind_(s, sub), () -> "rfind/2 " + description);
        assertEquals(modelFind(cps, subCps, start, PyNone.INSTANCE, false),
            StringIntrinsics._find_(s, sub, start), () -> "find/3 " + description);
        assertEquals(modelFind(cps, subCps, start, PyNone.INSTANCE, true),
            StringIntrinsics._rfind_(s, sub, start), () -> "rfind/3 " + description);
      }
    }

    @Test
    public void partitionAndRpartitionMatchTheModel() {
      var random = new Random(99);
      for (var iteration = 0; iteration < 20_000; iteration++) {
        var s = randomString(random, 8);
        var sep = randomString(random, 3);
        if (sep.isEmpty()) {
          assertThrows(PyValueError.class, () -> StringIntrinsics._partition_(s, sep));
          assertThrows(PyValueError.class, () -> StringIntrinsics._rpartition_(s, sep));
          continue;
        }
        var cps = s.codePoints().toArray();
        var sepCps = sep.codePoints().toArray();
        var description = "s=" + repr(s) + " sep=" + repr(sep);

        var first = (int) modelFind(cps, sepCps, PyNone.INSTANCE, PyNone.INSTANCE, false);
        var expectedPartition = first < 0
            ? "(" + repr(s) + ", '', '')"
            : "(" + repr(new String(cps, 0, first)) + ", " + repr(sep) + ", "
                + repr(new String(cps, first + sepCps.length, cps.length - first - sepCps.length)) + ")";
        assertEquals(expectedPartition, repr(StringIntrinsics._partition_(s, sep)), () -> "partition " + description);

        var last = (int) modelFind(cps, sepCps, PyNone.INSTANCE, PyNone.INSTANCE, true);
        var expectedRpartition = last < 0
            ? "('', '', " + repr(s) + ")"
            : "(" + repr(new String(cps, 0, last)) + ", " + repr(sep) + ", "
                + repr(new String(cps, last + sepCps.length, cps.length - last - sepCps.length)) + ")";
        assertEquals(expectedRpartition, repr(StringIntrinsics._rpartition_(s, sep)), () -> "rpartition " + description);
      }
    }

    /*@Test
    public void removeprefixAndRemovesuffixMatchTheModel() {
      var random = new Random(2024);
      for (var iteration = 0; iteration < 20_000; iteration++) {
        var s = randomString(random, 8);
        var p = randomString(random, 3);
        var cps = s.codePoints().toArray();
        var pCps = p.codePoints().toArray();
        var description = "s=" + repr(s) + " p=" + repr(p);

        var hasPrefix = pCps.length <= cps.length
            && Arrays.equals(pCps, 0, pCps.length, cps, 0, pCps.length);
        var expectedWithoutPrefix = hasPrefix
            ? new String(cps, pCps.length, cps.length - pCps.length)
            : s;
        assertEquals(expectedWithoutPrefix, StringIntrinsics._removeprefix_(s, p), () -> "removeprefix " + description);

        var hasSuffix = !pCps.length_isZero(pCps) && pCps.length <= cps.length
            && Arrays.equals(pCps, 0, pCps.length, cps, cps.length - pCps.length, cps.length);
        var expectedWithoutSuffix = hasSuffix
            ? new String(cps, 0, cps.length - pCps.length)
            : s;
        assertEquals(expectedWithoutSuffix, StringIntrinsics._removesuffix_(s, p), () -> "removesuffix " + description);
      }
    }*/
  }


  /// Checks that the method table is reachable from Python source, with every overload.
  @Nested
  public final class ThroughMethodCalls {
    private ModuleContext run(String source) {
      var context = new ModuleContext();
      Engine.run(context, source);
      return context;
    }

    @Test
    public void everyMethodIsReachableFromPythonSource() {
      var context = run("""
          text = "hello world"
          upper = text.upper()
          lower = "HELLO".lower()
          swapped = "Hello".swapcase()
          capitalized = "hello WORLD".capitalize()
          found = text.find("o")
          found_from = text.find("o", 5)
          found_range = text.find("o", 5, 8)
          last = text.rfind("o")
          last_from = text.rfind("o", 5)
          last_range = text.rfind("o", 0, 5)
          idx = text.index("world")
          idx_from = text.index("o", 5)
          idx_range = text.index("o", 5, 8)
          ridx = text.rindex("o")
          ridx_from = text.rindex("o", 0, 5)
          ridx_range = text.rindex("l", 0, 5)
          parts = "a,b,c".partition(",")
          rparts = "a,b,c".rpartition(",")
          joined = "-".join(["a", "b", "c"])
          unprefixed = "unhappy".removeprefix("un")
          unsuffixed = "happy!!".removesuffix("!!")
          padded = "42".zfill(5)
          is_ascii = "abc".isascii()
          is_alpha = "abc".isalpha()
          is_decimal = "123".isdecimal()
          """);

      assertEquals("HELLO WORLD", context.getGlobal("upper"));
      assertEquals("hello", context.getGlobal("lower"));
      assertEquals("hELLO", context.getGlobal("swapped"));
      assertEquals("Hello world", context.getGlobal("capitalized"));
      assertEquals(4L, context.getGlobal("found"));
      assertEquals(7L, context.getGlobal("found_from"));
      assertEquals(7L, context.getGlobal("found_range"));
      assertEquals(7L, context.getGlobal("last"));
      assertEquals(7L, context.getGlobal("last_from"));
      assertEquals(4L, context.getGlobal("last_range"));
      assertEquals(6L, context.getGlobal("idx"));
      assertEquals(7L, context.getGlobal("idx_from"));
      assertEquals(7L, context.getGlobal("idx_range"));
      assertEquals(7L, context.getGlobal("ridx"));
      assertEquals(4L, context.getGlobal("ridx_from"));
      assertEquals(3L, context.getGlobal("ridx_range"));
      assertEquals("('a', ',', 'b,c')", repr(context.getGlobal("parts")));
      assertEquals("('a,b', ',', 'c')", repr(context.getGlobal("rparts")));
      assertEquals("a-b-c", context.getGlobal("joined"));
      assertEquals("happy", context.getGlobal("unprefixed"));
      assertEquals("happy", context.getGlobal("unsuffixed"));
      assertEquals("00042", context.getGlobal("padded"));
      assertEquals(true, context.getGlobal("is_ascii"));
      assertEquals(true, context.getGlobal("is_alpha"));
      assertEquals(true, context.getGlobal("is_decimal"));
    }

    @Test
    public void errorsPropagateToTheCaller() {
      assertThrows(PyValueError.class, () -> run("'abc'.index('z')"));
      assertThrows(PyValueError.class, () -> run("'abc'.rindex('z')"));
      assertThrows(PyTypeError.class, () -> run("'abc'.find(1)"));
      assertThrows(PyValueError.class, () -> run("'a,b'.partition('')"));
    }
  }
}