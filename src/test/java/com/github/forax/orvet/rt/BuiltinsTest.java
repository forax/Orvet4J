package com.github.forax.orvet.rt;

import com.github.forax.orvet.engine.Engine;
import com.github.forax.orvet.rt.error.PyOverflowError;
import com.github.forax.orvet.rt.error.PyTypeError;
import com.github.forax.orvet.rt.error.PyValueError;
import com.github.forax.orvet.rt.error.PyZeroDivisionError;
import com.github.forax.orvet.rt.error.UnimplementedFeatureError;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

public final class BuiltinsTest {

  private static void run(String source) {
    var context = new ModuleContext();
    Engine.run(context, source);
  }

  private static String runCaptureOutput(String source) throws IOException {
    try (var out = new ByteArrayOutputStream();
         var printStream = new PrintStream(out)) {
      var originalOut = System.out;
      System.setOut(printStream);
      try {
        run(source);
      } finally {
        System.setOut(originalOut);
      }
      return out.toString();
    }
  }

  private static String stripTrailingNewline(String s) {
    return s.endsWith("\n") ? s.substring(0, s.length() - 1) : s;
  }

  /** Evaluate {@code expression} and return its repr (without the trailing newline). */
  private static String reprOf(String expression) throws IOException {
    return stripTrailingNewline(
        runCaptureOutput("print(repr(" + expression + "))"));
  }

  /** Evaluate {@code expression} and return its str (as printed by {@code print}). */
  private static String strOf(String expression) throws IOException {
    return stripTrailingNewline(
        runCaptureOutput("print(" + expression + ")"));
  }

  private static void assertRaises(Class<? extends Throwable> expected,
                                   String source) {
    assertThrows(expected, () -> run(source));
  }


  @Nested
  public final class AsValues {
    private static final String FIVE_PYTHON_CALLEES = """
      def apply(fn, v):
          return fn(v)
      def a(x): return x
      def b(x): return x
      def c(x): return x
      def d(x): return x
      def e(x): return x
      apply(a, 1)
      apply(b, 1)
      apply(c, 1)
      apply(d, 1)
      apply(e, 1)
      """;

    @Test
    public void printAtAMegamorphicSite() throws IOException {
      assertEquals("hello\n", runCaptureOutput(FIVE_PYTHON_CALLEES + "apply(print, 'hello')\n"));
    }

    @Test
    public void fixedAndVariadicNativesAtAMegamorphicSite() throws IOException {
      assertEquals("3\n1 2\n", runCaptureOutput(FIVE_PYTHON_CALLEES + """
        print(apply(abs, -3))
        def apply2(fn, a, b):
            return fn(a, b)
        apply2(print, 1, 2)
        """));
    }

    @Test
    public void printAtAMonomorphicAndPolymorphicSite() throws IOException {
      assertEquals("1\n2 3\n", runCaptureOutput("""
        def apply(fn, v):
            return fn(v)
        apply(print, 1)
        def apply2(fn, a, b):
            return fn(a, b)
        apply2(print, 2, 3)
        """));
    }
  }


  @Nested
  public final class Abs {
    @Test
    public void booleansAndIntegers() throws IOException {
      assertEquals("1", strOf("abs(True)"));
      assertEquals("0", strOf("abs(False)"));
      assertEquals("5", strOf("abs(-5)"));
      assertEquals("5", strOf("abs(5)"));
      assertEquals("10", strOf("abs(-10)"));
    }

    @Test
    public void longMinValuePromotesToBigInteger() throws IOException {
      // abs(Long.MIN_VALUE) overflows if the result is kept as a long.
      assertEquals("9223372036854775808", reprOf("abs(-(2**63))"));
      assertEquals("100000000000000000000", reprOf("abs(-(10**20))"));
    }

    @Test
    public void floats() throws IOException {
      assertEquals("0.0", strOf("abs(-0.0)"));
      assertEquals("3.14", strOf("abs(-3.14)"));
    }

    @Test
    public void rejectsNonNumbers() {
      assertRaises(PyTypeError.class, "abs('x')");
      assertRaises(PyTypeError.class, "abs(None)");
    }
  }

  @Nested
  public final class AllAny {
    @Test
    public void emptyIterables() throws IOException {
      assertEquals("True", strOf("all([])"));
      assertEquals("True", strOf("all(())"));
      assertEquals("False", strOf("any([])"));
      assertEquals("False", strOf("any(())"));
    }

    @Test
    public void truthinessOfElements() throws IOException {
      assertEquals("True", strOf("all([1, 2, 3])"));
      assertEquals("False", strOf("all([1, 0, 3])"));
      assertEquals("True", strOf("any([0, '', 3])"));
      assertEquals("False", strOf("any([0, '', None])"));
    }

