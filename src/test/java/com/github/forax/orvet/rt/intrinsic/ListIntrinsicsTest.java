package com.github.forax.orvet.rt.intrinsic;

import com.github.forax.orvet.engine.Engine;
import com.github.forax.orvet.rt.ModuleContext;
import com.github.forax.orvet.rt.PyDict;
import com.github.forax.orvet.rt.PyIterator;
import com.github.forax.orvet.rt.PyList;
import com.github.forax.orvet.rt.PyNone;
import com.github.forax.orvet.rt.PySlice;
import com.github.forax.orvet.rt.PyTuple;
import com.github.forax.orvet.rt.error.PyIndexError;
import com.github.forax.orvet.rt.error.PyTypeError;
import com.github.forax.orvet.rt.error.PyValueError;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.Random;

import static com.github.forax.orvet.rt.intrinsic.ListIntrinsics._append_;
import static com.github.forax.orvet.rt.intrinsic.ListIntrinsics._clear_;
import static com.github.forax.orvet.rt.intrinsic.ListIntrinsics._copy_;
import static com.github.forax.orvet.rt.intrinsic.ListIntrinsics._count_;
import static com.github.forax.orvet.rt.intrinsic.ListIntrinsics._extend_;
import static com.github.forax.orvet.rt.intrinsic.ListIntrinsics._index_;
import static com.github.forax.orvet.rt.intrinsic.ListIntrinsics._insert_;
import static com.github.forax.orvet.rt.intrinsic.ListIntrinsics._pop_;
import static com.github.forax.orvet.rt.intrinsic.ListIntrinsics._remove_;
import static com.github.forax.orvet.rt.intrinsic.ListIntrinsics._reverse_;
import static com.github.forax.orvet.rt.intrinsic.ListIntrinsics._sort_;

import static org.junit.jupiter.api.Assertions.*;

public final class ListIntrinsicsTest {

    private static PyList<Object> pyList(Object... elements) {
      return new PyList<>(elements);
    }

    private static PyTuple pyTuple(Object... elements) {
      return TupleIntrinsics.fromArray(elements);
    }


    @Nested
    public final class NewList {
        @Test
        public void newListIsEmpty() {
            var list = ListIntrinsics.newList();

            assertEquals(0L, ListIntrinsics.$size$(list));
        }
    }

    @Nested
    public final class Size {
        @Test
        public void sizeReflectsElements() {
            var list = pyList(1L, "a", 2.5);

            assertEquals(3L, ListIntrinsics.$size$(list));
        }
    }

    @Nested
    public final class Get {
        @Test
        public void positiveIndex() {
            var list = pyList(10L, 20L, 30L);

            assertEquals(10L, ListIntrinsics.$get$(list, 0));
            assertEquals(30L, ListIntrinsics.$get$(list, 2));
        }

        @Test
        public void negativeIndex() {
            var list = pyList(10L, 20L, 30L);

            assertEquals(30L, ListIntrinsics.$get$(list, -1));
            assertEquals(10L, ListIntrinsics.$get$(list, -3));
        }

        @Test
        public void outOfRangeRaisesIndexError() {
            var list = pyList(10L, 20L, 30L);

            assertThrows(PyIndexError.class, () -> ListIntrinsics.$get$(list, 3));
            assertThrows(PyIndexError.class, () -> ListIntrinsics.$get$(list, -4));
        }
    }

    @Nested
    public final class Set {
        @Test
        public void returnsNoneAndUpdates() {
            var list = pyList(10L, 20L);
            var result = ListIntrinsics.$set$(list, 0, 99L);

            assertSame(PyNone.INSTANCE, result);
            assertEquals(99L, ListIntrinsics.$get$(list, 0));
        }

        @Test
        public void negativeIndex() {
            var list = pyList(10L, 20L);
            ListIntrinsics.$set$(list, -1, 99L);

            assertEquals(99L, ListIntrinsics.$get$(list, 1));
        }

        @Test
        public void outOfRangeRaisesIndexError() {
            var list = pyList(10L);
            assertThrows(PyIndexError.class, () -> ListIntrinsics.$set$(list, 1, 99L));
        }
    }

    @Nested
    public final class Delete {
        @Test
        public void returnsNoneAndRemoves() {
            var list = pyList(10L, 20L, 30L);
            var result = ListIntrinsics.$delete$(list, 1);

            assertSame(PyNone.INSTANCE, result);
            assertEquals(2L, ListIntrinsics.$size$(list));
            assertEquals(30L, ListIntrinsics.$get$(list, 1));
        }

        @Test
        public void listIteratorObservesRemovalBeforeItsCurrentPosition() {
            var list = new PyList<>("a", "b", "c", "d");
            var iterator = new PyIterator.ListIterator(list);

            assertEquals("a", iterator.next());

            list.remove(0);

            // The iterator is index-based: after removal, index 1 now contains "c".
            assertEquals("c", iterator.next());
            assertEquals("d", iterator.next());
            assertNull(iterator.next());
        }

        @Test
        public void negativeIndex() {
            var list = pyList(10L, 20L, 30L);
            ListIntrinsics.$delete$(list, -1);

            assertEquals(2L, ListIntrinsics.$size$(list));
        }

        @Test
        public void outOfRangeRaisesIndexError() {
            var list = pyList(10L);
            assertThrows(PyIndexError.class, () -> ListIntrinsics.$delete$(list, 1));
        }
    }

    @Nested
    public final class Concat {
        @Test
        public void concatenatesTwoLists() {
            var left = pyList(1L, 2L);
            var right = pyList("a", "b");
            var result = ListIntrinsics.$concat$(left, right);

            assertEquals(4L, ListIntrinsics.$size$(result));
            assertEquals(1L, ListIntrinsics.$get$(result, 0));
            assertEquals("b", ListIntrinsics.$get$(result, 3));
        }

        @Test
        public void resultIsIndependent() {
            var left = pyList(1L);
            var right = pyList(2L);
            var result = ListIntrinsics.$concat$(left, right);
            left.append(99L);

            assertEquals(2L, ListIntrinsics.$size$(result));
        }
    }

