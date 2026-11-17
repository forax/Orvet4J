package com.github.forax.orvet.rt;

import com.github.forax.orvet.rt.PyTuple.PyTuple1;
import com.github.forax.orvet.rt.PyTuple.PyTuple2;
import com.github.forax.orvet.rt.PyTuple.PyTuple3;
import com.github.forax.orvet.rt.PyTuple.PyTuple4;
import com.github.forax.orvet.rt.PyTuple.PyTupleN;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

public final class PyTupleTest {

  @Nested
  public final class PyTuple1Test {

    @Test
    public void tuple1Accessors() {
      var tuple = new PyTuple1(1L);
      assertEquals(1L, tuple.item0());
    }

    @Test
    public void tuple2RejectsJavaNull() {
      assertThrows(NullPointerException.class, () -> new PyTuple1(null));
    }

    @Test
    public void tuple2AcceptsPyNone() {
      var tuple = new PyTuple1(PyNone.INSTANCE);
      assertSame(PyNone.INSTANCE, tuple.item0());
    }

    @Test
    public void tuple2ToString() {
      assertEquals("PyTuple1(a)", new PyTuple1("a").toString());
      assertEquals("PyTuple1(true)", new PyTuple1(true).toString());
      assertEquals("PyTuple1(None)", new PyTuple1(PyNone.INSTANCE).toString());
    }
  }

  @Nested
  public final class PyTuple2Test {

    @Test
    public void tuple2Accessors() {
      var tuple = new PyTuple2(1L, "a");
      assertEquals(1L, tuple.item0());
      assertEquals("a", tuple.item1());
    }

    @Test
    public void tuple2RejectsJavaNull() {
      assertThrows(NullPointerException.class, () -> new PyTuple2(null, 1L));
      assertThrows(NullPointerException.class, () -> new PyTuple2(1L, null));
    }

    @Test
    public void tuple2AcceptsPyNone() {
      var tuple = new PyTuple2(PyNone.INSTANCE, PyNone.INSTANCE);
      assertSame(PyNone.INSTANCE, tuple.item0());
      assertSame(PyNone.INSTANCE, tuple.item1());
    }

    @Test
    public void tuple2ToString() {
      assertEquals("PyTuple2(1, a)", new PyTuple2(1L, "a").toString());
      assertEquals("PyTuple2(None, true)", new PyTuple2(PyNone.INSTANCE, true).toString());
    }
  }

  @Nested
  public final class PyTuple3Test {

    @Test
    public void tuple3Accessors() {
      var tuple = new PyTuple3(1L, 2L, 3L);
      assertEquals(1L, tuple.item0());
      assertEquals(2L, tuple.item1());
      assertEquals(3L, tuple.item2());
    }

    @Test
    public void tuple3RejectsJavaNull() {
      assertThrows(NullPointerException.class, () -> new PyTuple3(null, 2L, 3L));
      assertThrows(NullPointerException.class, () -> new PyTuple3(1L, null, 3L));
      assertThrows(NullPointerException.class, () -> new PyTuple3(1L, 2L, null));
    }

    @Test
    public void tuple3ToString() {
      assertEquals("PyTuple3(1, 2.5, x)", new PyTuple3(1L, 2.5, "x").toString());
    }
  }


  @Nested
  public final class PyTuple4Test {

    @Test
    public void tuple4Accessors() {
      var tuple = new PyTuple4(1L, 2L, 3L, 4L);
      assertEquals(1L, tuple.item0());
      assertEquals(2L, tuple.item1());
      assertEquals(3L, tuple.item2());
      assertEquals(4L, tuple.item3());
    }

    @Test
    public void tuple4RejectsJavaNull() {
      assertThrows(NullPointerException.class, () -> new PyTuple4(null, 2L, 3L, 4L));
      assertThrows(NullPointerException.class, () -> new PyTuple4(1L, null, 3L, 4L));
      assertThrows(NullPointerException.class, () -> new PyTuple4(1L, 2L, null, 4L));
      assertThrows(NullPointerException.class, () -> new PyTuple4(1L, 2L, 3L, null));
    }