    @Test
    public void supportsStringsAndDictionaries() throws IOException {
      assertEquals("True", strOf("all('abc')"));
      assertEquals("False", strOf("any('')"));
      assertEquals("True", strOf("all({'a': 1, 'b': 2})"));
    }

    @Test
    public void rejectsNonIterables() {
      assertRaises(PyTypeError.class, "all(42)");
      assertRaises(PyTypeError.class, "any(None)");
    }
  }

  @Nested
  public final class Ascii {
    @Test
    public void escapesUnicodeAtDifferentWidths() throws IOException {
      assertEquals("'\\xe9'", strOf("ascii('é')"));
      assertEquals("'\\u03bb'", strOf("ascii('λ')"));
      assertEquals("'\\U0001f600'", strOf("ascii('😀')"));
    }

    @Test
    public void handlesNestedValues() throws IOException {
      assertEquals("[1, '\\xe9']", strOf("ascii([1, 'é'])"));
    }
  }


  @Nested
  public final class Bool {
    @Test
    public void falsyValues() throws IOException {
      assertEquals("False", strOf("bool()"));
      assertEquals("False", strOf("bool(False)"));
      assertEquals("False", strOf("bool(0)"));
      assertEquals("False", strOf("bool(0.0)"));
      assertEquals("False", strOf("bool(-0.0)"));
      assertEquals("False", strOf("bool('')"));
      assertEquals("False", strOf("bool(None)"));
      assertEquals("False", strOf("bool([])"));
      assertEquals("False", strOf("bool({})"));
      assertEquals("False", strOf("bool(())"));
    }

    @Test
    public void truthyValues() throws IOException {
      assertEquals("True", strOf("bool(True)"));
      assertEquals("True", strOf("bool(1)"));
      assertEquals("True", strOf("bool(-1)"));
      assertEquals("True", strOf("bool(0.5)"));
      assertEquals("True", strOf("bool('x')"));
      assertEquals("True", strOf("bool([1])"));
      assertEquals("True", strOf("bool({'a': 1})"));
      assertEquals("True", strOf("bool((1,))"));
    }

    @Test
    public void rejectsMoreThanOneArgument() {
      assertRaises(PyTypeError.class, "bool(1, 2)");
    }
  }


  @Nested
  public final class Callable {
    @Test
    public void recognizesBuiltinsAndTypes() throws IOException {
      assertEquals("True", strOf("callable(abs)"));
      assertEquals("True", strOf("callable(int)"));
      assertEquals("True", strOf("callable(type(1))"));
    }

    @Test
    public void recognizesPythonFunctions() throws IOException {
      assertEquals("True", strOf("""
        callable(abs)
        """));

      assertEquals("True\nFalse\n", runCaptureOutput("""
        def identity(x):
            return x
        print(callable(identity))
        print(callable(42))
        """));
    }

    @Test
    public void ordinaryValuesAreNotCallable() throws IOException {
      assertEquals("False", strOf("callable(42)"));
      assertEquals("False", strOf("callable(None)"));
      assertEquals("False", strOf("callable([])"));
    }
  }


  @Nested
  public final class Chr {
    @Test
    public void convertsCodePoints() throws IOException {
      assertEquals("A", strOf("chr(65)"));
      assertEquals("é", strOf("chr(233)"));
      assertEquals("😀", strOf("chr(128512)"));
      assertEquals("'\\x00'", reprOf("chr(0)"));
      assertEquals("'\\U0010ffff'", reprOf("chr(1114111)"));
    }

    @Test
    public void rejectsOutOfRangeValues() {
      assertRaises(PyValueError.class, "chr(-1)");
      assertRaises(PyValueError.class, "chr(1114112)");
    }

    @Test
    public void rejectsNonIntegers() {
      assertRaises(PyTypeError.class, "chr(1.0)");
      assertRaises(PyTypeError.class, "chr('65')");
    }
  }


  @Nested
  public final class Dict {
    @Test
    public void empty() throws IOException {
      assertEquals("0", strOf("len(dict())"));
    }

    @Test
    public void copyConstructor() throws IOException {
      assertEquals("1\n2\n", runCaptureOutput("""
          d = {'a': 1, 'b': 2}
          c = dict(d)
          print(c['a'])
          print(c['b'])
          """));
    }

    @Test
    public void fromPairs() throws IOException {
      assertEquals("1\n2\n", runCaptureOutput("""
          d = dict([('a', 1), ('b', 2)])
          print(d['a'])
          print(d['b'])
          """));
    }