    @Nested
    public final class Repeat {
        @Test
        public void zeroOrNegativeCountReturnsEmpty() {
            var list = pyList(1L, 2L);

            assertTrue(ListIntrinsics.$repeat$(list, 0).isEmpty());
            assertTrue(ListIntrinsics.$repeat$(list, -1).isEmpty());
        }

        @Test
        public void countOneReturnsCopy() {
            var list = pyList(1L, 2L);
            var result = ListIntrinsics.$repeat$(list, 1);

            assertEquals(2L, ListIntrinsics.$size$(result));
            assertNotSame(list, result);
        }

        @Test
        public void repeatsElements() {
            var list = pyList(1L, 2L);
            var result = ListIntrinsics.$repeat$(list, 3);

            assertEquals(6L, ListIntrinsics.$size$(result));
            assertEquals(1L, ListIntrinsics.$get$(result, 0));
            assertEquals(2L, ListIntrinsics.$get$(result, 5));
        }
    }

    @Nested
    public final class IAdd {
        @Test
        public void withAnotherList() {
            var list = new PyList<Object>(1L, 2L);
            var other = pyList(3L, 4L);
            var result = ListIntrinsics.$iadd$(list, other);

            assertSame(list, result);
            assertEquals(4L, ListIntrinsics.$size$(list));
        }

        @Test
        public void withTuple() {
            var list = new PyList<Object>(1L);
            PyTuple tuple = pyTuple(2L, 3L);
            ListIntrinsics.$iadd$(list, tuple);

            assertEquals(3L, ListIntrinsics.$size$(list));
        }

        @Test
        public void withString() {
            var list = new PyList<Object>("a");
            ListIntrinsics.$iadd$(list, "bc");

            assertEquals(3L, ListIntrinsics.$size$(list));
            assertEquals("b", ListIntrinsics.$get$(list, 1));
            assertEquals("c", ListIntrinsics.$get$(list, 2));
        }
    }

    @Nested
    public final class IMul {
        @Test
        public void repeatsInPlace() {
            var list = pyList(1L, 2L);
            var result = ListIntrinsics.$imul$(list, 3);

            assertSame(list, result);
            assertEquals(6L, ListIntrinsics.$size$(list));
        }

        @Test
        public void zeroOrNegativeClears() {
            var list = pyList(1L, 2L);
            ListIntrinsics.$imul$(list, 0);

            assertTrue(list.isEmpty());
        }
    }


    @Nested
    public final class Slicing {
        @Test
        public void readsForwardAndBackwardSlices() {
            var list = pyList(0L, 1L, 2L, 3L, 4L, 5L);

            assertArrayEquals(new Object[]{1L, 2L, 3L},
                ListIntrinsics.$slice$(list,
                    PySlice.of(1L, 4L, PyNone.INSTANCE)).toArray());

            assertArrayEquals(new Object[]{0L, 2L, 4L},
                ListIntrinsics.$slice$(list,
                    PySlice.of(PyNone.INSTANCE, PyNone.INSTANCE, 2L)).toArray());

            assertArrayEquals(new Object[]{5L, 3L, 1L},
                ListIntrinsics.$slice$(list,
                    PySlice.of(PyNone.INSTANCE, PyNone.INSTANCE, -2L)).toArray());

            assertArrayEquals(new Object[]{},
                ListIntrinsics.$slice$(list,
                    PySlice.of(3L, 3L, 1L)).toArray());
        }

        @Test
        public void hugeSliceBoundsAreClamped() {
            var list = pyList(0L, 1L, 2L, 3L, 4L, 5L);
            var huge = BigInteger.TEN.pow(100);

            assertArrayEquals(new Object[]{0L, 1L, 2L, 3L, 4L, 5L},
                ListIntrinsics.$slice$(list,
                    PySlice.of(huge.negate(), huge, 1L)).toArray());

            // A huge negative step selects the final element.
            assertArrayEquals(new Object[]{5L},
                ListIntrinsics.$slice$(list,
                    PySlice.of(PyNone.INSTANCE, PyNone.INSTANCE,
                        huge.negate())).toArray());
        }

        @Test
        public void assigningToAnExtendedSliceReplacesSelectedElements() {
            var list = pyList(0L, 1L, 2L, 3L, 4L, 5L);

            ListIntrinsics.$setSlice$(list,
                PySlice.of(PyNone.INSTANCE, PyNone.INSTANCE, 2L),
                pyList(10L, 20L, 30L));

            assertArrayEquals(
                new Object[]{10L, 1L, 20L, 3L, 30L, 5L},
                list.toArray());
        }

        @Test
        public void assigningTheListToItsOwnFullSliceIsSafe() {
            var list = pyList(1L, 2L, 3L);

            ListIntrinsics.$setSlice$(list,
                PySlice.of(PyNone.INSTANCE, PyNone.INSTANCE, PyNone.INSTANCE),
                list);

            assertArrayEquals(new Object[]{1L, 2L, 3L}, list.toArray());
        }

        @Test
        public void extendedSliceAssignmentRequiresExactLength() {
            var list = pyList(0L, 1L, 2L, 3L, 4L, 5L);
            var original = list.toArray();

            assertThrows(PyValueError.class, () ->
                ListIntrinsics.$setSlice$(list,
                    PySlice.of(PyNone.INSTANCE, PyNone.INSTANCE, 2L),
                    pyList(10L, 20L)));

            assertArrayEquals(original, list.toArray());
        }

        @Test
        public void deletesForwardAndBackwardExtendedSlices() {
            var forward = pyList(0L, 1L, 2L, 3L, 4L, 5L);
            ListIntrinsics.$deleteSlice$(forward,
                PySlice.of(PyNone.INSTANCE, PyNone.INSTANCE, 2L));

            assertArrayEquals(new Object[]{1L, 3L, 5L}, forward.toArray());

            var backward = pyList(0L, 1L, 2L, 3L, 4L, 5L);
            ListIntrinsics.$deleteSlice$(backward,
                PySlice.of(PyNone.INSTANCE, PyNone.INSTANCE, -2L));

            assertArrayEquals(new Object[]{0L, 2L, 4L}, backward.toArray());
        }

        @Test
        public void zeroStepIsRejectedWithoutModifyingTheList() {
            var list = pyList(0L, 1L, 2L);
            var original = list.toArray();

            assertThrows(PyValueError.class, () ->
                ListIntrinsics.$deleteSlice$(list,
                    PySlice.of(PyNone.INSTANCE, PyNone.INSTANCE, 0L)));

            assertArrayEquals(original, list.toArray());
        }
    }


