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
import com.github.forax.orvet.rt.error.PyOverflowError;
import com.github.forax.orvet.rt.error.PyTypeError;
import com.github.forax.orvet.rt.error.PyValueError;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public final class StringIntrinsicsTest {

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

    @Test
    public void reprRange() {
      assertEquals("range(0, 10)", StringIntrinsics.$repr$(PyRange.of(0L, 10L, 1L)));
      assertEquals("range(0, 10, 3)", StringIntrinsics.$repr$(PyRange.of(0L, 10L, 3L)));
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

    @Test
    public void repeatedReferenceIsNotAMCycle() {
      var child = new PyList<>(1L);
      var list = new PyList<>(child, child);

      assertEquals("[[1], [1]]", StringIntrinsics.$repr$(list));
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
  }


  @Nested
  public final class Float {

    @Test
    public void floatSpecialValues() {
      assertEquals("nan", StringIntrinsics.$str$(Double.NaN));
      assertEquals("inf", StringIntrinsics.$str$(Double.POSITIVE_INFINITY));
      assertEquals("-inf", StringIntrinsics.$str$(Double.NEGATIVE_INFINITY));
      assertEquals("nan", StringIntrinsics.$repr$(Double.NaN));
      assertEquals("inf", StringIntrinsics.$repr$(Double.POSITIVE_INFINITY));
      assertEquals("-inf", StringIntrinsics.$repr$(Double.NEGATIVE_INFINITY));
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

    @Test
    public void floatScientificExponentFormat() {
      assertEquals("1e+16", StringIntrinsics.$str$(1e16));
      assertEquals("1e-16", StringIntrinsics.$str$(1e-16));
      assertEquals("1e+100", StringIntrinsics.$str$(1e100));
      assertEquals("1e-100", StringIntrinsics.$str$(1e-100));
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
        assertEquals(0.0, Double.compare(v, Double.parseDouble(s)),
            "round-trip failed for " + v + " (\"str\" gave \"" + s + "\")");
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

    @Test
    public void quoteSelection() {
      assertEquals("\"it's\"", StringIntrinsics.$repr$("it's"));
      assertEquals("'say \"hi\"'", StringIntrinsics.$repr$("say \"hi\""));
      assertEquals("'a\\'b\"c'", StringIntrinsics.$repr$("a'b\"c"));
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

    @Test
    public void loneHighSurrogate() {
      assertEquals("'\\ud800'", StringIntrinsics.$repr$("\uD800"));
    }

    @Test
    public void printableUnicodeIsNotEscaped() {
      assertEquals("'\u0100'", StringIntrinsics.$repr$("\u0100"));
      assertEquals("'\u0300'", StringIntrinsics.$repr$("\u0300"));
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
    public void finalSigmaUsesOriginalContext() {    // FAILS TODAY (produces σ, not ς)
      assertEquals("\u03BF\u03B4\u03BF\u03C2",
          StringIntrinsics._swapcase_("\u039F\u0394\u039F\u03A3"));  // ΟΔΟΣ -> οδος (final ς)
      assertEquals("\u03C3\u03C2",
          StringIntrinsics._swapcase_("\u03A3\u03A3"));              // ΣΣ -> σς
      assertEquals("\u03B1'\u03C2",
          StringIntrinsics._swapcase_("\u0391'\u03A3"));             // Α'Σ -> α'ς (case-ignorable ')
    }

    @Test
    public void sigmaIsNotFinalWhenFollowedByCased() { // FAILS TODAY for the Α'ΣΒ case
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
  }

  @Nested
  public final class Capitalize {

    @Test
    public void fullTitleMappingExpansions() {        // FAILS TODAY (first char left unchanged)
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

    @Test
    public void simpleTitleMappingStillUsed() {
      assertEquals("\u01F2abc", StringIntrinsics._capitalize_("\u01F3abc")); // ǳabc -> ǲabc
      assertEquals("\u01C5", StringIntrinsics._capitalize_("\u01C6"));       // ǆ -> ǅ
    }

    @Test
    public void restLowercaseKeepsOriginalSigmaContext() { // FAILS TODAY (rest lowered in isolation)
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
  }

  @Nested
  public final class Find {

    @Test
    public void emptySubWithStartBeyondLength() {     // FAILS TODAY (returns 3 / 0 instead of -1)
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

    @Test
    public void hugeIntegerBoundsAreClampedNotOverflow() { // FAILS TODAY (throws PyOverflowError)
      var huge = BigInteger.TEN.pow(100);
      assertEquals(-1L, StringIntrinsics._find_("abc", "a", huge));
      assertEquals(0L, StringIntrinsics._find_("abc", "a", huge.negate()));
      assertEquals(0L, StringIntrinsics._rfind_("abc", "a", 0L, huge));
    }

    @Test
    public void longExtremeBounds() {                 // FAILS TODAY for the empty-sub cases
      assertEquals(-1L, StringIntrinsics._find_("abc", "", Long.MAX_VALUE));
      assertEquals(0L, StringIntrinsics._find_("abc", "", Long.MIN_VALUE));
      assertEquals(-1L, StringIntrinsics._find_("abc", "a", Long.MAX_VALUE));
    }

    @Test
    public void noneBoundsUseDefaults() {             // FAILS TODAY (throws PyTypeError)
      assertEquals(0L, StringIntrinsics._find_("abc", "a", PyNone.INSTANCE));
      assertEquals(2L, StringIntrinsics._find_("abc", "c", PyNone.INSTANCE, PyNone.INSTANCE));
      assertEquals(3L, StringIntrinsics._rfind_("abc", "", PyNone.INSTANCE, PyNone.INSTANCE));
    }

    @Test
    public void booleanBounds() {
      assertEquals(2L, StringIntrinsics._find_("aba", "a", true)); // True == 1
    }

    @Test
    public void indexRaisesValueErrorOnHugeBound() {  // FAILS TODAY (throws PyOverflowError)
      var ex = assertThrows(PyValueError.class,
          () -> StringIntrinsics._index_("abc", "a", BigInteger.TEN.pow(100)));
      assertEquals("substring not found", ex.getMessage());
    }

    @Test
    public void wrongTypeBoundMessage() {             // FAILS TODAY (different message)
      var ex = assertThrows(PyTypeError.class,
          () -> StringIntrinsics._find_("abc", "a", 1.0));
      assertEquals("slice indices must be integers or None or have an __index__ method",
          ex.getMessage());
    }

    @Test
    public void normalSearchStillWorks() {
      assertEquals(2L, StringIntrinsics._find_("hello", "l"));
      assertEquals(3L, StringIntrinsics._rfind_("hello", "l"));
      assertEquals(2L, StringIntrinsics._find_("a\uD835\uDD52b", "b")); // a𝕒b -> code-point index 2
      assertEquals(0L, StringIntrinsics._find_("abc", "a", -10L));
    }

    @Test
    public void rfindWithStartEnd() {
      assertEquals(1L, StringIntrinsics._rfind_("abcabc", "b", 0L, 4L));
      assertEquals(4L, StringIntrinsics._rfind_("abcabc", "b", 2L));
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

  @Nested
  public final class Zfill {

    @Test
    public void zfillStillRejectsHugeWidth() {   // Guard: asIndex must stay strict (OverflowError)
      var ex = assertThrows(PyOverflowError.class,
          () -> StringIntrinsics._zfill_("a", BigInteger.TEN.pow(100)));
      assertEquals("Python int too large to convert to C ssize_t", ex.getMessage());
    }
  }


  @Nested
  public final class ThroughMethodCalls {
    private ModuleContext run(String source) {
      var context = new ModuleContext();
      Engine.run(context, source);
      return context;
    }

    @Test
    @Disabled
    public void commonStringMethodsAreReachableFromPythonSource() {
      var context = run("""
          source = "  Hello, World  "
          clean = source.strip()
          left = source.lstrip()
          right = source.rstrip()
          custom = "--hello--".strip("-")
          words = "  one   two three  ".split()
          limited = "one,two,three".split(",", 1)
          replaced = "banana".replace("a", "o", 2)
          starts = "banana".startswith("ban")
          ends = "banana".endswith("ana")
          count = "banana".count("ana")
          title = "hello WORLD".title()
          folded = "Straße".casefold()
          digits = "²".isdigit()
          alnum = "abc123".isalnum()
          spaces = " \t".isspace()
          """);

      assertEquals("Hello, World", context.getGlobal("clean"));
      assertEquals("Hello, World  ", context.getGlobal("left"));
      assertEquals("  Hello, World", context.getGlobal("right"));
      assertEquals("hello", context.getGlobal("custom"));
      var words = (PyList<?>) context.getGlobal("words");
      assertEquals(3, words.size());
      assertEquals("one", words.get(0));
      assertEquals("two", words.get(1));
      assertEquals("three", words.get(2));
      var limited = (PyList<?>) context.getGlobal("limited");
      assertEquals(2, limited.size());
      assertEquals("one", limited.get(0));
      assertEquals("two,three", limited.get(1));
      assertEquals("bonona", context.getGlobal("replaced"));
      assertEquals(true, context.getGlobal("starts"));
      assertEquals(true, context.getGlobal("ends"));
      assertEquals(1L, context.getGlobal("count"));
      assertEquals("Hello World", context.getGlobal("title"));
      assertEquals("strasse", context.getGlobal("folded"));
      assertEquals(true, context.getGlobal("digits"));
      assertEquals(true, context.getGlobal("alnum"));
      assertEquals(true, context.getGlobal("spaces"));
    }
  }
}