    @Test
    public void duplicateKeyLastWins() throws IOException {
      assertEquals("2", strOf("dict([('a', 1), ('a', 2)])['a']"));
    }

    @Test
    public void stringPairsAreSequences() throws IOException {
      assertEquals("b\nd\n", runCaptureOutput("""
          d = dict(['ab', 'cd'])
          print(d['a'])
          print(d['c'])
          """));
    }

    @Test
    public void pairLengthMustBeTwo() {
      //assertRaises(PyValueError.class, "dict([('a',)])");  // FIXME Not Yet supported
      // assertRaises(PyValueError.class, "dict([()])");     // FIXME Not Yet supported
      assertRaises(PyValueError.class, "dict(['abc'])");
    }

    @Test
    public void pairMustBeSequence() {
      assertRaises(PyTypeError.class, "dict([42])");
    }

    @Test
    public void tooManyArguments() {
      assertRaises(PyTypeError.class, "dict([], [])");
    }
  }


  @Nested
  public final class DivMod {
    @Test
    public void integerSignCombinations() throws IOException {
      assertEquals("(2, 1)", reprOf("divmod(7, 3)"));
      assertEquals("(-3, 2)", reprOf("divmod(-7, 3)"));
      assertEquals("(-3, -2)", reprOf("divmod(7, -3)"));
      assertEquals("(2, -1)", reprOf("divmod(-7, -3)"));
    }

    @Test
    public void booleans() throws IOException {
      assertEquals("(1, 0)", reprOf("divmod(True, True)"));
      assertEquals("(0, 0)", reprOf("divmod(False, True)"));
    }

    @Test
    public void bigIntegers() throws IOException {
      assertEquals("(33333333333333333333, 1)",
          reprOf("divmod(10**20, 3)"));
      assertEquals(
          "(9223372036854775808, 0)",
          reprOf("divmod(-(2**63), -1)"));
    }

    @Test
    public void floats() throws IOException {
      assertEquals("(3.0, 1.5)", reprOf("divmod(7.5, 2.0)"));
      assertEquals("(-4.0, 0.5)", reprOf("divmod(-7.5, 2.0)"));
      assertEquals("(-4.0, -0.5)", reprOf("divmod(7.5, -2.0)"));
      assertEquals("(3.0, -1.5)", reprOf("divmod(-7.5, -2.0)"));
    }

    @Test
    public void zeroIntegerDivisor() {
      var error = assertThrows(PyZeroDivisionError.class,
          () -> run("divmod(1, 0)"));
      assertEquals("integer division or modulo by zero",
          error.getMessage());
    }

    @Test
    public void zeroFloatDivisor() {
      var error = assertThrows(PyZeroDivisionError.class,
          () -> run("divmod(1.0, 0.0)"));
      assertEquals("float divmod()", error.getMessage());

      error = assertThrows(PyZeroDivisionError.class,
          () -> run("divmod(1, 0.0)"));
      assertEquals("float divmod()", error.getMessage());
    }

    @Test
    public void rejectsNonNumericOperands() {
      assertRaises(PyTypeError.class, "divmod('a', 1)");
      assertRaises(PyTypeError.class, "divmod(1, None)");
    }
  }


  @Nested
  public final class Id {
    @Test
    public void isStableForTheSameObject() throws IOException {
      assertEquals("True\n", runCaptureOutput("""
        value = []
        print(id(value) == id(value))
        """));
    }

    @Test
    public void returnsAnInteger() throws IOException {
      assertEquals("<class 'int'>", strOf("type(id(None))"));
    }
  }


  @Nested
  public final class Int {
    @Test
    public void noArguments() throws IOException {
      assertEquals("0", strOf("int()"));
    }

    @Test
    public void fromIntegersAndBooleans() throws IOException {
      assertEquals("1", strOf("int(True)"));
      assertEquals("0", strOf("int(False)"));
      assertEquals("42", strOf("int(42)"));
      assertEquals("100000000000000000000", strOf("int(10**20)"));
    }

    @Test
    public void floatsTruncateTowardZero() throws IOException {
      assertEquals("3", strOf("int(3.9)"));
      assertEquals("-3", strOf("int(-3.9)"));
      assertEquals("0", strOf("int(-0.0)"));
    }

    @Test
    public void nonFiniteFloats() {
      assertRaises(PyOverflowError.class, "int(1e309)");
      assertRaises(PyOverflowError.class, "int(-1e309)");
      assertRaises(PyValueError.class, "int(1e309 - 1e309)");
    }

