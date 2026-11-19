package com.github.forax.orvet.rt.intrinsic;

import com.github.forax.orvet.rt.PyDict;
import com.github.forax.orvet.rt.PyList;
import com.github.forax.orvet.rt.PyNone;
import com.github.forax.orvet.rt.PyRange;
import com.github.forax.orvet.rt.PyType;
import com.github.forax.orvet.rt.error.PyKeyError;
import com.github.forax.orvet.rt.error.PyTypeError;
import com.github.forax.orvet.rt.error.PyValueError;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public final class DictIntrinsicsTest {

  private static PyDict<Object, Object> dictOf(Object... keysAndValues) {
    if (keysAndValues.length % 2 == 1) {
      throw new IllegalArgumentException("odd number of keys and values");
    }
    var dict = new PyDict<>();
    for (var i = 0; i < keysAndValues.length; i += 2) {
      dict.put(keysAndValues[i], keysAndValues[i + 1]);
    }
    return dict;
  }

  private static <K> List<K> keysOf(PyDict<? extends K,?> dict) {
    var keys = new ArrayList<K>();
    for (var i = 0; i < dict.entryCount(); i++) {
      if (dict.isLive(i)) {
        keys.add(dict.keyAt(i));
      }
    }
    return keys;
  }

  private static <V> List<V> valuesOf(PyDict<?,? extends V> dict) {
    var values = new ArrayList<V>();
    for (var i = 0; i < dict.entryCount(); i++) {
      if (dict.isLive(i)) {
        values.add(dict.valueAt(i));
      }
    }
    return values;
  }


  @Nested
  public final class Size {
    @Test
    public void newDictIsEmpty() {
      var dict = DictIntrinsics.newDict();

      assertEquals(0L, DictIntrinsics.$size$(dict));
    }

    @Test
    public void sizeReflectsInsertions() {
      var dict = DictIntrinsics.newDict();
      dict.put(1L, "one");
      dict.put("a", 2L);

      assertEquals(2L, DictIntrinsics.$size$(dict));
    }
  }

  @Nested
  public final class Get {
    @Test
    public void existingKey() {
      var dict = new PyDict<>();
      dict.put(1L, "one");

      assertEquals("one", DictIntrinsics.$get$(dict, 1L));
    }

    @Test
    public void missingKeyRaisesKeyError() {
      var dict = new PyDict<>();
      dict.put(1L, "hello");

      assertThrows(PyKeyError.class, () -> DictIntrinsics.$get$(dict, 2L));
    }
  }

  @Nested
  public final class Set {
    @Test
    public void insertsNewKey() {
      var dict = DictIntrinsics.newDict();
      var result = DictIntrinsics.$set$(dict, 1L, "one");

      assertSame(PyNone.INSTANCE, result);
      assertEquals("one", DictIntrinsics.$get$(dict, 1L));
    }

    @Test
    public void replacesExistingKey() {
      var dict = DictIntrinsics.newDict();
      dict.put(1L, "one");

      DictIntrinsics.$set$(dict, 1L, "uno");
      assertEquals("uno", DictIntrinsics.$get$(dict, 1L));
    }
  }

  @Nested
  public final class Delete {
    @Test
    public void removesExistingKey() {
      var dict = new PyDict<>();
      dict.put(1L, "one");

      var result = DictIntrinsics.$delete$(dict, 1L);
      assertSame(PyNone.INSTANCE, result);
      assertFalse(DictIntrinsics.$in$(dict, 1L));
    }

    @Test
    public void missingKeyRaisesKeyError() {
      var dict = new PyDict<>();
      dict.put(1L, "one");

      assertThrows(PyKeyError.class, () -> DictIntrinsics.$delete$(dict, 2L));
    }
  }

  @Test
  public void containsKey() {
    var dict = DictIntrinsics.newDict();
    dict.put(1L, "one");

    assertTrue(DictIntrinsics.$in$(dict, 1L));
    assertFalse(DictIntrinsics.$in$(dict, 2L));
  }

  @Nested
  public final class Equals {
    @Test
    public void sameInstance() {
      var dict = DictIntrinsics.newDict();

      assertTrue(DictIntrinsics.equals(dict, dict));
    }

    @Test
    public void differentSizes() {
      var left = DictIntrinsics.newDict();
      left.put(1L, "one");
      var right = DictIntrinsics.newDict();

      assertFalse(DictIntrinsics.equals(left, right));
    }

    @Test
    public void sameContent() {
      var left = DictIntrinsics.newDict();
      left.put(1L, "one");
      left.put("a", 2L);
      var right = DictIntrinsics.newDict();
      right.put("a", 2L);
      right.put(1L, "one");

      assertTrue(DictIntrinsics.equals(left, right));
    }

    @Test
    public void differentValues() {
      var left = DictIntrinsics.newDict();
      left.put(1L, "one");

      var right = DictIntrinsics.newDict();
      right.put(1L, "uno");

      assertFalse(DictIntrinsics.equals(left, right));
    }
  }


  @Nested
  public final class ClearMethod {
    @Test
    public void removesEveryEntryAndReturnsNone() {
      var dict = dictOf("a", 1L, "b", 2L);

      var result = DictIntrinsics._clear_(dict);

      assertSame(PyNone.INSTANCE, result);
      assertEquals(0, dict.size());
      assertTrue(dict.isEmpty());
      assertFalse(dict.containsKey("a"));
      assertFalse(dict.containsKey("b"));
    }

    @Test
    public void clearingAnEmptyDictIsANoOp() {
      var dict = dictOf();
      var version = dict.layoutVersion();

      assertSame(PyNone.INSTANCE, DictIntrinsics._clear_(dict));

      assertTrue(dict.isEmpty());
      assertEquals(version, dict.layoutVersion());
    }

    @Test
    public void clearingANonEmptyDictChangesTheLayoutVersion() {
      var dict = dictOf("a", 1L);
      var version = dict.layoutVersion();

      DictIntrinsics._clear_(dict);

      assertTrue(dict.layoutVersion() > version);
    }

    @Test
    public void dictCanBeReusedAfterClear() {
      var dict = dictOf("a", 1L, "b", 2L);

      DictIntrinsics._clear_(dict);
      dict.put("c", 3L);
      dict.put("a", 4L);

      assertEquals(dictOf("c", 3L, "a", 4L), dict);
      assertEquals(List.of("c", "a"), keysOf(dict));
      assertEquals(List.of(3L, 4L), valuesOf(dict));
    }

    @Test
    public void clearingTwiceIsFine() {
      var dict = dictOf("a", 1L);

      DictIntrinsics._clear_(dict);
      DictIntrinsics._clear_(dict);

      assertTrue(dict.isEmpty());
    }

    @Test
    public void clearingALargeDictionary() {
      var dict = dictOf();
      for (var i = 0L; i < 1_000L; i++) {
        dict.put(i, i);
      }

      DictIntrinsics._clear_(dict);

      assertEquals(0, dict.size());
      assertEquals(0, dict.entryCount());
    }

    @Test
    public void nullDictIsRejected() {
      assertThrows(NullPointerException.class, () -> DictIntrinsics._clear_(null));
    }
  }


  @Nested
  public final class CopyMethod {
    @Test
    public void copyHasTheSameEntriesInTheSameOrder() {
      var dict = dictOf("z", 1L, "a", 2L, "m", 3L);

      var copy = (PyDict<?, ?>) DictIntrinsics._copy_(dict);

      assertEquals(dictOf("z", 1L, "a", 2L, "m", 3L), copy);
      assertEquals(List.of("z", "a", "m"), keysOf(copy));
      assertEquals(List.of(1L, 2L, 3L), valuesOf(copy));
      assertTrue(DictIntrinsics.equals(dict, copy));
    }

    @Test
    public void copyIsANewIndependentDictionary() {
      var dict = dictOf("a", 1L);

      @SuppressWarnings("unchecked")
      var copy = (PyDict<Object, Object>) DictIntrinsics._copy_(dict);
      assertNotSame(dict, copy);

      copy.put("b", 2L);
      copy.put("a", 10L);
      assertEquals(1, dict.size());
      assertEquals(1L, dict.get("a"));

      dict.remove("a");
      assertEquals(2, copy.size());
      assertEquals(10L, copy.get("a"));
    }

    @Test
    public void copyIsShallow() {
      var list = new PyList<Object>(1L);
      var dict = dictOf("k", list);

      var copy = (PyDict<?, ?>) DictIntrinsics._copy_(dict);

      var extractedList = (PyList<?>) copy.get("k");
      assertSame(list, extractedList);

      list.append(2L);
      assertEquals(2, extractedList.size());
    }

    @Test
    public void copyKeepsTheKeyObjects() {
      var key = new String("key");
      var dict = dictOf();
      dict.put(key, 1L);

      var copy = (PyDict<?, ?>) DictIntrinsics._copy_(dict);

      assertSame(key, copy.keyAt(0));
    }

    @Test
    public void copyDropsDeletedEntries() {
      var dict = dictOf("a", 1L, "b", 2L, "c", 3L);
      dict.remove("b");

      var copy = (PyDict<?, ?>) DictIntrinsics._copy_(dict);

      assertEquals(2, copy.size());
      assertEquals(2, copy.entryCount());  // no tombstone is copied
      assertEquals(dictOf("a", 1L, "c", 3L), copy);
    }

    @Test
    public void copyOfAnEmptyDict() {
      var copy = (PyDict<?, ?>) DictIntrinsics._copy_(dictOf());

      assertTrue(copy.isEmpty());
    }

    @Test
    public void copyPreservesNoneValues() {
      var dict = dictOf("a", PyNone.INSTANCE);

      var copy = (PyDict<?, ?>) DictIntrinsics._copy_(dict);

      assertSame(PyNone.INSTANCE, copy.get("a"));
    }

    @Test
    public void copyPreservesNaNValues() {
      var nan1 = TypeIntrinsics.$float$("nan");
      var nan2 = TypeIntrinsics.$float$("nan");
      var dict =
          dictOf("a", nan1, "b", nan2);

      var copy = (PyDict<?, ?>) DictIntrinsics._copy_(dict);

      assertSame(nan1, copy.get("a"));
      assertSame(nan2, copy.get("b"));
    }

    @Test
    public void nullDictIsRejected() {
      assertThrows(NullPointerException.class, () -> DictIntrinsics._copy_(null));
    }
  }


  @Nested
  public final class FromKeysMethod {
    @Test
    public void defaultValueIsNone() {
      var result = (PyDict<?, ?>) DictIntrinsics._fromkeys_(PyType.DICT, new PyList<>("a", "b"));

      assertEquals(dictOf("a", PyNone.INSTANCE, "b", PyNone.INSTANCE), result);
      assertEquals(List.of("a", "b"), keysOf(result));
      assertEquals(List.of(PyNone.INSTANCE, PyNone.INSTANCE), valuesOf(result));
    }

    @Test
    public void explicitValueIsSharedByAllKeys() {
      var shared = new PyList<>();

      var result = (PyDict<?, ?>) DictIntrinsics._fromkeys_(
          PyType.DICT, new PyList<Object>("a", "b", "c"), shared);

      assertEquals(3, result.size());
      assertSame(shared, result.get("a"));
      assertSame(shared, result.get("b"));
      assertSame(shared, result.get("c"));
    }

    @Test
    public void keysCanComeFromATuple() {
      var result = (PyDict<?, ?>) DictIntrinsics._fromkeys_(
          PyType.DICT, TupleIntrinsics.fromArray(1L, 2L, 3L), 0L);

      assertEquals(dictOf(1L, 0L, 2L, 0L, 3L, 0L), result);
      assertEquals(List.of(1L, 2L, 3L), keysOf(result));
      assertEquals(List.of(0L, 0L, 0L), valuesOf(result));
    }

    @Test
    public void keysCanComeFromAStringCodePointByCodePoint() {
      var result = (PyDict<?, ?>) DictIntrinsics._fromkeys_(PyType.DICT, "a\uD83D\uDE00b");

      assertEquals(dictOf("a", PyNone.INSTANCE, "\uD83D\uDE00", PyNone.INSTANCE, "b", PyNone.INSTANCE), result);
      assertEquals(List.of("a", "\uD83D\uDE00", "b"), keysOf(result));
    }

    @Test
    public void keysCanComeFromARange() {
      var result = (PyDict<?, ?>) DictIntrinsics._fromkeys_(PyType.DICT, PyRange.of(0L, 3L, 1L), true);

      assertEquals(dictOf(0L, true, 1L, true, 2L, true), result);
      assertEquals(List.of(0L, 1L, 2L), keysOf(result));
      assertEquals(List.of(true, true, true), valuesOf(result));
    }

    @Test
    public void keysCanComeFromADictionary() {
      var source = dictOf("x", 1L, "y", 2L);

      var result = (PyDict<?, ?>) DictIntrinsics._fromkeys_(PyType.DICT, source);

      assertEquals(List.of("x", "y"), keysOf(result));
      assertEquals(List.of(PyNone.INSTANCE, PyNone.INSTANCE), valuesOf(result));
      assertEquals(List.of(1L, 2L), valuesOf(source));  // the source is untouched
    }

    @Test
    public void duplicateKeysCollapseKeepingFirstPosition() {
      var result = (PyDict<?, ?>) DictIntrinsics._fromkeys_(
          PyType.DICT, new PyList<Object>("a", "b", "a", "c", "b"), 7L);

      assertEquals(List.of("a", "b", "c"), keysOf(result));
      assertEquals(List.of(7L, 7L, 7L), valuesOf(result));
    }

    @Test
    public void equivalentNumericKeysCollapseKeepingTheFirstKeyObject() {
      var result = (PyDict<?, ?>) DictIntrinsics._fromkeys_(
          PyType.DICT, new PyList<Object>(1L, true, 1.0d));

      assertEquals(1, result.size());
      assertEquals(dictOf(1L,  PyNone.INSTANCE), result);
      assertEquals(List.of(1L), keysOf(result));
    }

    @Test
    public void emptyIterableGivesAnEmptyDict() {
      var result = (PyDict<?, ?>) DictIntrinsics._fromkeys_(PyType.DICT, new PyList<>());

      assertTrue(result.isEmpty());
    }

    @Test
    public void emptyStringAndEmptyTupleGiveAnEmptyDict() {
      assertTrue(((PyDict<?, ?>) DictIntrinsics._fromkeys_(PyType.DICT, "")).isEmpty());
      assertTrue(((PyDict<?, ?>) DictIntrinsics._fromkeys_(PyType.DICT, TupleIntrinsics.empty())).isEmpty());
    }

    @Test
    public void noneCanBeAKeyAndAValue() {
      var result = (PyDict<?, ?>) DictIntrinsics._fromkeys_(
          PyType.DICT, new PyList<>(PyNone.INSTANCE), PyNone.INSTANCE);

      assertEquals(dictOf(PyNone.INSTANCE, PyNone.INSTANCE), result);
      assertSame(PyNone.INSTANCE, result.get(PyNone.INSTANCE));
    }

    @Test
    public void everyCallReturnsANewDictionary() {
      var keys = new PyList<Object>("a");

      var first = DictIntrinsics._fromkeys_(PyType.DICT, keys);
      var second = DictIntrinsics._fromkeys_(PyType.DICT, keys);

      assertNotSame(first, second);
    }

    @Test
    public void typeOtherThanDictIsRejected() {
      assertThrows(IllegalArgumentException.class,
          () -> DictIntrinsics._fromkeys_(PyType.LIST, new PyList<>("a")));
      assertThrows(IllegalArgumentException.class,
          () -> DictIntrinsics._fromkeys_(PyType.INT, new PyList<>("a"), 1L));
    }

    @Test
    public void nonIterableArgumentRaisesTypeError() {
      assertThrows(PyTypeError.class, () -> DictIntrinsics._fromkeys_(PyType.DICT, 5L));
      assertThrows(PyTypeError.class, () -> DictIntrinsics._fromkeys_(PyType.DICT, PyNone.INSTANCE));
    }

    @Test
    public void unhashableKeyRaisesTypeError() {
      var keys = new PyList<>("a", new PyList<>());

      assertThrows(PyTypeError.class, () -> DictIntrinsics._fromkeys_(PyType.DICT, keys));
    }

    @Test
    public void nullArgumentsAreRejected() {
      assertThrows(NullPointerException.class,
          () -> DictIntrinsics._fromkeys_(null, new PyList<>()));
      assertThrows(NullPointerException.class,
          () -> DictIntrinsics._fromkeys_(PyType.DICT, null));
      assertThrows(NullPointerException.class,
          () -> DictIntrinsics._fromkeys_(PyType.DICT, new PyList<>(), null));
    }
  }


  @Nested
  public final class GetMethod {
    @Test
    public void existingKeyReturnsItsValue() {
      var dict = dictOf("a", 1L);

      assertEquals(1L, DictIntrinsics._get_(dict, "a"));
      assertEquals(1L, DictIntrinsics._get_(dict, "a", 99L));
    }

    @Test
    public void missingKeyReturnsNoneByDefault() {
      var dict = dictOf("a", 1L);

      assertSame(PyNone.INSTANCE, DictIntrinsics._get_(dict, "missing"));
    }

    @Test
    public void missingKeyReturnsTheProvidedDefault() {
      var dict = dictOf("a", 1L);

      assertEquals(99L, DictIntrinsics._get_(dict, "missing", 99L));
    }

    @Test
    public void getOnAnEmptyDict() {
      var dict = dictOf();

      assertSame(PyNone.INSTANCE, DictIntrinsics._get_(dict, 1L));
      assertEquals("d", DictIntrinsics._get_(dict, 1L, "d"));
    }

    @Test
    public void getDoesNotInsertTheKey() {
      var dict = dictOf("a", 1L);

      DictIntrinsics._get_(dict, "missing", 5L);

      assertEquals(1, dict.size());
      assertEquals(dictOf("a", 1L), dict);
      assertFalse(dict.containsKey("missing"));
    }

    @Test
    public void aStoredNoneIsReturnedInsteadOfTheDefault() {
      var dict = dictOf("k", PyNone.INSTANCE);

      assertSame(PyNone.INSTANCE, DictIntrinsics._get_(dict, "k", 5L));
    }

    @Test
    public void falsyStoredValuesAreNotConfusedWithAbsence() {
      var dict = dictOf("zero", 0L, "empty", "", "false", false);

      assertEquals(0L, DictIntrinsics._get_(dict, "zero", 5L));
      assertEquals("", DictIntrinsics._get_(dict, "empty", 5L));
      assertEquals(false, DictIntrinsics._get_(dict, "false", 5L));
    }

    @Test
    public void defaultIsReturnedByReference() {
      var defaultValue1 = new PyList<>();
      var defaultValue2 = TypeIntrinsics.$bool$("nan");

      assertSame(defaultValue1, DictIntrinsics._get_(dictOf(), "a", defaultValue1));
      assertSame(defaultValue2, DictIntrinsics._get_(dictOf(), "a", defaultValue2));
    }

    @Test
    public void equivalentNumericKeysFindTheSameEntry() {
      var dict = dictOf(1L, "one");

      assertEquals("one", DictIntrinsics._get_(dict, 1.0d));
      assertEquals("one", DictIntrinsics._get_(dict, true));
      assertSame(PyNone.INSTANCE, DictIntrinsics._get_(dict, 2L));
    }

    @Test
    public void tupleKeys() {
      var dict = dictOf(TupleIntrinsics.fromArray(1L, "a"), "found");

      assertEquals("found", DictIntrinsics._get_(dict, TupleIntrinsics.fromArray(1.0d, "a")));
      assertSame(PyNone.INSTANCE, DictIntrinsics._get_(dict, TupleIntrinsics.fromArray(1L, "b")));
    }

    @Test
    public void unhashableKeyRaisesTypeError() {
      var dict = dictOf("a", 1L);

      assertThrows(PyTypeError.class, () -> DictIntrinsics._get_(dict, new PyList<Object>()));
      assertThrows(PyTypeError.class, () -> DictIntrinsics._get_(dict, dictOf(), 1L));
    }

    @Test
    public void nullArgumentsAreRejected() {
      var dict = dictOf("a", 1L);

      assertThrows(NullPointerException.class, () -> DictIntrinsics._get_(null, "a"));
      assertThrows(NullPointerException.class, () -> DictIntrinsics._get_(dict, null));
      assertThrows(NullPointerException.class, () -> DictIntrinsics._get_(dict, "a", null));
    }
  }


  @Nested
  public final class PopMethod {
    @Test
    public void popReturnsTheValueAndRemovesTheKey() {
      var dict = dictOf("a", 1L, "b", 2L);

      assertEquals(1L, DictIntrinsics._pop_(dict, "a"));

      assertEquals(1, dict.size());
      assertEquals(dictOf("b", 2L), dict);
      assertFalse(dict.containsKey("a"));
      assertEquals(List.of("b"), keysOf(dict));
    }

    @Test
    public void popOfAMissingKeyRaisesKeyErrorWithTheKeyRepr() {
      var dict = dictOf("a", 1L);

      var stringKey = assertThrows(PyKeyError.class, () -> DictIntrinsics._pop_(dict, "missing"));
      assertEquals("'missing'", stringKey.getMessage());

      var intKey = assertThrows(PyKeyError.class, () -> DictIntrinsics._pop_(dict, 42L));
      assertEquals("42", intKey.getMessage());
    }

    @Test
    public void failedPopLeavesTheDictUnchanged() {
      var dict = dictOf("a", 1L, "b", 2L);

      assertThrows(PyKeyError.class, () -> DictIntrinsics._pop_(dict, "missing"));

      assertEquals(dictOf("a", 1L, "b", 2L), dict);
      assertEquals(List.of("a", "b"), keysOf(dict));
      assertEquals(List.of(1L, 2L), valuesOf(dict));
    }

    @Test
    public void popOnAnEmptyDictRaisesKeyError() {
      assertThrows(PyKeyError.class, () -> DictIntrinsics._pop_(dictOf(), "a"));
    }

    @Test
    public void popOfAMissingKeyWithDefaultReturnsTheDefault() {
      var dict = dictOf("a", 1L);

      assertEquals(99L, DictIntrinsics._pop_(dict, "missing", 99L));
      assertSame(PyNone.INSTANCE, DictIntrinsics._pop_(dict, "missing", PyNone.INSTANCE));

      assertEquals(1, dict.size());  // the default is never inserted
      assertEquals(dictOf("a", 1L), dict);
      assertFalse(dict.containsKey("missing"));
    }

    @Test
    public void popOfAnExistingKeyIgnoresTheDefault() {
      var dict = dictOf("a", 1L);

      assertEquals(1L, DictIntrinsics._pop_(dict, "a", 99L));

      assertTrue(dict.isEmpty());
    }

    @Test
    public void aStoredNoneIsPoppedInsteadOfRaisingOrUsingTheDefault() {
      var dict = dictOf("k", PyNone.INSTANCE);

      assertSame(PyNone.INSTANCE, DictIntrinsics._pop_(dict, "k", 5L));
      assertFalse(dict.containsKey("k"));

      var other = dictOf("k", PyNone.INSTANCE);

      assertSame(PyNone.INSTANCE, DictIntrinsics._pop_(other, "k"));
      assertTrue(other.isEmpty());
    }

    @Test
    public void poppingTwiceThenFailsOrFallsBackToTheDefault() {
      var dict = dictOf("a", 1L);

      DictIntrinsics._pop_(dict, "a");

      assertThrows(PyKeyError.class, () -> DictIntrinsics._pop_(dict, "a"));
      assertEquals("d", DictIntrinsics._pop_(dict, "a", "d"));
    }

    @Test
    public void reinsertedKeyGoesToTheEnd() {
      var dict = dictOf("a", 1L, "b", 2L, "c", 3L);

      DictIntrinsics._pop_(dict, "a");
      dict.put("a", 10L);

      assertEquals(dictOf("b", 2L, "c", 3L, "a", 10L), dict);
      assertEquals(List.of("b", "c", "a"), keysOf(dict));
    }

    @Test
    public void equivalentNumericKeyPopsTheSameEntry() {
      var dict = dictOf(1L, "one");

      assertEquals("one", DictIntrinsics._pop_(dict, 1.0d));
      assertTrue(dict.isEmpty());
    }

    @Test
    public void popReturnsTheStoredReference() {
      var value = new PyList<>();
      var dict = dictOf("a", value);

      assertSame(value, DictIntrinsics._pop_(dict, "a"));
    }

    @Test
    public void unhashableKeyRaisesTypeError() {
      var dict = dictOf("a", 1L);

      assertThrows(PyTypeError.class, () -> DictIntrinsics._pop_(dict, new PyList<Object>()));
      assertThrows(PyTypeError.class, () -> DictIntrinsics._pop_(dict, new PyList<Object>(), 1L));
    }

    @Test
    public void manyPopsCompactTheDictionaryWithoutCorruptingIt() {
      var dict = dictOf();
      for (long i = 0; i < 20; i++) {
        dict.put(i, i * 10);
      }

      for (long i = 0; i < 15; i++) {
        assertEquals(i * 10, DictIntrinsics._pop_(dict, i));
      }

      assertEquals(5, dict.size());
      assertEquals(dictOf(15L, 150L, 16L, 160L, 17L, 170L, 18L, 180L, 19L, 190L), dict);
      assertEquals(List.of(15L, 16L, 17L, 18L, 19L), keysOf(dict));
      assertEquals(List.of(150L, 160L, 170L, 180L, 190L), valuesOf(dict));
      assertEquals(170L, DictIntrinsics._get_(dict, 17L));
    }

    @Test
    public void nullArgumentsAreRejected() {
      var dict = dictOf("a", 1L);

      assertThrows(NullPointerException.class, () -> DictIntrinsics._pop_(null, "a"));
      assertThrows(NullPointerException.class, () -> DictIntrinsics._pop_(dict, null));
      assertThrows(NullPointerException.class, () -> DictIntrinsics._pop_(dict, "a", null));
      assertThrows(NullPointerException.class, () -> DictIntrinsics._pop_(dict, null, 1L));
    }
  }


  @Nested
  public final class PopItemMethod {
    @Test
    public void popitemRemovesTheLastInsertedEntryFirst() {
      var dict = dictOf("a", 1L, "b", 2L, "c", 3L);

      assertEquals(TupleIntrinsics.fromArray("c", 3L), DictIntrinsics._popitem_(dict));
      assertEquals(TupleIntrinsics.fromArray("b", 2L), DictIntrinsics._popitem_(dict));
      assertEquals(TupleIntrinsics.fromArray("a", 1L), DictIntrinsics._popitem_(dict));

      assertTrue(dict.isEmpty());
    }

    @Test
    public void popitemOnAnEmptyDictRaisesKeyError() {
      var error = assertThrows(PyKeyError.class, () -> DictIntrinsics._popitem_(dictOf()));

      assertEquals("'popitem(): dictionary is empty'", error.getMessage());
    }

    @Test
    public void popitemOnADrainedDictRaisesKeyError() {
      var dict = dictOf("a", 1L);

      DictIntrinsics._popitem_(dict);

      assertThrows(PyKeyError.class, () -> DictIntrinsics._popitem_(dict));
      assertTrue(dict.isEmpty());
    }

    @Test
    public void popitemSkipsDeletedTrailingEntries() {
      var dict = dictOf("a", 1L, "b", 2L, "c", 3L);
      dict.remove("c");

      assertEquals(TupleIntrinsics.fromArray("b", 2L), DictIntrinsics._popitem_(dict));

      assertEquals(dictOf("a", 1L), dict);
      assertEquals(List.of("a"), keysOf(dict));
    }

    @Test
    public void popitemSkipsDeletedEntriesInTheMiddle() {
      var dict = dictOf("a", 1L, "b", 2L, "c", 3L);
      dict.remove("b");

      assertEquals(TupleIntrinsics.fromArray("c", 3L), DictIntrinsics._popitem_(dict));
      assertEquals(TupleIntrinsics.fromArray("a", 1L), DictIntrinsics._popitem_(dict));
      assertTrue(dict.isEmpty());
    }

    @Test
    public void popitemFollowsReinsertionOrder() {
      var dict = dictOf("a", 1L, "b", 2L);
      dict.remove("a");
      dict.put("a", 10L);  // "a" is now the last entry

      assertEquals(TupleIntrinsics.fromArray("a", 10L), DictIntrinsics._popitem_(dict));
      assertEquals(TupleIntrinsics.fromArray("b", 2L), DictIntrinsics._popitem_(dict));
    }

    @Test
    public void popitemReplacingAValueDoesNotMoveTheKey() {
      var dict = dictOf("a", 1L, "b", 2L);
      dict.put("a", 10L);  // replacing a value keeps the position

      assertEquals(TupleIntrinsics.fromArray("b", 2L), DictIntrinsics._popitem_(dict));
    }

    @Test
    public void popitemWithANoneValue() {
      var dict = dictOf("a", PyNone.INSTANCE);

      assertEquals(TupleIntrinsics.fromArray("a", PyNone.INSTANCE), DictIntrinsics._popitem_(dict));
    }

    @Test
    public void popitemShrinksTheDictAndRemovesTheKey() {
      var dict = dictOf("a", 1L, "b", 2L);

      DictIntrinsics._popitem_(dict);

      assertEquals(1, dict.size());
      assertEquals(dictOf("a", 1L), dict);
      assertFalse(dict.containsKey("b"));
      assertTrue(dict.containsKey("a"));
    }

    @Test
    public void popitemReturnsAPairTuple() {
      var dict = dictOf("a", 1L);

      var result = (com.github.forax.orvet.rt.PyTuple) DictIntrinsics._popitem_(dict);

      assertEquals(2, result.size());
      assertEquals("a", result.get(0));
      assertEquals(1L, result.get(1));
    }

    @Test
    public void popitemAfterCompactionStillReturnsTheLastEntry() {
      var dict = dictOf();
      for (long i = 0; i < 20; i++) {
        dict.put(i, i * 10);
      }
      for (long i = 0; i < 15; i++) {
        dict.remove(i);  // triggers a compaction of the insertion-order arrays
      }

      assertEquals(TupleIntrinsics.fromArray(19L, 190L), DictIntrinsics._popitem_(dict));
      assertEquals(TupleIntrinsics.fromArray(18L, 180L), DictIntrinsics._popitem_(dict));

      assertEquals(dictOf(15L, 150L, 16L, 160L, 17L, 170L), dict);
      assertEquals(List.of(15L, 16L, 17L), keysOf(dict));
    }

    @Test
    public void drainingALargeDictReturnsEntriesInReverseInsertionOrder() {
      var dict = dictOf();
      for (long i = 0; i < 100; i++) {
        dict.put(i, i);
      }

      for (long i = 99; i >= 0; i--) {
        assertEquals(TupleIntrinsics.fromArray(i, i), DictIntrinsics._popitem_(dict));
      }

      assertTrue(dict.isEmpty());
    }

    @Test
    public void nullDictIsRejected() {
      assertThrows(NullPointerException.class, () -> DictIntrinsics._popitem_(null));
    }
  }


  @Nested
  public final class SetDefaultMethod {
    @Test
    public void missingKeyIsInsertedWithNoneByDefault() {
      var dict = dictOf();

      var result = DictIntrinsics._setdefault_(dict, "a");

      assertSame(PyNone.INSTANCE, result);
      assertEquals(1, dict.size());
      assertEquals(dictOf("a", PyNone.INSTANCE), dict);
      assertSame(PyNone.INSTANCE, dict.get("a"));
    }

    @Test
    public void missingKeyIsInsertedWithTheProvidedDefault() {
      var dict = dictOf();

      var result = DictIntrinsics._setdefault_(dict, "a", 42L);

      assertEquals(42L, result);
      assertEquals(dictOf("a", 42L), dict);
      assertEquals(42L, dict.get("a"));
    }

    @Test
    public void existingKeyIsNotModified() {
      var dict = dictOf("a", 1L);

      assertEquals(1L, DictIntrinsics._setdefault_(dict, "a", 99L));
      assertEquals(1L, DictIntrinsics._setdefault_(dict, "a"));

      assertEquals(1, dict.size());
      assertEquals(dictOf("a", 1L), dict);
      assertEquals(1L, dict.get("a"));
    }

    @Test
    public void existingNoneValueIsKeptAndReturned() {
      var dict = dictOf("a", PyNone.INSTANCE);

      assertSame(PyNone.INSTANCE, DictIntrinsics._setdefault_(dict, "a", 99L));
      assertEquals(dictOf("a", PyNone.INSTANCE), dict);
      assertSame(PyNone.INSTANCE, dict.get("a"));
    }

    @Test
    public void existingFalsyValuesAreKept() {
      var dict = dictOf("zero", 0L, "empty", "");

      assertEquals(0L, DictIntrinsics._setdefault_(dict, "zero", 5L));
      assertEquals("", DictIntrinsics._setdefault_(dict, "empty", "x"));

      assertEquals(dictOf("zero", 0L, "empty", ""), dict);
      assertEquals(0L, dict.get("zero"));
      assertEquals("", dict.get("empty"));
    }

    @Test
    public void insertedKeyGoesToTheEnd() {
      var dict = dictOf("a", 1L, "b", 2L);

      DictIntrinsics._setdefault_(dict, "c", 3L);
      DictIntrinsics._setdefault_(dict, "a", 100L);  // present: position unchanged

      assertEquals(dictOf("a", 1L, "b", 2L, "c", 3L), dict);
      assertEquals(List.of("a", "b", "c"), keysOf(dict));
      assertEquals(List.of(1L, 2L, 3L), valuesOf(dict));
    }

    @Test
    @SuppressWarnings("unchecked")
    public void defaultIsStoredAndReturnedByReference() {
      var dict = dictOf();
      var list = new PyList<Object>();

      var result = DictIntrinsics._setdefault_(dict, "a", list);

      assertSame(list, result);
      assertSame(list, dict.get("a"));

      // The classic "group by" idiom: the returned list is the stored list.
      ((PyList<Object>) DictIntrinsics._setdefault_(dict, "a", new PyList<>())).append(1L);

      assertEquals(1, list.size());
      assertEquals(dictOf("a", new PyList<>(1L)), dict);
    }

    @Test
    public void equivalentNumericKeyFindsTheExistingEntry() {
      var dict = dictOf(1L, "one");

      assertEquals("one", DictIntrinsics._setdefault_(dict, 1.0d, "other"));
      assertEquals("one", DictIntrinsics._setdefault_(dict, true, "other"));

      assertEquals(1, dict.size());
      assertEquals(dictOf(1L, "one"), dict);
      assertEquals(List.of(1L), keysOf(dict));
    }

    @Test
    public void tupleKeys() {
      var dict = dictOf();
      DictIntrinsics._setdefault_(dict, TupleIntrinsics.fromArray(1L, 2L), "t");

      assertEquals(1, dict.size());
      assertEquals(dictOf(TupleIntrinsics.fromArray(1L, 2L), "t"), dict);

      assertEquals("t", DictIntrinsics._setdefault_(dict, TupleIntrinsics.fromArray(1.0d, 2L), "u"));


      assertEquals(1, dict.size());
      assertEquals(dictOf(TupleIntrinsics.fromArray(1L, 2L), "t"), dict);
    }

    @Test
    public void unhashableKeyRaisesTypeErrorAndDoesNotModifyTheDict() {
      var dict = dictOf("a", 1L);

      assertThrows(PyTypeError.class, () -> DictIntrinsics._setdefault_(dict, new PyList<>()));
      assertThrows(PyTypeError.class, () -> DictIntrinsics._setdefault_(dict, new PyList<>(), 1L));

      assertEquals(List.of("a"), keysOf(dict));
    }

    @Test
    public void nullArgumentsAreRejected() {
      var dict = dictOf("a", 1L);

      assertThrows(NullPointerException.class, () -> DictIntrinsics._setdefault_(null, "a"));
      assertThrows(NullPointerException.class, () -> DictIntrinsics._setdefault_(dict, null));
      assertThrows(NullPointerException.class, () -> DictIntrinsics._setdefault_(null, "a", 1L));
      assertThrows(NullPointerException.class, () -> DictIntrinsics._setdefault_(dict, null, 1L));
    }
  }


  @Nested
  public final class UpdateMethod {
    @Test
    public void updateFromADictOverwritesExistingKeysAndAppendsNewOnes() {
      var dict = dictOf("a", 1L, "b", 2L);
      var other = dictOf("b", 20L, "c", 3L);

      var result = DictIntrinsics._update_(dict, other);

      assertSame(PyNone.INSTANCE, result);
      assertEquals(dictOf("a", 1L, "b", 20L, "c", 3L), dict);
      assertEquals(List.of("a", "b", "c"), keysOf(dict));
      assertEquals(List.of(1L, 20L, 3L), valuesOf(dict));
    }

    @Test
    public void updateFromADictDoesNotModifyTheSource() {
      var dict = dictOf("a", 1L);
      var other = dictOf("b", 2L);

      DictIntrinsics._update_(dict, other);

      assertEquals(dictOf("b", 2L), other);
      assertEquals(List.of("b"), keysOf(other));
      assertEquals(List.of(2L), valuesOf(other));
    }

    @Test
    public void updateKeepsTheOriginalKeyObjectAndPositionOfExistingKeys() {
      var original = new String("k");
      var dict = dictOf();
      dict.put(original, 1L);
      dict.put("z", 2L);

      DictIntrinsics._update_(dict, dictOf(new String("k"), 10L));

      assertSame(original, dict.keyAt(0));
      assertEquals(10L, dict.get("k"));
      assertEquals(dictOf("k", 10L, "z", 2L), dict);
      assertEquals(List.of("k", "z"), keysOf(dict));
    }

    @Test
    public void updateFromADictSkipsItsDeletedEntries() {
      var other = dictOf("a", 1L, "b", 2L, "c", 3L);
      other.remove("b");
      var dict = dictOf();

      DictIntrinsics._update_(dict, other);

      assertEquals(dictOf("a", 1L, "c", 3L), other);
      assertEquals(List.of("a", "c"), keysOf(dict));
    }

    @Test
    public void updateWithAnEmptyDictOrIterableIsANoOp() {
      var dict = dictOf("a", 1L);

      DictIntrinsics._update_(dict, dictOf());
      DictIntrinsics._update_(dict, new PyList<Object>());
      DictIntrinsics._update_(dict, TupleIntrinsics.empty());

      assertEquals(dictOf("a", 1L), dict);
      assertEquals(List.of("a"), keysOf(dict));
      assertEquals(List.of(1L), valuesOf(dict));
    }

    @Test
    public void updateAnEmptyDictFromAnotherDict() {
      var dict = dictOf();

      DictIntrinsics._update_(dict, dictOf("a", 1L));

      assertEquals(dictOf("a", 1L), dict);
      assertEquals(List.of("a"), keysOf(dict));
    }

    @Test
    public void updateWithItselfChangesNothing() {
      var dict = dictOf("a", 1L, "b", 2L);

      DictIntrinsics._update_(dict, dict);

      assertEquals(dictOf("a", 1L, "b", 2L), dict);
      assertEquals(List.of("a", "b"), keysOf(dict));
      assertEquals(List.of(1L, 2L), valuesOf(dict));
    }

    @Test
    public void updateFromAListOfTuples() {
      var dict = dictOf("a", 1L);
      var pairs = new PyList<Object>(
          TupleIntrinsics.fromArray("a", 10L),
          TupleIntrinsics.fromArray("b", 2L));

      DictIntrinsics._update_(dict, pairs);

      assertEquals(dictOf("a", 10L, "b", 2L), dict);
      assertEquals(List.of("a", "b"), keysOf(dict));
      assertEquals(List.of(10L, 2L), valuesOf(dict));
    }

    @Test
    public void updateFromATupleOfLists() {
      var dict = dictOf();
      var pairs = TupleIntrinsics.fromArray(
          new PyList<>("a", 1L),
          new PyList<>("b", 2L));

      DictIntrinsics._update_(dict, pairs);

      assertEquals(dictOf("a", 1L, "b", 2L), dict);
      assertEquals(List.of("a", "b"), keysOf(dict));
      assertEquals(List.of(1L, 2L), valuesOf(dict));
    }

    @Test
    public void updateFromTwoCharacterStringsTreatsThemAsPairs() {
      var dict = dictOf();
      var pairs = new PyList<>("ab", "cd");

      DictIntrinsics._update_(dict, pairs);

      assertEquals(dictOf("a", "b", "c", "d"), dict);
      assertEquals("b", dict.get("a"));
      assertEquals("d", dict.get("c"));
    }

    @Test
    public void laterPairsOverwriteEarlierOnesForTheSameKey() {
      var dict = dictOf();
      var pairs = new PyList<Object>(
          TupleIntrinsics.fromArray("a", 1L),
          TupleIntrinsics.fromArray("a", 2L));

      DictIntrinsics._update_(dict, pairs);

      assertEquals(1, dict.size());
      assertEquals(dictOf("a", 2L), dict);
      assertEquals(2L, dict.get("a"));
    }

    @Test
    public void noneKeysAndValuesAreAccepted() {
      var dict = dictOf();
      var pairs = new PyList<Object>(TupleIntrinsics.fromArray(PyNone.INSTANCE, PyNone.INSTANCE));

      DictIntrinsics._update_(dict, pairs);

      assertEquals(dictOf(PyNone.INSTANCE, PyNone.INSTANCE), dict);
      assertSame(PyNone.INSTANCE, dict.get(PyNone.INSTANCE));
    }

    @Test
    public void pairWithTooFewItemsRaisesValueError() {
      var dict = dictOf();

      var empty = assertThrows(PyValueError.class,
          () -> DictIntrinsics._update_(dict, new PyList<Object>(TupleIntrinsics.empty())));
      assertEquals("dictionary update sequence element #0 has length 0; 2 is required", empty.getMessage());

      var one = assertThrows(PyValueError.class,
          () -> DictIntrinsics._update_(dict, new PyList<Object>(TupleIntrinsics.fromArray("a"))));
      assertEquals("dictionary update sequence element #0 has length 1; 2 is required", one.getMessage());
    }

    @Test
    public void pairWithTooManyItemsRaisesValueError() {
      var dict = dictOf();

      var three = assertThrows(PyValueError.class,
          () -> DictIntrinsics._update_(dict, new PyList<Object>(TupleIntrinsics.fromArray(1L, 2L, 3L))));
      assertEquals("dictionary update sequence element #0 has length 3; 2 is required", three.getMessage());

      var five = assertThrows(PyValueError.class,
          () -> DictIntrinsics._update_(dict, new PyList<Object>(TupleIntrinsics.fromArray(1L, 2L, 3L, 4L, 5L))));
      assertEquals("dictionary update sequence element #0 has length 5; 2 is required", five.getMessage());
    }

    @Test
    public void errorReportsTheIndexOfTheOffendingElement() {
      var dict = dictOf();
      var pairs = new PyList<>(
          TupleIntrinsics.fromArray("a", 1L),
          TupleIntrinsics.fromArray("b", 2L),
          TupleIntrinsics.fromArray("c"));

      var error = assertThrows(PyValueError.class, () -> DictIntrinsics._update_(dict, pairs));

      assertEquals("dictionary update sequence element #2 has length 1; 2 is required", error.getMessage());
    }

    @Test
    public void elementThatIsNotASequenceRaisesTypeError() {
      var dict = dictOf();
      var pairs = new PyList<Object>(TupleIntrinsics.fromArray("a", 1L), 42L);

      var error = assertThrows(PyTypeError.class, () -> DictIntrinsics._update_(dict, pairs));

      assertEquals("cannot convert dictionary update sequence element #1 to a sequence", error.getMessage());
    }

    @Test
    public void pairsBeforeTheFailureAreKept() {
      // Same as CPython: update() is not atomic.
      var dict = dictOf();
      var pairs = new PyList<Object>(TupleIntrinsics.fromArray("a", 1L), 42L);

      assertThrows(PyTypeError.class, () -> DictIntrinsics._update_(dict, pairs));

      assertEquals(List.of("a"), keysOf(dict));
    }

    @Test
    public void updateFromAStringIsAnErrorBecauseItsElementsAreNotPairs() {
      var dict = dictOf();

      var error = assertThrows(PyValueError.class, () -> DictIntrinsics._update_(dict, "ab"));

      assertEquals("dictionary update sequence element #0 has length 1; 2 is required", error.getMessage());
    }

    @Test
    public void updateFromAnIterableOfNonIterablesRaisesTypeError() {
      var dict = dictOf();

      assertThrows(PyTypeError.class, () -> DictIntrinsics._update_(dict, PyRange.of(0L, 3L, 1L)));
    }

    @Test
    public void updateFromANonIterableRaisesTypeError() {
      var dict = dictOf("a", 1L);

      assertThrows(PyTypeError.class, () -> DictIntrinsics._update_(dict, 5L));
      assertThrows(PyTypeError.class, () -> DictIntrinsics._update_(dict, PyNone.INSTANCE));
      assertEquals(List.of("a"), keysOf(dict));
    }

    @Test
    public void unhashableKeyInAPairRaisesTypeError() {
      var dict = dictOf();
      var pairs = new PyList<Object>(TupleIntrinsics.fromArray(new PyList<Object>(), 1L));

      assertThrows(PyTypeError.class, () -> DictIntrinsics._update_(dict, pairs));
      assertTrue(dict.isEmpty());
    }

    @Test
    public void equivalentNumericKeysReplaceTheSameEntry() {
      var dict = dictOf(1L, "one");

      DictIntrinsics._update_(dict, dictOf(1.0d, "uno", true, "eins"));

      assertEquals(1, dict.size());
      assertEquals(dictOf(1L, "eins"), dict);
      assertEquals(List.of(1L), keysOf(dict));
      assertEquals("eins", dict.get(1L));
    }

    @Test
    public void updatingWithManyEntriesGrowsTheDictionary() {
      var dict = dictOf();
      var other = dictOf();
      for (var i = 0L; i < 500; i++) {
        other.put(i, i * 2);
      }

      DictIntrinsics._update_(dict, other);

      assertEquals(500, dict.size());
      for (var i = 0L; i < 500L; i++) {
        assertEquals(i * 2, dict.get(i));
      }
      assertTrue(DictIntrinsics.equals(dict, other));
    }

    @Test
    public void nullArgumentsAreRejected() {
      var dict = dictOf("a", 1L);

      assertThrows(NullPointerException.class, () -> DictIntrinsics._update_(null, dictOf()));
      assertThrows(NullPointerException.class, () -> DictIntrinsics._update_(dict, null));
    }
  }
}