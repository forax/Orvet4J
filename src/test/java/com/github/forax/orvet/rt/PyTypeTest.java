package com.github.forax.orvet.rt;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;


public final class PyTypeTest {

  @Test
  public void everyTypeHasItsConstructorFunction() {
    var expectedNames = Map.ofEntries(
        Map.entry(PyType.INT, "int"),
        Map.entry(PyType.BOOL, "bool"),
        Map.entry(PyType.STR, "str"),
        Map.entry(PyType.NONE_TYPE, "NoneType"),
        Map.entry(PyType.TUPLE, "tuple"),
        Map.entry(PyType.LIST, "list"),
        Map.entry(PyType.DICT, "dict"),
        Map.entry(PyType.RANGE, "range"),
        Map.entry(PyType.SLICE, "slice"),
        Map.entry(PyType.FUNCTION, "function")
    );

    assertEquals(List.of(PyType.values()), PyType.ALL);

    for (var entry : expectedNames.entrySet()) {
      var type = entry.getKey();
      var name = entry.getValue();

      var function = type.function();

      assertEquals(name, function.name());
      assertTrue(function.isBuiltin());
      assertSame(function, Builtins.CodeInitialization.CONSTRUCTOR_FUNCTIONS.get(name));
    }
  }

  @Test
  public void allIsImmutable() {
    assertThrows(UnsupportedOperationException.class, () -> PyType.ALL.add(PyType.INT));
  }

  @Test
  public void toStringReturnsPythonClassRepresentation() {
    assertEquals("<class 'int'>", PyType.INT.toString());
    assertEquals("<class 'bool'>", PyType.BOOL.toString());
    assertEquals("<class 'str'>", PyType.STR.toString());
    assertEquals("<class 'NoneType'>", PyType.NONE_TYPE.toString());
    assertEquals("<class 'tuple'>", PyType.TUPLE.toString());
    assertEquals("<class 'list'>", PyType.LIST.toString());
    assertEquals("<class 'dict'>", PyType.DICT.toString());
    assertEquals("<class 'range'>", PyType.RANGE.toString());
    assertEquals("<class 'slice'>", PyType.SLICE.toString());
    assertEquals("<class 'function'>", PyType.FUNCTION.toString());
  }
}