    @Test
    public void stringsWithWhitespaceAndSigns() throws IOException {
      assertEquals("42", strOf("int(' 42 ')"));
      assertEquals("42", strOf("int('+42')"));
      assertEquals("-42", strOf("int('-42')"));
      assertEquals("31", strOf("int('  +0x1f  ', 0)"));
      assertEquals("-16", strOf("int('-0x10', 0)"));
    }

    @Test
    public void prefixedStringsWithBaseZero() throws IOException {
      assertEquals("10", strOf("int('0b1010', 0)"));
      assertEquals("15", strOf("int('0o17', 0)"));
      assertEquals("31", strOf("int('0x1f', 0)"));
    }

    @Test
    public void explicitBases() throws IOException {
      assertEquals("255", strOf("int('ff', 16)"));
      assertEquals("255", strOf("int('FF', 16)"));
      assertEquals("255", strOf("int('0xff', 16)"));
      assertEquals("5", strOf("int('101', 2)"));
      assertEquals("5", strOf("int('0b101', 2)"));
      assertEquals("35", strOf("int('z', 36)"));
    }

    @Test
    public void underscores() throws IOException {
      assertEquals("1000", strOf("int('1_000')"));
      assertEquals("255", strOf("int('0x_FF', 0)"));
      assertEquals("5", strOf("int('0b_101', 0)"));
    }

    @Test
    public void unicodeDecimalDigits() throws IOException {
      assertEquals("12", strOf("int('١٢')"));
    }

    @Test
    public void invalidStrings() {
      for (var value : new String[]{
          "", "+", "-", "1__0", "_1", "1_",
          "0x", "0b2", "12.3", "0xGG"}) {
        assertRaises(PyValueError.class, "int('" + value + "')");
      }
      // Leading zero not allowed when base 0 is requested.
      assertRaises(PyValueError.class, "int('010', 0)");
    }

    @Test
    public void invalidBases() {
      assertRaises(PyValueError.class, "int('10', -1)");
      assertRaises(PyValueError.class, "int('10', 1)");
      assertRaises(PyValueError.class, "int('10', 37)");
    }

    @Test
    public void twoArgumentsRequireString() {
      assertRaises(PyTypeError.class, "int(10, 2)");
    }

    @Test
    public void rejectsUnsupportedTypes() {
      assertRaises(PyTypeError.class, "int(None)");
    }

    @Test
    public void tooManyArguments() {
      assertRaises(PyTypeError.class, "int(1, 2, 3)");
    }
  }


  @Nested
  public final class Float {
    @Test
    public void noArguments() throws IOException {
      assertEquals("0.0", strOf("float()"));
    }

    @Test
    public void convertsNumbersAndStrings() throws IOException {
      assertEquals("1.0", strOf("float(True)"));
      assertEquals("0.0", strOf("float(False)"));
      assertEquals("42.0", strOf("float(42)"));
      assertEquals("-3.5", strOf("float('-3.5')"));
      assertEquals("1e+20", strOf("float(10**20)"));
    }

    @Test
    public void specialValuesAndAdditionalFormats() throws IOException {
      assertEquals("inf", strOf("float('Infinity')"));
      assertEquals("-inf", strOf("float('-inf')"));
      assertEquals("nan", strOf("float('NaN')"));
      assertEquals("1000.5", strOf("float('1_000.5')"));
      assertEquals("12.5", strOf("float('١٢.٥')"));
    }

    @Test
    public void largeIntegerOverflows() {
      assertRaises(PyOverflowError.class, "float(10**400)");
    }

    @Test
    public void invalidArguments() {
      assertRaises(PyValueError.class, "float('not a number')");
      assertRaises(PyValueError.class, "float('1__2')");
      assertRaises(PyTypeError.class, "float(None)");
      assertRaises(PyTypeError.class, "float(1, 2)");
    }
  }


  @Nested
  public final class Len {
    @Test
    public void stringsCountCodePoints() throws IOException {
      assertEquals("0", strOf("len('')"));
      assertEquals("5", strOf("len('hello')"));
      assertEquals("1", strOf("len('😀')"));
      assertEquals("3", strOf("len('a😀b')"));
    }

    @Test
    public void tuplesAndLists() throws IOException {
      assertEquals("0", strOf("len(())"));
      assertEquals("1", strOf("len((1,))"));
      assertEquals("2", strOf("len((1, 2))"));
      assertEquals("0", strOf("len([])"));
      assertEquals("2", strOf("len([1, 2])"));
    }

    @Test
    public void dicts() throws IOException {
      assertEquals("0", strOf("len({})"));
      assertEquals("1", strOf("len({'a': 1})"));
      assertEquals("2", strOf("len({'a': 1, 'b': 2})"));
    }

