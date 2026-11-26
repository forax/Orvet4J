package com.github.forax.orvet.rt;

import java.util.List;

public enum PyType {
  //OBJECT("object"),
  INT("int"),
  BOOL("bool"),
  FLOAT("float"),
  STR("str"),
  NONE_TYPE("NoneType"),
  TUPLE("tuple"),
  LIST("list"),
  DICT("dict"),
  RANGE("range"),
  SLICE("slice"),
  FUNCTION("function"),
  BUILTIN_FUNCTION_OR_METHOD("builtin_function_or_method"),
  TYPE("type")
  ;

  public static final List<PyType> ALL = List.of(values());

  private final PyFunction function;

  PyType(String typeName) {
    var function = Builtins.CodeInitialization.CONSTRUCTOR_FUNCTIONS.get(typeName);
    if (function == null) {
      throw new AssertionError("unknown constructor: " + typeName);
    }
    this.function = function;
  }

  public PyFunction function() {
    return function;
  }

  @Override
  public String toString() {
    return  "<class '" + function.name() + "'>";
  }
}