    @Test
    public void tuple4ToString() {
      assertEquals("PyTuple4(1, 2, 3, 4)", new PyTuple4(1L, 2L, 3L, 4L).toString());
    }
  }

  @Nested
  public final class PyTupleNTest {

    @Test
    public void tupleNLengthAndItems() {
      var tuple = new PyTupleN(new Object[]{1L, "a", PyNone.INSTANCE, true, 2.5});
      assertEquals(5, tuple.itemCount());
      assertEquals(1L, tuple.itemAt(0));
      assertEquals("a", tuple.itemAt(1));
      assertSame(PyNone.INSTANCE, tuple.itemAt(2));
      assertEquals(true, tuple.itemAt(3));
      assertEquals(2.5, tuple.itemAt(4));
    }

    @Test
    public void tupleNCanBeEmpty() {
      var tuple = new PyTupleN(new Object[0]);
      assertEquals(0, tuple.itemCount());
      assertEquals("PyTupleN()", tuple.toString());
    }

    @Test
    public void tupleNCanBeSmallerThanFiveForGenericUse() {
      // the choice of representation is a performance property only, so PyTupleN must work for any size
      var tuple = new PyTupleN(new Object[]{1L, 2L});
      assertEquals(2, tuple.itemCount());
      assertEquals(2L, tuple.itemAt(1));
    }

    @Test
    public void tupleNDefensivelyCopiesItsInput() {
      var array = new Object[]{1L, 2L, 3L, 4L, 5L};
      var tuple = new PyTupleN(array);
      array[0] = 99L;
      array[4] = "changed";
      assertEquals(1L, tuple.itemAt(0));
      assertEquals(5L, tuple.itemAt(4));
    }

    @Test
    public void tupleNRejectsJavaNullElements() {
      assertThrows(NullPointerException.class, () -> new PyTupleN(new Object[]{1L, null, 3L}));
      assertThrows(NullPointerException.class, () -> new PyTupleN(new Object[]{null}));
    }

    @Test
    public void tupleNRejectsNullArray() {
      assertThrows(NullPointerException.class, () -> new PyTupleN(null));
    }

    @Test
    public void tupleNItemRejectsOutOfRangeIndexes() {
      var tuple = new PyTupleN(new Object[]{1L, 2L, 3L});
      assertThrows(ArrayIndexOutOfBoundsException.class, () -> tuple.itemAt(-1));
      assertThrows(ArrayIndexOutOfBoundsException.class, () -> tuple.itemAt(3));
    }

    @Test
    public void tupleNItemsAccessorIsForbidden() {
      var tuple = new PyTupleN(new Object[]{1L});
      assertThrows(AssertionError.class, tuple::items);
    }

    @Test
    public void tupleNToString() {
      assertEquals("PyTupleN(1, a, None, 2.5, true)",
          new PyTupleN(new Object[]{1L, "a", PyNone.INSTANCE, 2.5, true}).toString());
      assertEquals("PyTupleN(7)", new PyTupleN(new Object[]{7L}).toString());
    }

    @Test
    public void tupleNEqualsAndHashCode() {
      var a = new PyTupleN(new Object[]{1L, 2L, 3L, 4L, 5L});
      var b = new PyTupleN(new Object[]{1L, 2L, 3L, 4L, 5L});
      var differentValue = new PyTupleN(new Object[]{1L, 2L, 3L, 4L, 6L});
      var differentLength = new PyTupleN(new Object[]{1L, 2L, 3L, 4L});

      assertEquals(a, a);
      assertEquals(a, b);
      assertEquals(b, a);
      assertEquals(a.hashCode(), b.hashCode());
      assertNotEquals(a, differentValue);
      assertNotEquals(a, differentLength);
      assertNotEquals(a, "PyTupleN(1, 2, 3, 4, 5)");
    }
  }


  @Nested
  public final class Shared {
    private static int arity(PyTuple tuple) {
      return switch (tuple) {  // no default: this fails to compile if a new shape is added to the sealed interface
        case PyTuple1 _ -> 1;
        case PyTuple2 _ -> 2;
        case PyTuple3 _ -> 3;
        case PyTuple4 _ -> 4;
        case PyTupleN n -> n.itemCount();
      };
    }