    @Test
    public void unsupportedTypes() {
      assertRaises(PyTypeError.class, "len(1)");
      assertRaises(PyTypeError.class, "len(None)");
    }
  }

  @Nested
  public final class ListConstructor {
    @Test
    public void emptyAndIterableConversions() throws IOException {
      assertEquals("[]", reprOf("list()"));
      assertEquals("[1, 2]", reprOf("list((1, 2))"));
      assertEquals("['a', '😀']", reprOf("list('a😀')"));
    }

    @Test
    public void rejectsNonIterables() {
      assertRaises(PyTypeError.class, "list(42)");
    }
  }


  @Nested
  public final class Max {
    @Test
    public void acceptsMultipleArguments() throws IOException {
      assertEquals("3", strOf("max(3, 1, 2)"));
    }

    @Test
    public void acceptsAnIterable() throws IOException {
      assertEquals("3", strOf("max((1, 2, 3))"));
      assertEquals("c", strOf("max('cab')"));
    }

    @Test
    public void rejectsEmptyIterables() {
      assertRaises(PyValueError.class, "max(())");
    }

    @Test
    public void rejectsMissingArguments() {
      assertRaises(PyTypeError.class, "max()");
    }

    @Test
    public void rejectsNonIterables() {
      assertRaises(PyTypeError.class, "max(None)");
    }
  }

  @Nested
  public final class Min {
    @Test
    public void acceptsMultipleArguments() throws IOException {
      assertEquals("1", strOf("min(3, 1, 2)"));
    }

    @Test
    public void acceptsAnIterable() throws IOException {
      assertEquals("1", strOf("min([3, 1, 2])"));
      assertEquals("a", strOf("min('cab')"));
    }

    @Test
    public void rejectsEmptyIterables() {
      assertRaises(PyValueError.class, "min([])");
    }

    @Test
    public void rejectsMissingArguments() {
      assertRaises(PyTypeError.class, "min()");
    }

    @Test
    public void rejectsNonIterables() {
      assertRaises(PyTypeError.class, "min(42)");
    }
  }

  @Nested
  public final class Sum {
    @Test
    public void sumsIntegersAndFloats() throws IOException {
      assertEquals("0", strOf("sum([])"));
      assertEquals("6", strOf("sum([1, 2, 3])"));
      assertEquals("3.5", strOf("sum([1.5, 2.0])"));
      assertEquals("3", strOf("sum([True, 2])"));
    }

    @Test
    public void supportsStartingValue() throws IOException {
      assertEquals("16", strOf("sum([1, 2, 3], 10)"));
      assertEquals("3.5", strOf("sum([], 3.5)"));
    }

    @Test
    public void promotesIntegerOverflow() throws IOException {
      assertEquals("9223372036854775809",
          strOf("sum([2**63, 1])"));
    }

    @Test
    public void rejectsUnsupportedValues() {
      assertRaises(PyTypeError.class, "sum([1, 'x'])");
      assertRaises(PyTypeError.class, "sum([1], '0')");
      assertRaises(PyTypeError.class, "sum(42)");
    }

    @Test
    public void argumentCount() {
      assertRaises(PyTypeError.class, "sum()");
      assertRaises(PyTypeError.class, "sum([], 0, 1)");
    }
  }


  @Nested
  public final class Ord {
    @Test
    public void singleCodePoints() throws IOException {
      assertEquals("65", strOf("ord('A')"));
      assertEquals("233", strOf("ord('é')"));
      assertEquals("128512", strOf("ord('😀')"));
    }

    @Test
    public void rejectsWrongLength() {
      assertRaises(PyTypeError.class, "ord('')");
      assertRaises(PyTypeError.class, "ord('ab')");
    }

    @Test
    public void rejectsNonStrings() {
      assertRaises(PyTypeError.class, "ord(65)");
      assertRaises(PyTypeError.class, "ord(None)");
    }
  }


  @Nested
  public final class Pow {
    @Test
    public void twoArgumentIntegers() throws IOException {
      assertEquals("1024", strOf("pow(2, 10)"));
      assertEquals("1", strOf("pow(0, 0)"));
      assertEquals("0", strOf("pow(0, 1)"));
      assertEquals("-8", strOf("pow(-2, 3)"));
      assertEquals("4", strOf("pow(-2, 2)"));
      assertEquals("0.5", strOf("pow(2, -1)"));
    }

    @Test
    public void integerOverflowProducesBigInteger() throws IOException {
      assertEquals("100000000000000000000", strOf("pow(10, 20)"));
      assertEquals("9223372036854775808", reprOf("pow(2, 63)"));
      assertEquals(
          "85070591730234615847396907784232501249",
          strOf("pow(Long, 2)".replace("Long", "9223372036854775807")));
    }