    @Nested
    public final class Append {
        @Test
        public void appendsAtTheEndAndReturnsNone() {
            var list = pyList(1L);
            var result = _append_(list, 2L);

            assertSame(PyNone.INSTANCE, result);
            assertArrayEquals(new Object[]{1L, 2L}, list.toArray());
        }

        @Test
        public void appendToEmptyListAndGrow() {
            var list = pyList();
            for (var i = 0L; i < 1_000L; i++) {
                _append_(list, i);
            }
            assertEquals(1_000, list.size());
            assertEquals(0L, list.get(0));
            assertEquals(999L, list.get(999));
        }

        @Test
        public void duplicatesNoneAndNestedValuesAreKept() {
            var inner = pyList(1L);
            var list = pyList();
            _append_(list, PyNone.INSTANCE);
            _append_(list, PyNone.INSTANCE);
            _append_(list, inner);
            _append_(list, inner);

            assertEquals(4, list.size());
            assertSame(PyNone.INSTANCE, list.get(0));
            assertSame(PyNone.INSTANCE, list.get(1));
            assertSame(inner, list.get(2));
            assertSame(inner, list.get(3));
        }

        @Test
        public void aListCanBeAppendedToItself() {
            var list = pyList(1L);
            _append_(list, list);

            assertEquals(2, list.size());
            assertSame(list, list.get(1));
        }

        @Test
        public void javaNullIsRejectedAndLeavesTheListUnchanged() {
            var list = pyList(1L);

            assertThrows(NullPointerException.class, () -> _append_(list, null));
            assertThrows(NullPointerException.class, () -> _append_((PyList<Object>) null, 1L));
            assertArrayEquals(new Object[]{1L}, list.toArray());
        }
    }


    @Nested
    public final class Clear {
        @Test
        public void emptiesTheListAndReturnsNone() {
            var list = pyList(1L, 2L, 3L);
            var result = _clear_(list);

            assertSame(PyNone.INSTANCE, result);
            assertEquals(0, list.size());
            assertTrue(list.isEmpty());
        }

        @Test
        public void clearingAnEmptyListIsANoOp() {
            var list = pyList();

            assertSame(PyNone.INSTANCE, _clear_(list));
            assertSame(PyNone.INSTANCE, _clear_(list));
            assertTrue(list.isEmpty());
        }

        @Test
        public void theListIsUsableAfterClear() {
            var list = pyList(1L, 2L);
            _clear_(list);
            _append_(list, 9L);

            assertArrayEquals(new Object[]{9L}, list.toArray());
        }

        @Test
        public void clearingAListContainingItselfWorks() {
            var list = pyList();
            _append_(list, list);
            _clear_(list);

            assertTrue(list.isEmpty());
        }

        @Test
        public void nullListIsRejected() {
            assertThrows(NullPointerException.class, () -> _clear_(null));
        }
    }


    @Nested
    public final class Copy {
        @Test
        public void copyIsAnIndependentListWithTheSameElements() {
            var list = pyList(1L, "a", PyNone.INSTANCE);
            @SuppressWarnings("unchecked")
            var copy = (PyList<Object>) _copy_(list);

            assertNotSame(list, copy);
            assertArrayEquals(new Object[]{1L, "a", PyNone.INSTANCE}, copy.toArray());

            _append_(list, 2L);
            assertEquals(3, copy.size());

            _append_(copy, 3L);
            assertEquals(4, list.size());
            assertEquals(2L, list.get(3));
        }

        @Test
        public void copyIsShallow() {
            var inner = pyList(1L);
            var list = pyList(inner);
            var copy = (PyList<?>) _copy_(list);

            assertSame(inner, copy.get(0));
            _append_(inner, 2L);
            assertEquals(2, ((PyList<?>) copy.get(0)).size());
        }

        @Test
        public void copyOfAnEmptyListIsAnotherEmptyList() {
            var list = pyList();
            var copy = (PyList<?>) _copy_(list);

            assertNotSame(list, copy);
            assertTrue(copy.isEmpty());
        }

        @Test
        public void copyOfACopyIsYetAnotherList() {
            var list = pyList(1L, 2L);
            var first = (PyList<?>) _copy_(list);
            var second = (PyList<?>) _copy_(first);

            assertNotSame(first, second);
            assertArrayEquals(list.toArray(), second.toArray());
        }

        @Test
        public void copyOfAListContainingItselfKeepsTheReferenceToTheOriginal() {
            var list = pyList();
            _append_(list, list);
            var copy = (PyList<?>) _copy_(list);

            assertEquals(1, copy.size());
            assertSame(list, copy.get(0));
        }

        @Test
        public void nullListIsRejected() {
            assertThrows(NullPointerException.class, () -> _copy_(null));
        }
    }


    @Nested
    public final class Count {
        @Test
        public void countsEqualElements() {
            var list = pyList(1L, 2L, 1L, 3L, 1L);

            assertEquals(3L, _count_(list, 1L));
            assertEquals(1L, _count_(list, 2L));
            assertEquals(0L, _count_(list, 42L));
        }

        @Test
        public void emptyListCountsZero() {
            assertEquals(0L, _count_(pyList(), 1L));
        }

        @Test
        public void boolIntAndFloatAreEqualForCounting() {
            var ones = pyList(1L, true, 1.0, 2L, "1");
            assertEquals(3L, _count_(ones, 1L));
            assertEquals(3L, _count_(ones, true));
            assertEquals(3L, _count_(ones, 1.0));

            var zeros = pyList(false, 0L, -0.0, 0.0, 1L);
            assertEquals(4L, _count_(zeros, 0L));
            assertEquals(4L, _count_(zeros, false));
        }

        @Test
        public void stringsAreNotEqualToNumbers() {
            var list = pyList("1", 1L, "a");

            assertEquals(1L, _count_(list, 1L));
            assertEquals(1L, _count_(list, "1"));
            assertEquals(1L, _count_(list, "a"));
        }

        @Test
        public void noneIsCountedByEquality() {
            var list = pyList(PyNone.INSTANCE, 0L, PyNone.INSTANCE, false);

            assertEquals(2L, _count_(list, PyNone.INSTANCE));
            assertEquals(2L, _count_(list, 0L));
        }

