package com.github.forax.orvet.rt;

import com.github.forax.orvet.rt.error.PyRuntimeError;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.*;

public final class PyIteratorTest {

  @Nested
  public final class TupleIteration {

    @Test
    public void tupleIteratorVisitsElementsInOrder() {
      var tuple = new PyTuple.PyTuple3("first", "second", "third");
      var iterator = new PyIterator.TupleIterator(tuple);

      assertEquals("first", iterator.next());
      assertEquals("second", iterator.next());
      assertEquals("third", iterator.next());
      assertNull(iterator.next());
      assertNull(iterator.next());
    }

    @Test
    public void tupleIteratorSupportsTheGeneralTupleRepresentation() {
      var tuple = new PyTuple.PyTupleN(new Object[]{1L, 2L, 3L, 4L, 5L});
      var iterator = new PyIterator.TupleIterator(tuple);

      for (var expected = 1L; expected <= 5L; expected++) {
        assertEquals(expected, iterator.next());
      }
      assertNull(iterator.next());
    }

    @Test
    public void tupleIteratorDoesNotConfusePythonNoneWithExhaustion() {
      var iterator = new PyIterator.TupleIterator(new PyTuple.PyTuple1(PyNone.INSTANCE));

      assertSame(PyNone.INSTANCE, iterator.next());
      assertNull(iterator.next());
    }

    @Test
    public void tupleIteratorHandlesAnEmptyTuple() {
      var iterator = new PyIterator.TupleIterator(new PyTuple.PyTupleN(new Object[0]));

      assertNull(iterator.next());
      assertNull(iterator.next());
    }
  }

  @Nested
  public final class ListIteration {

    @Test
    public void listIteratorVisitsElementsInOrderAndThenReturnsNull() {
      var iterator = new PyIterator.ListIterator(new PyList<>("first", "second"));

      assertEquals("first", iterator.next());
      assertEquals("second", iterator.next());
      assertNull(iterator.next());
      assertNull(iterator.next());
    }

    @Test
    public void listIteratorDoesNotConfusePythonNoneWithExhaustion() {
      var list = new PyList<>(PyNone.INSTANCE);
      var iterator = new PyIterator.ListIterator(list);

      assertSame(PyNone.INSTANCE, iterator.next());
      assertNull(iterator.next());
    }

    @Test
    public void listIteratorObservesChangesToUnvisitedElementsAndAppends() {
      var list = new PyList<>("first", "second");
      var iterator = new PyIterator.ListIterator(list);

      assertEquals("first", iterator.next());

      list.set(1, "updated");
      assertEquals("updated", iterator.next());

      assertNull(iterator.next());

      // ListIterator does not become permanently exhausted: it checks the current size
      // every time next() is called.
      list.append("appended");
      assertEquals("appended", iterator.next());
      assertNull(iterator.next());
    }

    @Test
    public void listIteratorHandlesAnInitiallyEmptyList() {
      var list = new PyList<>();
      var iterator = new PyIterator.ListIterator(list);

      assertNull(iterator.next());
      assertNull(iterator.next());
    }
  }


  @Nested
  public final class StringIteration {

    @Test
    public void stringIteratorVisitsCharactersInOrder() {
      var iterator = new PyIterator.StringIterator("abc");

      assertEquals("a", iterator.next());
      assertEquals("b", iterator.next());
      assertEquals("c", iterator.next());
      assertNull(iterator.next());
      assertNull(iterator.next());
    }

    @Test
    public void stringIteratorReturnsUnicodeCodePointsRatherThanUtf16Chars() {
      var iterator = new PyIterator.StringIterator("A\uD83D\uDE00\uD834\uDD1EZ");

      assertEquals("A", iterator.next());
      assertEquals("\uD83D\uDE00", iterator.next());
      assertEquals("\uD834\uDD1E", iterator.next());
      assertEquals("Z", iterator.next());
      assertNull(iterator.next());
    }

    @Test
    public void stringIteratorHandlesAnEmptyString() {
      var iterator = new PyIterator.StringIterator("");

      assertNull(iterator.next());
      assertNull(iterator.next());
    }
  }


  @Nested
  public final class DictionaryIteration {

    @Test
    public void dictIteratorVisitsKeysInInsertionOrder() {
      var dict = new PyDict<String, Object>();
      dict.put("first", 1L);
      dict.put("second", 2L);
      dict.put("third", 3L);
      var iterator = new PyIterator.DictIterator(dict);

      assertEquals("first", iterator.next());
      assertEquals("second", iterator.next());
      assertEquals("third", iterator.next());
      assertNull(iterator.next());
      assertNull(iterator.next());
    }

    @Test
    public void dictIteratorDoesNotConfusePythonNoneWithExhaustion() {
      var dict = new PyDict<>();
      dict.put(PyNone.INSTANCE, PyNone.INSTANCE);
      var iterator = new PyIterator.DictIterator(dict);

      assertSame(PyNone.INSTANCE, iterator.next());
      assertNull(iterator.next());
    }

    @Test
    public void dictIteratorSkipsEntriesDeletedBeforeIterationStarts() {
      var dict = new PyDict<String, Object>();
      dict.put("first", 1L);
      dict.put("deleted", 2L);
      dict.put("last", 3L);
      dict.remove("deleted");
      var iterator = new PyIterator.DictIterator(dict);

      assertEquals("first", iterator.next());
      assertEquals("last", iterator.next());
      assertNull(iterator.next());
    }