    @Test
    public void booleans() throws IOException {
      assertEquals("1", strOf("pow(True, 5)"));
      assertEquals("1", strOf("pow(False, 0)"));
      assertEquals("0", strOf("pow(False, 5)"));
    }

    @Test
    public void threeArgumentModularPow() throws IOException {
      assertEquals("24", strOf("pow(2, 10, 1000)"));
      assertEquals("4", strOf("pow(3, -1, 11)"));
      assertEquals("-2", strOf("pow(2, 3, -5)"));
    }

    @Test
    public void modularPowCornerCases() {
      assertRaises(PyValueError.class, "pow(2, 3, 0)");
      assertRaises(PyValueError.class, "pow(2, -1, 4)");
      assertRaises(PyTypeError.class, "pow(2.0, 3, 5)");
      assertRaises(PyTypeError.class, "pow(2, 3, 5.0)");
    }

    @Test
    public void floatPow() throws IOException {
      assertEquals("8.0", strOf("pow(2.0, 3.0)"));
      assertEquals("0.5", strOf("pow(2.0, -1.0)"));
      assertEquals("1.0", strOf("pow(2.0, 0.0)"));
    }

    @Test
    public void zeroToNegativePower() {
      assertRaises(PyZeroDivisionError.class, "pow(0.0, -1.0)");
    }

    @Test
    public void negativeFloatToNonIntegerPowerIsUnsupported() {
      assertRaises(UnimplementedFeatureError.class, "pow(-2.0, 0.5)");
    }

    @Test
    public void argumentCount() {
      assertRaises(PyTypeError.class, "pow(1)");
      assertRaises(PyTypeError.class, "pow(1, 2, 3, 4)");
    }
  }


  @Nested
  public final class Print {
    @Test
    public void printOneArgument() throws IOException {
      assertEquals("hello, world!\n", runCaptureOutput("""
          print("hello, world!")
          """));
    }

    @Test
    public void printTwoArguments() throws IOException {
      assertEquals("one two\n", runCaptureOutput("""
          print("one", "two")
          """));
    }

    @Test
    public void printScalarValues() throws IOException {
      assertEquals("12 3.4 True hello None 12345678901234567890\n",
          runCaptureOutput("""
              print(12, 3.4, True, "hello", None, 12345678901234567890)
              """));
    }

    @Test
    public void printNoArguments() throws IOException {
      assertEquals("\n", runCaptureOutput("print()"));
    }
  }


  @Nested
  public final class Range {
    private static String runLoop(String range) throws IOException {
      return runCaptureOutput("""
          for i in %s:
              print(i)
          """.formatted(range));
    }

    @Test
    public void oneArgument() throws IOException {
      assertEquals("0\n1\n2\n3\n4\n", runLoop("range(5)"));
      assertEquals("", runLoop("range(0)"));
      assertEquals("0\n", runLoop("range(True)"));
      assertEquals("", runLoop("range(False)"));
    }

    @Test
    public void twoArguments() throws IOException {
      assertEquals("2\n3\n4\n", runLoop("range(2, 5)"));
      assertEquals("", runLoop("range(5, 2)"));
    }

    @Test
    public void threeArguments() throws IOException {
      assertEquals("1\n4\n7\n", runLoop("range(1, 10, 3)"));
      assertEquals("10\n7\n4\n1\n", runLoop("range(10, 0, -3)"));
      assertEquals("1\n3\n", runLoop("range(1, 4, 2)"));
      assertEquals("5\n3\n1\n", runLoop("range(5, -1, -2)"));
      assertEquals("0\n", runLoop("range(0, 10, 10)"));
    }

    @Test
    public void emptyRanges() throws IOException {
      assertEquals("", runLoop("range(0, 3, -1)"));
      assertEquals("", runLoop("range(3, 0, 1)"));
      assertEquals("", runLoop("range(0, 0, 1)"));
      assertEquals("", runLoop("range(0, 0, -1)"));
    }

    @Test
    public void zeroStep() {
      assertRaises(PyValueError.class, "range(0, 10, 0)");
    }

    @Test
    public void nonIntegerArguments() {
      assertRaises(PyTypeError.class, "range(1.0)");
      assertRaises(PyTypeError.class, "range(0, 1.0)");
      assertRaises(PyTypeError.class, "range(0, 10, 1.0)");
    }

    @Test
    public void argumentCount() {
      assertRaises(PyTypeError.class, "range()");
      assertRaises(PyTypeError.class, "range(1, 2, 3, 4)");
    }