        @Test
        public void nestedContainersCompareByValue() {
            var list = pyList(
                pyList(1L),
                pyList(1L),
                pyList(2L),
                pyTuple(1L, 2L),
                new PyTuple.PyTupleN(new Object[]{1L, 2L}),
                pyTuple(1L, 3L));

            assertEquals(2L, _count_(list, pyList(1L)));
            assertEquals(1L, _count_(list, pyList(2L)));
            assertEquals(2L, _count_(list, pyTuple(1L, 2L)));
            // a list is never equal to a tuple
            assertEquals(0L, _count_(list, pyTuple(1L)));
        }

        @Test
        public void nanIsCountedOnlyByIdentity() {
            Object nan = Double.NaN;
            var list = pyList(nan, nan, 1L);

            assertEquals(2L, _count_(list, nan));

            Object otherNaN = TypeIntrinsics.NaNGenerator.nextNaN();
            assertEquals(0L, _count_(list, otherNaN));
        }

        @Test
        public void countDoesNotModifyTheList() {
            var list = pyList(1L, 1L);
            _count_(list, 1L);

            assertArrayEquals(new Object[]{1L, 1L}, list.toArray());
        }

        @Test
        public void javaNullIsRejected() {
            var list = pyList(1L);

            assertThrows(NullPointerException.class, () -> _count_(list, null));
            assertThrows(NullPointerException.class, () -> _count_(null, 1L));
        }
    }


    @Nested
    public final class Extend {
        @Test
        public void extendWithAListAppendsInOrderAndReturnsNone() {
            var list = pyList(1L);
            var result = _extend_(list, pyList(2L, 3L));

            assertSame(PyNone.INSTANCE, result);
            assertArrayEquals(new Object[]{1L, 2L, 3L}, list.toArray());
        }

        @Test
        public void extendWithATuple() {
            var list = pyList(1L);
            _extend_(list, pyTuple(2L, "a", PyNone.INSTANCE));

            assertArrayEquals(new Object[]{1L, 2L, "a", PyNone.INSTANCE}, list.toArray());
        }

        @Test
        public void extendWithAStringAppendsCodePoints() {
            var list = pyList();
            _extend_(list, "a😀b");

            assertArrayEquals(new Object[]{"a", "😀", "b"}, list.toArray());
        }

        @Test
        public void extendWithADictAppendsKeysInInsertionOrder() {
            var dict = new PyDict<>();
            dict.put("b", 1L);
            dict.put("a", 2L);
            dict.put(3L, 3L);

            var list = pyList();
            _extend_(list, dict);

            assertArrayEquals(new Object[]{"b", "a", 3L}, list.toArray());
        }

        @Test
        public void extendWithARange() {
            var list = pyList(-1L);
            _extend_(list, RangeIntrinsics.newRange(0L, 4L, 1L));
            assertArrayEquals(new Object[]{-1L, 0L, 1L, 2L, 3L}, list.toArray());

            var reversed = pyList();
            _extend_(reversed, RangeIntrinsics.newRange(3L, 0L, -1L));
            assertArrayEquals(new Object[]{3L, 2L, 1L}, reversed.toArray());
        }

        @Test
        public void extendWithEmptyIterablesDoesNothing() {
            var list = pyList(1L);
            _extend_(list, pyList());
            _extend_(list, pyTuple());
            _extend_(list, "");
            _extend_(list, RangeIntrinsics.newRange(0L, 0L, 1L));
            _extend_(list, new PyDict<>());

            assertArrayEquals(new Object[]{1L}, list.toArray());
        }

        @Test
        public void extendWithItselfDoublesTheList() {
            var list = pyList(1L, 2L);
            _extend_(list, list);

            assertArrayEquals(new Object[]{1L, 2L, 1L, 2L}, list.toArray());
        }

        @Test
        public void extendKeepsDuplicatesAndShallowReferences() {
            var inner = pyList(1L);
            var list = pyList(inner);
            _extend_(list, list);

            assertEquals(2, list.size());
            assertSame(inner, list.get(0));
            assertSame(inner, list.get(1));
        }

        @Test
        public void nonIterableValuesRaiseTypeError() {
            var list = pyList(1L);

            assertThrows(PyTypeError.class, () -> _extend_(list, 42L));
            assertThrows(PyTypeError.class, () -> _extend_(list, 1.5));
            assertThrows(PyTypeError.class, () -> _extend_(list, true));
            assertThrows(PyTypeError.class, () -> _extend_(list, PyNone.INSTANCE));
            assertArrayEquals(new Object[]{1L}, list.toArray());
        }

        @Test
        public void javaNullIsRejected() {
            var list = pyList(1L);

            assertThrows(NullPointerException.class, () -> _extend_(list, null));
            assertThrows(NullPointerException.class, () -> _extend_(null, pyList(1L)));
        }
    }


    @Nested
    public final class Index {
        private PyList<Object> sample() {
            return pyList(1L, 2L, 3L, 2L, 1L);
        }

        @Test
        public void findsTheFirstOccurrence() {
            var list = sample();

            assertEquals(0L, _index_(list, 1L));
            assertEquals(1L, _index_(list, 2L));
            assertEquals(2L, _index_(list, 3L));
        }

        @Test
        public void startRestrictsTheSearch() {
            var list = sample();

            assertEquals(1L, _index_(list, 2L, 1L));
            assertEquals(3L, _index_(list, 2L, 2L));
            assertEquals(4L, _index_(list, 1L, 1L));
        }

        @Test
        public void startAndStopRestrictTheSearchToAHalfOpenRange() {
            var list = sample();

            assertEquals(3L, _index_(list, 2L, 2L, 4L));
            assertEquals(4L, _index_(list, 1L, 1L, 5L));
            assertEquals(1L, _index_(list, 2L, 1L, 2L));
            // stop is exclusive: [2, 3) only holds the 3
            assertThrows(PyValueError.class, () -> _index_(list, 2L, 2L, 3L));
        }