    @Test
    public void dictIteratorAllowsReplacingValues() {
      var dict = new PyDict<String, Object>();
      dict.put("first", 1L);
      dict.put("second", 2L);
      var iterator = new PyIterator.DictIterator(dict);

      assertEquals("first", iterator.next());

      // Replacing a value does not change the dictionary's key layout.
      dict.put("second", 20L);

      assertEquals("second", iterator.next());
      assertNull(iterator.next());
      assertEquals(20L, dict.get("second"));
    }

    @Test
    public void dictIteratorHandlesAnEmptyDictionary() {
      var dict = new PyDict<>();
      var iterator = new PyIterator.DictIterator(dict);

      assertNull(iterator.next());
      assertNull(iterator.next());
    }

    @Test
    public void dictIteratorFailsWhenTheDictionarySizeChanges() {
      var dict = new PyDict<String, Object>();
      dict.put("first", 1L);
      var iterator = new PyIterator.DictIterator(dict);

      dict.put("second", 2L);

      var error = assertThrows(PyRuntimeError.class, iterator::next);
      assertEquals("dictionary changed size during iteration", error.getMessage());
    }

    @Test
    public void dictIteratorFailsWhenKeysChangeWithoutChangingTheSize() {
      var dict = new PyDict<String, Object>();
      dict.put("first", 1L);
      dict.put("second", 2L);
      var iterator = new PyIterator.DictIterator(dict);

      assertEquals("first", iterator.next());

      // A deletion followed by an insertion preserves size but changes the key set.
      dict.remove("second");
      dict.put("replacement", 3L);

      var error = assertThrows(PyRuntimeError.class, iterator::next);
      assertEquals("dictionary keys changed during iteration", error.getMessage());

      // Once iteration has failed, subsequent calls keep failing with the same error.
      var repeatedError = assertThrows(PyRuntimeError.class, iterator::next);
      assertEquals(error.getMessage(), repeatedError.getMessage());
    }
  }


  @Nested
  public final class SmallRangeIteration {

    @Test
    public void smallRangeIteratorSupportsPositiveSteps() {
      var range = (PyRange.PyRangeSmall) PyRange.of(1, 8, 2);
      var iterator = new PyIterator.SmallRangeIterator(range);

      assertEquals(1L, iterator.next());
      assertEquals(3L, iterator.next());
      assertEquals(5L, iterator.next());
      assertEquals(7L, iterator.next());
      assertNull(iterator.next());
    }

    @Test
    public void smallRangeIteratorSupportsNegativeSteps() {
      var range = (PyRange.PyRangeSmall) PyRange.of(10, 0, -3);
      var iterator = new PyIterator.SmallRangeIterator( range);

      assertEquals(10L, iterator.next());
      assertEquals(7L, iterator.next());
      assertEquals(4L, iterator.next());
      assertEquals(1L, iterator.next());
      assertNull(iterator.next());
    }

    @Test
    public void smallRangeIteratorHandlesEmptyRanges() {
      var range = (PyRange.PyRangeSmall) PyRange.of(0, 10, -1);
      var iterator = new PyIterator.SmallRangeIterator( range);

      assertNull(iterator.next());
      assertNull(iterator.next());
    }

    @Test
    public void smallRangeIteratorHandlesLongBoundaryWithoutOverflowingAfterTheLastElement() {
      var range = (PyRange.PyRangeSmall) PyRange.of(Long.MAX_VALUE, 0, Long.MIN_VALUE);
      var iterator = new PyIterator.SmallRangeIterator( range);

      assertEquals(Long.MAX_VALUE, iterator.next());
      assertNull(iterator.next());
    }
  }


  @Nested
  public final class BigRangeIteration {

    @Test
    public void bigRangeIteratorSupportsLargeValuesAndPositiveSteps() {
      var start = BigInteger.ONE.shiftLeft(70);
      var range = (PyRange.PyRangeBig) PyRange.of(start, start.add(BigInteger.valueOf(7)), BigInteger.valueOf(3));
      var iterator = new PyIterator.BigRangeIterator(range);

      assertEquals(start, iterator.next());
      assertEquals(start.add(BigInteger.valueOf(3)), iterator.next());
      assertEquals(start.add(BigInteger.valueOf(6)), iterator.next());
      assertNull(iterator.next());
      assertNull(iterator.next());
    }

    @Test
    public void bigRangeIteratorNormalizesValuesThatFitInALong() {
      var start = BigInteger.valueOf(Long.MAX_VALUE);
      var range = (PyRange.PyRangeBig) PyRange.of(start, start.add(BigInteger.TWO), BigInteger.ONE);
      var iterator = new PyIterator.BigRangeIterator( range);

      assertEquals(Long.MAX_VALUE, iterator.next());
      assertEquals(BigInteger.ONE.shiftLeft(63), iterator.next());
      assertNull(iterator.next());
    }

    @Test
    public void bigRangeIteratorSupportsNegativeSteps() {
      var start = BigInteger.ONE.shiftLeft(70);
      var range = (PyRange.PyRangeBig) PyRange.of(start, start.subtract(BigInteger.valueOf(5)), BigInteger.valueOf(-2));
      var iterator = new PyIterator.BigRangeIterator(range);

      assertEquals(start, iterator.next());
      assertEquals(start.subtract(BigInteger.TWO), iterator.next());
      assertEquals(start.subtract(BigInteger.valueOf(4)), iterator.next());
      assertNull(iterator.next());
    }

    @Test
    public void bigRangeIteratorHandlesAnEmptyRange() {
      var start = BigInteger.ONE.shiftLeft(70);
      var range = (PyRange.PyRangeBig) PyRange.of(start, BigInteger.ZERO, BigInteger.ONE);
      var iterator = new PyIterator.BigRangeIterator( range);

      assertNull(iterator.next());
      assertNull(iterator.next());
    }
  }
}