    @Test
    @Disabled
    public void tooLargeRange() {
      assertRaises(UnimplementedFeatureError.class,
          "range(2_147_483_640)");
      assertRaises(UnimplementedFeatureError.class,
          "range(0, 2_147_483_640)");
      assertRaises(UnimplementedFeatureError.class,
          "range(2_147_483_640, 0, -1)");
      assertRaises(UnimplementedFeatureError.class,
          "range(-9223372036854775808, 9223372036854775807)");
    }

    @Test
    public void bigRangeIteration() throws IOException {
      assertEquals(
          """
              -9223372036854775808
              -1
              9223372036854775806
              """,
          runLoop(
              "range(-9223372036854775808, 9223372036854775807, 9223372036854775807)"));
    }
  }

  @Nested
  public final class PrintIntegerBases {
    @Test
    public void bin() throws IOException {
      assertEquals("0b0", strOf("bin(0)"));
      assertEquals("0b101", strOf("bin(5)"));
      assertEquals("-0b101", strOf("bin(-5)"));
      assertEquals("0b1", strOf("bin(True)"));
      assertEquals("0b0", strOf("bin(False)"));
    }

    @Test
    public void binOfBigInteger() throws IOException {
      assertEquals("0b1" + "0".repeat(100), strOf("bin(2**100)"));
    }

    @Test
    public void hex() throws IOException {
      assertEquals("0x0", strOf("hex(0)"));
      assertEquals("0xff", strOf("hex(255)"));
      assertEquals("-0xff", strOf("hex(-255)"));
      assertEquals("0x1", strOf("hex(True)"));
      assertEquals("0x" + "1" + "0".repeat(25), strOf("hex(2**100)"));
    }

    @Test
    public void oct() throws IOException {
      assertEquals("0o0", strOf("oct(0)"));
      assertEquals("0o10", strOf("oct(8)"));
      assertEquals("-0o10", strOf("oct(-8)"));
      assertEquals("0o1", strOf("oct(True)"));
      assertEquals("0o777", strOf("oct(511)"));
    }

    @Test
    public void rejectNonIntegers() {
      assertRaises(PyTypeError.class, "bin(1.0)");
      assertRaises(PyTypeError.class, "hex('10')");
      assertRaises(PyTypeError.class, "oct(None)");
    }
  }


  @Nested
  public final class Repr {
    @Test
    public void strings() throws IOException {
      assertEquals("'hello'", reprOf("'hello'"));
      assertEquals("\"a'b\"", reprOf("\"a'b\""));
      assertEquals("'a\"b'", reprOf("'a\"b'"));
      assertEquals("'a\\nb'", reprOf("'a\\nb'"));
      assertEquals("'a\\\\b'", reprOf("'a\\\\b'"));
    }

    @Test
    public void scalars() throws IOException {
      assertEquals("42", reprOf("42"));
      assertEquals("True", reprOf("True"));
      assertEquals("None", reprOf("None"));
      assertEquals("1.0", reprOf("1.0"));
      assertEquals("-0.0", reprOf("-0.0"));
      assertEquals("100000000000000000000", reprOf("10**20"));
    }

    @Test
    public void tuples() throws IOException {
      assertEquals("()", reprOf("()"));
      assertEquals("(1,)", reprOf("(1,)"));
      assertEquals("(1, 'x')", reprOf("(1, 'x')"));
    }

    @Test
    public void lists() throws IOException {
      assertEquals("[]", reprOf("[]"));
      assertEquals("[1, 'x']", reprOf("[1, 'x']"));
    }

    @Test
    public void dicts() throws IOException {
      assertEquals("{}", reprOf("{}"));
      assertEquals("{'a': 1, 'b': 2}",
          reprOf("{'a': 1, 'b': 2}"));
    }
  }


  @Nested
  public final class Round {
    @Test
    public void noNdigitsFloats() throws IOException {
      assertEquals("2", strOf("round(2.5)"));
      assertEquals("4", strOf("round(3.5)"));
      assertEquals("-2", strOf("round(-2.5)"));
      assertEquals("3", strOf("round(2.6)"));
      assertEquals("-3", strOf("round(-2.6)"));
    }

    @Test
    public void noNdigitsIntegersAndBooleans() throws IOException {
      assertEquals("42", strOf("round(42)"));
      assertEquals("1", strOf("round(True)"));
      assertEquals("0", strOf("round(False)"));
      assertEquals("10", strOf("round(10)"));
    }

    @Test
    public void noNdigitsNonFiniteFloats() {
      assertRaises(PyOverflowError.class, "round(1e309)");
      assertRaises(PyOverflowError.class, "round(-1e309)");
      assertRaises(PyOverflowError.class, "round(1e309 - 1e309)");
    }