        @Test
        public void negativeBoundsCountFromTheEnd() {
            var list = sample();

            assertEquals(3L, _index_(list, 2L, -3L));
            assertEquals(4L, _index_(list, 1L, -1L));
            assertEquals(0L, _index_(list, 1L, 0L, -1L));
            // [1, 4) holds 2, 3, 2 only
            assertThrows(PyValueError.class, () -> _index_(list, 1L, 1L, -1L));
        }

        @Test
        public void boundsAreClampedLikeSliceBounds() {
            var list = sample();

            assertEquals(0L, _index_(list, 1L, -100L));
            assertEquals(4L, _index_(list, 1L, 4L, 100L));
            assertEquals(4L, _index_(list, 1L, 4L, Long.MAX_VALUE));
            assertEquals(0L, _index_(list, 1L, Long.MIN_VALUE, Long.MAX_VALUE));
            assertThrows(PyValueError.class, () -> _index_(list, 1L, 100L));
            assertThrows(PyValueError.class, () -> _index_(list, 1L, 5L));
            assertThrows(PyValueError.class, () -> _index_(list, 1L, 0L, -100L));
        }

        @Test
        public void emptyOrInvertedRangesFindNothing() {
            var list = sample();

            assertThrows(PyValueError.class, () -> _index_(list, 1L, 2L, 2L));
            assertThrows(PyValueError.class, () -> _index_(list, 1L, 3L, 1L));
            assertThrows(PyValueError.class, () -> _index_(pyList(), 1L));
        }

        @Test
        public void boolBoundsAreAcceptedAsIntegers() {
            var list = sample();

            assertEquals(1L, _index_(list, 2L, true));
            assertEquals(4L, _index_(list, 1L, true));
            assertEquals(0L, _index_(list, 1L, false, true));
        }

        @Test
        public void nonIntegerBoundsRaiseTypeError() {
            var list = sample();

            assertThrows(PyTypeError.class, () -> _index_(list, 1L, "1"));
            assertThrows(PyTypeError.class, () -> _index_(list, 1L, 1.0));
            assertThrows(PyTypeError.class, () -> _index_(list, 1L, 0L, PyNone.INSTANCE));
            assertThrows(PyTypeError.class, () -> _index_(list, 1L, 0L, 2.0));
        }

        @Test
        public void numericEqualityAcrossRepresentations() {
            var list = pyList(0L, 1L, 2L);

            assertEquals(1L, _index_(list, 1.0));
            assertEquals(1L, _index_(list, true));
            assertEquals(0L, _index_(list, false));
            assertEquals(0L, _index_(list, -0.0));
        }

        @Test
        public void stringsAreNotEqualToNumbers() {
            assertEquals(1L, _index_(pyList("1", 1L), 1L));
        }

        @Test
        public void nestedContainersCompareByValue() {
            var list = pyList(pyList(1L), pyTuple(1L), pyList(2L));

            assertEquals(0L, _index_(list, pyList(1L)));
            assertEquals(1L, _index_(list, pyTuple(1L)));
            assertEquals(2L, _index_(list, pyList(2L)));
        }

        @Test
        public void nanIsFoundOnlyByIdentity() {
            Object nan = Double.NaN;
            var list = pyList(1L, nan);

            assertEquals(1L, _index_(list, nan));
            Object otherNaN = TypeIntrinsics.NaNGenerator.nextNaN();
            assertThrows(PyValueError.class, () -> _index_(list, otherNaN));
        }

        @Test
        public void notFoundMessageContainsTheRepr() {
            var list = pyList(1L);

            assertEquals("5 is not in list",
                assertThrows(PyValueError.class, () -> _index_(list, 5L)).getMessage());
            assertEquals("'a' is not in list",
                assertThrows(PyValueError.class, () -> _index_(list, "a")).getMessage());
        }

        @Test
        public void indexDoesNotModifyTheList() {
            var list = sample();
            _index_(list, 3L);

            assertArrayEquals(new Object[]{1L, 2L, 3L, 2L, 1L}, list.toArray());
        }

        @Test
        public void javaNullIsRejected() {
            var list = sample();

            assertThrows(NullPointerException.class, () -> _index_(list, null));
            assertThrows(NullPointerException.class, () -> _index_(null, 1L));
            assertThrows(NullPointerException.class, () -> _index_(list, 1L, null));
            assertThrows(NullPointerException.class, () -> _index_(null, 1L, 0L));
        }
    }


    @Nested
    public final class Insert {
        @Test
        public void insertsBeforeTheGivenIndexAndReturnsNone() {
            var list = pyList(1L, 2L, 3L);
            var result = _insert_(list, 1L, "x");

            assertSame(PyNone.INSTANCE, result);
            assertArrayEquals(new Object[]{1L, "x", 2L, 3L}, list.toArray());
        }

        @Test
        public void insertAtZeroPrepends() {
            var list = pyList(1L, 2L);
            _insert_(list, 0L, "x");

            assertArrayEquals(new Object[]{"x", 1L, 2L}, list.toArray());
        }

        @Test
        public void indexAtOrBeyondTheSizeAppends() {
            var list = pyList(1L, 2L, 3L);
            _insert_(list, 3L, "a");
            _insert_(list, 4L, "b");
            _insert_(list, 100L, "c");
            _insert_(list, Long.MAX_VALUE, "d");

            assertArrayEquals(new Object[]{1L, 2L, 3L, "a", "b", "c", "d"}, list.toArray());
        }

        @Test
        public void negativeIndexCountsFromTheEnd() {
            var list = pyList(1L, 2L, 3L);
            _insert_(list, -1L, "x");
            assertArrayEquals(new Object[]{1L, 2L, "x", 3L}, list.toArray());

            var second = pyList(1L, 2L, 3L);
            _insert_(second, -3L, "x");
            assertArrayEquals(new Object[]{"x", 1L, 2L, 3L}, second.toArray());
        }

        @Test
        public void tooNegativeIndexPrepends() {
            var list = pyList(1L, 2L, 3L);
            _insert_(list, -4L, "a");
            _insert_(list, -100L, "b");
            _insert_(list, Long.MIN_VALUE, "c");

            assertArrayEquals(new Object[]{"c", "b", "a", 1L, 2L, 3L}, list.toArray());
        }