    @Test
    public void fixedArityRecordsHaveValueEquality() {
      assertEquals(new PyTuple2(1L, "a"), new PyTuple2(1L, "a"));
      assertEquals(new PyTuple3(1L, 2L, 3L), new PyTuple3(1L, 2L, 3L));
      assertEquals(new PyTuple4(1L, 2L, 3L, 4L), new PyTuple4(1L, 2L, 3L, 4L));
      assertNotEquals(new PyTuple2(1L, "a"), new PyTuple2(1L, "b"));
      assertNotEquals(new PyTuple2(1L, 2L), new PyTuple3(1L, 2L, 3L));
    }

    @Test
    public void everyShapeIsCoveredByAnExhaustiveSwitch() {
      assertEquals(1, arity(new PyTuple1(1L)));
      assertEquals(2, arity(new PyTuple2(1L, 2L)));
      assertEquals(3, arity(new PyTuple3(1L, 2L, 3L)));
      assertEquals(4, arity(new PyTuple4(1L, 2L, 3L, 4L)));
      assertEquals(0, arity(new PyTupleN(new Object[0])));
      assertEquals(6, arity(new PyTupleN(new Object[]{1L, 2L, 3L, 4L, 5L, 6L})));
    }

    @Test
    public void equalsWorksForEveryTupleImplementationPair() {
      record TupleCase(String name, Object[] values, PyTuple tuple) {}

      var cases = List.of(
          new TupleCase("N()", new Object[]{},
              new PyTupleN(new Object[]{})),

          new TupleCase("1(1)", new Object[]{1L},
              new PyTuple1(1L)),
          new TupleCase("1(2)", new Object[]{2L},
              new PyTuple1(2L)),
          new TupleCase("N(1)", new Object[]{1L},
              new PyTupleN(new Object[]{1L})),
          new TupleCase("N(2)", new Object[]{2L},
              new PyTupleN(new Object[]{2L})),

          new TupleCase("2(1,a)", new Object[]{1L, "a"},
              new PyTuple2(1L, "a")),
          new TupleCase("2(1,b)", new Object[]{1L, "b"},
              new PyTuple2(1L, "b")),
          new TupleCase("N(1,a)", new Object[]{1L, "a"},
              new PyTupleN(new Object[]{1L, "a"})),
          new TupleCase("N(1,b)", new Object[]{1L, "b"},
              new PyTupleN(new Object[]{1L, "b"})),

          new TupleCase("3(1,a,true)", new Object[]{1L, "a", true},
              new PyTuple3(1L, "a", true)),
          new TupleCase("3(1,a,false)", new Object[]{1L, "a", false},
              new PyTuple3(1L, "a", false)),
          new TupleCase("N(1,a,true)", new Object[]{1L, "a", true},
              new PyTupleN(new Object[]{1L, "a", true})),

          new TupleCase("4(1,a,true,4)", new Object[]{1L, "a", true, 4L},
              new PyTuple4(1L, "a", true, 4L)),
          new TupleCase("4(1,a,true,5)", new Object[]{1L, "a", true, 5L},
              new PyTuple4(1L, "a", true, 5L)),
          new TupleCase("N(1,a,true,4)", new Object[]{1L, "a", true, 4L},
              new PyTupleN(new Object[]{1L, "a", true, 4L})),

          new TupleCase("N(1,2,3,4,5)", new Object[]{1L, 2L, 3L, 4L, 5L},
              new PyTupleN(new Object[]{1L, 2L, 3L, 4L, 5L})),
          new TupleCase("N(1,2,3,4,6)", new Object[]{1L, 2L, 3L, 4L, 6L},
              new PyTupleN(new Object[]{1L, 2L, 3L, 4L, 6L}))
      );

      for (var left : cases) {
        for (var right : cases) {
          var expected = Arrays.equals(left.values(), right.values());
          var message = left.name() + " vs " + right.name();

          assertEquals(expected, left.tuple().equals(right.tuple()), message);
          assertEquals(expected, right.tuple().equals(left.tuple()), message);

          if (expected) {
            assertEquals(left.tuple().hashCode(), right.tuple().hashCode(), message);
          }
        }
      }
    }
  }