    @Test
    public void noNdigitsUnsupportedType() {
      assertRaises(PyTypeError.class, "round('1')");
    }

    @Test
    public void integerNegativeNdigits() throws IOException {
      assertEquals("1200", strOf("round(1234, -2)"));
      assertEquals("1400", strOf("round(1350, -2)"));
      assertEquals("-1200", strOf("round(-1250, -2)"));
      assertEquals("-1400", strOf("round(-1350, -2)"));
      assertEquals("0", strOf("round(5, -2)"));
      assertEquals("0", strOf("round(-5, -2)"));
    }

    @Test
    public void exactMultiplesAreNotRoundedUp() throws IOException {
      // CPython: round(10, -1) == 10 and round(100, -2) == 100.
      assertEquals("10", strOf("round(10, -1)"));
      assertEquals("100", strOf("round(100, -2)"));
    }

    @Test
    public void bankerRoundingOnNegativeNdigits() throws IOException {
      assertEquals("20", strOf("round(15, -1)"));
      assertEquals("0", strOf("round(5, -1)"));
      assertEquals("20", strOf("round(25, -1)"));
      assertEquals("40", strOf("round(35, -1)"));
    }

    @Test
    public void nonNegativeNdigitsOnIntegers() throws IOException {
      assertEquals("1234", strOf("round(1234, 2)"));
      assertEquals("1234", strOf("round(1234, 0)"));
    }

    @Test
    public void floatNdigits() throws IOException {
      assertEquals("2.67", strOf("round(2.675, 2)"));
      assertEquals("1.2", strOf("round(1.25, 1)"));
      assertEquals("1.4", strOf("round(1.35, 1)"));
      assertEquals("1200.0", strOf("round(1234.5678, -2)"));
      assertEquals("2.0", strOf("round(2.5, 0)"));
      assertEquals("4.0", strOf("round(3.5, 0)"));
      assertEquals("-2.0", strOf("round(-2.5, 0)"));
    }

    @Test
    public void extremeNdigits() throws IOException {
      assertEquals("1.0", strOf("round(1.0, 400)"));
      assertEquals("0.0", strOf("round(1.0, -400)"));
    }

    @Test
    public void ndigitsNoneMeansNoNdigits() throws IOException {
      assertEquals("2", strOf("round(2.5, None)"));
    }

    @Test
    public void invalidNdigits() {
      assertRaises(PyTypeError.class, "round(1, 2.5)");
      assertRaises(PyTypeError.class, "round(1, '2')");
    }

    @Test
    public void argumentCount() {
      assertRaises(PyTypeError.class, "round()");
      assertRaises(PyTypeError.class, "round(1, 2, 3)");
    }
  }


  @Nested
  public final class Slice {
    @Test
    public void constructorArgumentForms() throws IOException {
      assertEquals("slice(None, 5, None)", strOf("slice(5)"));
      assertEquals("slice(1, 5, None)", strOf("slice(1, 5)"));
      assertEquals("slice(1, 5, 2)", strOf("slice(1, 5, 2)"));
    }

    @Test
    public void worksAsAListIndex() throws IOException {
      assertEquals("[3, 2, 1, 0]",
          reprOf("[0, 1, 2, 3][slice(None, None, -1)]"));
    }

    @Test
    public void zeroStepIsRejectedWhenSliceIsUsed() {
      assertRaises(PyValueError.class,
          "[0, 1, 2][slice(None, None, 0)]");
    }

    @Test
    public void argumentCount() {
      assertRaises(PyTypeError.class, "slice()");
      assertRaises(PyTypeError.class, "slice(1, 2, 3, 4)");
    }
  }


  @Nested
  public final class TupleConstructor {
    @Test
    public void emptyAndIterableConversions() throws IOException {
      assertEquals("()", reprOf("tuple()"));
      assertEquals("(1, 2)", reprOf("tuple([1, 2])"));
      assertEquals("('a', '😀')", reprOf("tuple('a😀')"));
    }

    @Test
    public void rejectsNonIterables() {
      assertRaises(PyTypeError.class, "tuple(None)");
    }
  }


  @Nested
  public final class Str {
    @Test
    @Disabled
    public void noArguments() throws IOException {
      assertEquals("", strOf("str()"));
    }
  }


  @Nested
  public final class Type {
    @Test
    public void typeBuiltinIsSingleArgument() throws IOException {
      assertEquals("<class 'int'>", reprOf("type(1)"));

      assertRaises(PyTypeError.class, "type()");
      assertRaises(PyTypeError.class, "type(1, 2)");
    }
  }
}