        @Test
        public void insertIntoAnEmptyListWhateverTheIndex() {
            for (var index : new long[]{0L, 1L, -1L, 100L, Long.MIN_VALUE, Long.MAX_VALUE}) {
                var list = pyList();
                _insert_(list, index, "x");

                assertArrayEquals(new Object[]{"x"}, list.toArray(), "index " + index);
            }
        }

        @Test
        public void boolIndexIsAcceptedAsInteger() {
            var list = pyList(1L, 2L);
            _insert_(list, true, "t");
            _insert_(list, false, "f");

            assertArrayEquals(new Object[]{"f", 1L, "t", 2L}, list.toArray());
        }

        @Test
        public void repeatedInsertAtZeroReversesTheInsertionOrder() {
            var list = pyList();
            for (var i = 0L; i < 5L; i++) {
                _insert_(list, 0L, i);
            }

            assertArrayEquals(new Object[]{4L, 3L, 2L, 1L, 0L}, list.toArray());
        }

        @Test
        public void noneCanBeInserted() {
            var list = pyList(1L);
            _insert_(list, 0L, PyNone.INSTANCE);

            assertSame(PyNone.INSTANCE, list.get(0));
        }

        @Test
        public void nonIntegerIndexRaisesTypeErrorAndLeavesTheListUnchanged() {
            var list = pyList(1L);

            assertThrows(PyTypeError.class, () -> _insert_(list, "0", 2L));
            assertThrows(PyTypeError.class, () -> _insert_(list, 0.0, 2L));
            assertThrows(PyTypeError.class, () -> _insert_(list, PyNone.INSTANCE, 2L));
            assertArrayEquals(new Object[]{1L}, list.toArray());
        }

        @Test
        public void javaNullIsRejected() {
            var list = pyList(1L);

            assertThrows(NullPointerException.class, () -> _insert_(list, 0L, null));
            assertThrows(NullPointerException.class, () -> _insert_(list, null, 1L));
            assertThrows(NullPointerException.class, () -> _insert_(null, 0L, 1L));
            assertArrayEquals(new Object[]{1L}, list.toArray());
        }
    }


    @Nested
    public final class Pop {
        @Test
        public void popWithoutArgumentRemovesAndReturnsTheLastElement() {
            var list = pyList(10L, 20L, 30L);

            assertEquals(30L, _pop_(list));
            assertArrayEquals(new Object[]{10L, 20L}, list.toArray());
        }

        @Test
        public void popWithIndex() {
            var list = pyList(10L, 20L, 30L, 40L);

            assertEquals(10L, _pop_(list, 0L));
            assertArrayEquals(new Object[]{20L, 30L, 40L}, list.toArray());
            assertEquals(30L, _pop_(list, 1L));
            assertArrayEquals(new Object[]{20L, 40L}, list.toArray());
        }

        @Test
        public void popWithNegativeIndex() {
            var list = pyList(10L, 20L, 30L);

            assertEquals(30L, _pop_(list, -1L));
            assertEquals(10L, _pop_(list, -2L));
            assertArrayEquals(new Object[]{20L}, list.toArray());
        }

        @Test
        public void popTheWholeRangeOfValidIndexes() {
            assertEquals(10L, _pop_(pyList(10L, 20L, 30L), 0L));
            assertEquals(30L, _pop_(pyList(10L, 20L, 30L), 2L));
            assertEquals(10L, _pop_(pyList(10L, 20L, 30L), -3L));
        }

        @Test
        public void popUntilEmpty() {
            var list = pyList(1L, 2L, 3L);

            assertEquals(3L, _pop_(list));
            assertEquals(2L, _pop_(list));
            assertEquals(1L, _pop_(list));
            assertTrue(list.isEmpty());
            assertThrows(PyIndexError.class, () -> _pop_(list));
        }

        @Test
        public void popReturnsTheStoredObject() {
            var inner = pyList(1L);
            var list = pyList(inner, PyNone.INSTANCE);

            assertSame(PyNone.INSTANCE, _pop_(list));
            assertSame(inner, _pop_(list));
        }

        @Test
        public void popFromAnEmptyListRaisesIndexError() {
            var list = pyList();

            assertEquals("pop from empty list",
                assertThrows(PyIndexError.class, () -> _pop_(list)).getMessage());
            // the emptiness is detected before the index is looked at
            assertEquals("pop from empty list",
                assertThrows(PyIndexError.class, () -> _pop_(list, 0L)).getMessage());
            assertEquals("pop from empty list",
                assertThrows(PyIndexError.class, () -> _pop_(list, 5L)).getMessage());
            assertEquals("pop from empty list",
                assertThrows(PyIndexError.class, () -> _pop_(list, -1L)).getMessage());
        }

        @Test
        public void outOfRangeIndexRaisesIndexErrorAndLeavesTheListUnchanged() {
            var list = pyList(10L, 20L, 30L);

            assertEquals("pop index out of range",
                assertThrows(PyIndexError.class, () -> _pop_(list, 3L)).getMessage());
            assertEquals("pop index out of range",
                assertThrows(PyIndexError.class, () -> _pop_(list, -4L)).getMessage());
            assertThrows(PyIndexError.class, () -> _pop_(list, Long.MAX_VALUE));
            assertThrows(PyIndexError.class, () -> _pop_(list, Long.MIN_VALUE));
            assertArrayEquals(new Object[]{10L, 20L, 30L}, list.toArray());
        }

        @Test
        public void boolIndexIsAcceptedAsInteger() {
            var list = pyList(10L, 20L, 30L);

            assertEquals(20L, _pop_(list, true));
            assertEquals(10L, _pop_(list, false));
            assertArrayEquals(new Object[]{30L}, list.toArray());
        }

        @Test
        public void nonIntegerIndexRaisesTypeError() {
            var list = pyList(10L);

            assertThrows(PyTypeError.class, () -> _pop_(list, "0"));
            assertThrows(PyTypeError.class, () -> _pop_(list, 0.0));
            assertThrows(PyTypeError.class, () -> _pop_(list, PyNone.INSTANCE));
            assertArrayEquals(new Object[]{10L}, list.toArray());
        }

        @Test
        public void javaNullIsRejected() {
            var list = pyList(1L);

            assertThrows(NullPointerException.class, () -> _pop_(null));
            assertThrows(NullPointerException.class, () -> _pop_(null, 0L));
            assertThrows(NullPointerException.class, () -> _pop_(list, null));
            assertArrayEquals(new Object[]{1L}, list.toArray());
        }
    }