  @Nested
  public final class JavaAPI {
    @Test
    public void sizeReturnsTheNumberOfElements() {
      var expected = new Object[][] {
          {},
          {1L},
          {1L, "a"},
          {1L, "a", true},
          {1L, "a", true, 4L},
          {1L},
          {1L, "a"},
          {1L, "a", true},
          {1L, "a", true, 4L},
          {1L, "a", true, 4L, 5L}
      };

      PyTuple[] tuples = {
          new PyTupleN(new Object[0]),
          new PyTuple1(1L),
          new PyTuple2(1L, "a"),
          new PyTuple3(1L, "a", true),
          new PyTuple4(1L, "a", true, 4L),
          new PyTupleN(new Object[]{1L}),
          new PyTupleN(new Object[]{1L, "a"}),
          new PyTupleN(new Object[]{1L, "a", true}),
          new PyTupleN(new Object[]{1L, "a", true, 4L}),
          new PyTupleN(new Object[]{1L, "a", true, 4L, 5L})
      };

      for (var i = 0; i < tuples.length; i++) {
        assertEquals(expected[i].length, tuples[i].size());
      }
    }

    @Test
    public void getReturnsTheElementAtTheGivenIndex() {
      var expectedArray = new Object[][] {
          {},
          {1L},
          {1L, "a"},
          {1L, "a", true},
          {1L, "a", true, 4L},
          {1L},
          {1L, "a"},
          {1L, "a", true},
          {1L, "a", true, 4L},
          {1L, "a", true, 4L, 5L}
      };

      PyTuple[] tupleArray = {
          new PyTupleN(new Object[0]),
          new PyTuple1(1L),
          new PyTuple2(1L, "a"),
          new PyTuple3(1L, "a", true),
          new PyTuple4(1L, "a", true, 4L),
          new PyTupleN(new Object[]{1L}),
          new PyTupleN(new Object[]{1L, "a"}),
          new PyTupleN(new Object[]{1L, "a", true}),
          new PyTupleN(new Object[]{1L, "a", true, 4L}),
          new PyTupleN(new Object[]{1L, "a", true, 4L, 5L})
      };

      for (var i = 0; i < tupleArray.length; i++) {
        for (var j = 0; j < expectedArray[i].length; j++) {
          assertEquals(expectedArray[i][j], tupleArray[i].get(j));
        }

        var tuple = tupleArray[i];
        var expected = expectedArray[i];

        assertThrows(IndexOutOfBoundsException.class, () -> tuple.get(-1));
        assertThrows(IndexOutOfBoundsException.class, () -> tuple.get(expected.length));
      }
    }

    @Test
    public void asJavaListReturnsAListWithTheSameElements() {
      var expected = new Object[][] {
          {},
          {1L},
          {1L, "a"},
          {1L, "a", true},
          {1L, "a", true, 4L},
          {1L},
          {1L, "a"},
          {1L, "a", true},
          {1L, "a", true, 4L},
          {1L, "a", true, 4L, 5L}
      };

      PyTuple[] tuples = {
          new PyTupleN(new Object[0]),
          new PyTuple1(1L),
          new PyTuple2(1L, "a"),
          new PyTuple3(1L, "a", true),
          new PyTuple4(1L, "a", true, 4L),
          new PyTupleN(new Object[]{1L}),
          new PyTupleN(new Object[]{1L, "a"}),
          new PyTupleN(new Object[]{1L, "a", true}),
          new PyTupleN(new Object[]{1L, "a", true, 4L}),
          new PyTupleN(new Object[]{1L, "a", true, 4L, 5L})
      };

      for (var i = 0; i < tuples.length; i++) {
        var list = tuples[i].asJavaList();
        var values = expected[i];

        assertEquals(values.length, list.size());

        for (var j = 0; j < values.length; j++) {
          assertEquals(values[j], list.get(j));
        }

        assertEquals(Arrays.asList(values), list);
      }
    }
  }
}