    @Nested
    public final class Remove {
        @Test
        public void removesTheFirstOccurrenceOnlyAndReturnsNone() {
            var list = pyList(1L, 2L, 3L, 2L);
            var result = _remove_(list, 2L);

            assertSame(PyNone.INSTANCE, result);
            assertArrayEquals(new Object[]{1L, 3L, 2L}, list.toArray());
        }

        @Test
        public void removeFirstAndLastElements() {
            var list = pyList(1L, 2L, 3L);
            _remove_(list, 1L);
            _remove_(list, 3L);

            assertArrayEquals(new Object[]{2L}, list.toArray());
        }

        @Test
        public void removingTheOnlyElementEmptiesTheList() {
            var list = pyList(1L);
            _remove_(list, 1L);

            assertTrue(list.isEmpty());
        }

        @Test
        public void missingValueRaisesValueErrorAndLeavesTheListUnchanged() {
            var list = pyList(1L, 2L);

            assertThrows(PyValueError.class, () -> _remove_(list, 3L));
            assertThrows(PyValueError.class, () -> _remove_(list, "1"));
            assertArrayEquals(new Object[]{1L, 2L}, list.toArray());
        }

        @Test
        public void removeFromAnEmptyListRaisesValueError() {
            assertThrows(PyValueError.class, () -> _remove_(pyList(), 1L));
        }

        @Test
        public void removeUsesPythonEquality() {
            var list = pyList(0L, 1L, 1.0, true);
            _remove_(list, true);   // equals the first 1
            assertArrayEquals(new Object[]{0L, 1.0, true}, list.toArray());

            _remove_(list, 1L);     // equals 1.0
            assertArrayEquals(new Object[]{0L, true}, list.toArray());

            _remove_(list, false);  // equals 0
            assertArrayEquals(new Object[]{true}, list.toArray());
        }

        @Test
        public void removeNone() {
            var list = pyList(0L, PyNone.INSTANCE, PyNone.INSTANCE);
            _remove_(list, PyNone.INSTANCE);

            assertArrayEquals(new Object[]{0L, PyNone.INSTANCE}, list.toArray());
        }

        @Test
        public void removeNestedContainersByValue() {
            var list = pyList(pyList(1L), pyList(2L), pyList(1L));
            _remove_(list, pyList(2L));

            assertEquals(2, list.size());
            assertArrayEquals(new Object[]{pyList(1L), pyList(1L)}, list.toArray());
        }

        @Test
        public void nanIsRemovedOnlyByIdentity() {
            Object nan = Double.NaN;
            var list = pyList(nan);

            Object otherNaN = TypeIntrinsics.NaNGenerator.nextNaN();
            assertThrows(PyValueError.class, () -> _remove_(list, otherNaN));
            assertEquals(1, list.size());

            _remove_(list, nan);
            assertTrue(list.isEmpty());
        }

        @Test
        public void javaNullIsRejected() {
            var list = pyList(1L);

            assertThrows(NullPointerException.class, () -> _remove_(list, null));
            assertThrows(NullPointerException.class, () -> _remove_(null, 1L));
        }
    }


    @Nested
    public final class Reverse {
        @Test
        public void reversesAnOddNumberOfElementsAndReturnsNone() {
            var list = pyList(1L, 2L, 3L);
            var result = _reverse_(list);

            assertSame(PyNone.INSTANCE, result);
            assertArrayEquals(new Object[]{3L, 2L, 1L}, list.toArray());
        }

        @Test
        public void reversesAnEvenNumberOfElements() {
            var list = pyList(1L, 2L, 3L, 4L);
            _reverse_(list);

            assertArrayEquals(new Object[]{4L, 3L, 2L, 1L}, list.toArray());
        }

        @Test
        public void emptyAndSingleElementListsAreUnchanged() {
            var empty = pyList();
            assertSame(PyNone.INSTANCE, _reverse_(empty));
            assertTrue(empty.isEmpty());

            var single = pyList(1L);
            assertSame(PyNone.INSTANCE, _reverse_(single));
            assertArrayEquals(new Object[]{1L}, single.toArray());
        }

        @Test
        public void twoElements() {
            var list = pyList("a", "b");
            _reverse_(list);

            assertArrayEquals(new Object[]{"b", "a"}, list.toArray());
        }

        @Test
        public void reversingTwiceRestoresTheOriginalOrder() {
            var list = pyList(1L, 2L, 3L, 4L, 5L);
            _reverse_(list);
            _reverse_(list);

            assertArrayEquals(new Object[]{1L, 2L, 3L, 4L, 5L}, list.toArray());
        }

        @Test
        public void elementsAreMovedNotCopied() {
            var first = pyList(1L);
            var last = pyList(2L);
            var list = pyList(first, PyNone.INSTANCE, last);
            _reverse_(list);

            assertSame(last, list.get(0));
            assertSame(PyNone.INSTANCE, list.get(1));
            assertSame(first, list.get(2));
        }

        @Test
        public void reversesALargeList() {
            var list = pyList();
            for (var i = 0L; i < 1_001L; i++) {
                _append_(list, i);
            }
            _reverse_(list);

            assertEquals(1_001, list.size());
            for (var i = 0; i < 1_001; i++) {
                assertEquals(1_000L - i, list.get(i));
            }
        }

        @Test
        public void reverseOfAListContainingItselfKeepsTheReference() {
            var list = pyList(1L);
            _append_(list, list);
            _reverse_(list);

            assertSame(list, list.get(0));
            assertEquals(1L, list.get(1));
        }

        @Test
        public void nullListIsRejected() {
            assertThrows(NullPointerException.class, () -> _reverse_(null));
        }
    }


    @Nested
    public final class Sort {
        @Test
        public void sortsIntegersAndReturnsNone() {
            var list = pyList(3L, 1L, 2L, -5L, 0L);
            var result = _sort_(list);

            assertSame(PyNone.INSTANCE, result);
            assertArrayEquals(new Object[]{-5L, 0L, 1L, 2L, 3L}, list.toArray());
        }

        @Test
        public void alreadySortedAndReverseSortedInputs() {
            var sorted = pyList(1L, 2L, 3L);
            _sort_(sorted);
            assertArrayEquals(new Object[]{1L, 2L, 3L}, sorted.toArray());

            var reversed = pyList(3L, 2L, 1L);
            _sort_(reversed);
            assertArrayEquals(new Object[]{1L, 2L, 3L}, reversed.toArray());
        }

        @Test
        public void emptyAndSingleElementListsAreAccepted() {
            var empty = pyList();
            assertSame(PyNone.INSTANCE, _sort_(empty));
            assertTrue(empty.isEmpty());

            var single = pyList(1L);
            _sort_(single);
            assertArrayEquals(new Object[]{1L}, single.toArray());
        }

        @Test
        public void aSingleElementIsNeverComparedSoItNeedsNoOrdering() {
            var list = pyList(PyNone.INSTANCE);
            _sort_(list);

            assertSame(PyNone.INSTANCE, list.get(0));
        }

        @Test
        public void sortsDuplicates() {
            var list = pyList(2L, 1L, 2L, 1L, 3L, 3L);
            _sort_(list);

            assertArrayEquals(new Object[]{1L, 1L, 2L, 2L, 3L, 3L}, list.toArray());
        }

        @Test
        public void sortsMixedNumericTypesExactly() {
            var list = pyList(3L, BigInteger.TEN.pow(20), true, Long.MIN_VALUE, 2.5, -1L);
            _sort_(list);

            assertArrayEquals(
                new Object[]{Long.MIN_VALUE, -1L, true, 2.5, 3L, BigInteger.TEN.pow(20)},
                list.toArray());
        }

        @Test
        public void sortIsStableForEqualElements() {
            var list = pyList(1.0, 1L, true, 0L, true, 1L, 1.0);
            _sort_(list);

            assertArrayEquals(
                new Object[]{0L, 1.0, 1L, true, true, 1L, 1.0},
                list.toArray());
        }

        @Test
        public void sortsStringsByCodePoint() {
            var list = pyList("b", "\uD83D\uDE00", "a", "\uFF5E", "", "ab");
            _sort_(list);

            // U+FF5E < U+1F600, even though \uD83D < \uFF5E as UTF-16 code units
            assertArrayEquals(
                new Object[]{"", "a", "ab", "b", "\uFF5E", "\uD83D\uDE00"},
                list.toArray());
        }

        @Test
        public void sortsTuplesLexicographically() {
            var list = pyList(pyTuple(2L, 1L), pyTuple(1L, 5L), pyTuple(1L), pyTuple(1L, 2L));
            _sort_(list);

            assertArrayEquals(
                new Object[]{pyTuple(1L), pyTuple(1L, 2L), pyTuple(1L, 5L), pyTuple(2L, 1L)},
                list.toArray());
        }

        @Test
        public void sortsListsLexicographically() {
            var list = pyList(pyList(2L), pyList(1L, 5L), pyList(1L));
            _sort_(list);

            assertArrayEquals(
                new Object[]{pyList(1L), pyList(1L, 5L), pyList(2L)},
                list.toArray());
        }

        @Test
        public void sortsALargeRandomListLikeJava() {
            var random = new Random(42);
            var values = new long[500];
            var list = pyList();
            for (var i = 0; i < values.length; i++) {
                values[i] = random.nextLong();
                _append_(list, values[i]);
            }
            Arrays.sort(values);
            _sort_(list);

            assertEquals(values.length, list.size());
            for (var i = 0; i < values.length; i++) {
                assertEquals(values[i], list.get(i), "index " + i);
            }
        }

        @Test
        public void sortsALargeListWithManyDuplicates() {
            var random = new Random(7);
            var values = new long[300];
            var list = pyList();
            for (var i = 0; i < values.length; i++) {
                values[i] = random.nextInt(10);
                _append_(list, values[i]);
            }
            Arrays.sort(values);
            _sort_(list);

            for (var i = 0; i < values.length; i++) {
                assertEquals(values[i], list.get(i), "index " + i);
            }
        }

        @Test
        public void incomparableElementsRaiseTypeError() {
            var e = assertThrows(PyTypeError.class, () -> _sort_(pyList(1L, "a")));
            assertTrue(e.getMessage().contains("not supported between instances of"), e.getMessage());

            assertThrows(PyTypeError.class, () -> _sort_(pyList("a", 1L)));
            assertThrows(PyTypeError.class, () -> _sort_(pyList(PyNone.INSTANCE, PyNone.INSTANCE)));
            assertThrows(PyTypeError.class, () -> _sort_(pyList(pyList(1L), pyTuple(1L))));
        }

        @Test
        public void nullListIsRejected() {
            assertThrows(NullPointerException.class, () -> _sort_(null));
        }
    }


    // ------------------------------------------------------------------ end to end

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
                xs = [3, 1, 2]
                xs.append(0)
                xs.extend((9, 8))
                xs.insert(0, 7)
                xs.sort()
                first = xs.pop(0)
                last = xs.pop()
                idx = xs.index(2)
                idx_from = xs.index(7, 2)
                idx_range = xs.index(7, 2, 5)
                cnt = xs.count(1)
                xs.remove(3)
                xs.reverse()
                ys = xs.copy()
                xs.clear()
                """);

            assertEquals(0L, context.getGlobal("first"));
            assertEquals(9L, context.getGlobal("last"));
            assertEquals(1L, context.getGlobal("idx"));
            assertEquals(3L, context.getGlobal("idx_from"));
            assertEquals(3L, context.getGlobal("idx_range"));
            assertEquals(1L, context.getGlobal("cnt"));
            assertTrue(((PyList<?>) context.getGlobal("xs")).isEmpty());
            assertArrayEquals(new Object[]{8L, 7L, 2L, 1L},
                ((PyList<?>) context.getGlobal("ys")).toArray());
        }

        @Test
        public void errorsPropagateToTheCaller() {
            assertThrows(PyIndexError.class, () -> run("xs = []\nxs.pop()"));
            assertThrows(PyValueError.class, () -> run("xs = [1]\nxs.remove(2)"));
            assertThrows(PyValueError.class, () -> run("xs = [1]\nxs.index(2)"));
            assertThrows(PyTypeError.class, () -> run("xs = [1, 'a']\nxs.sort()"));
        }
    }
}