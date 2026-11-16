package com.github.forax.orvet.rt;

import java.util.Objects;

public final class PyType {
  public static final PyType OBJECT = new PyType("object", null);
  public static final PyType TYPE = new PyType("type", OBJECT);
  public static final PyType INT = new PyType("int", OBJECT);
  public static final PyType BOOL = new PyType("bool", INT);       // bool is an int subclass
  public static final PyType FLOAT = new PyType("float", OBJECT);
  public static final PyType STR = new PyType("str", OBJECT);
  public static final PyType NONE_TYPE = new PyType("NoneType", OBJECT);
  public static final PyType TUPLE = new PyType("tuple", OBJECT);
  public static final PyType LIST = new PyType("list", OBJECT);
  public static final PyType DICT = new PyType("dict", OBJECT);
  public static final PyType RANGE = new PyType("range", OBJECT);
  public static final PyType SLICE = new PyType("slice", OBJECT);
  public static final PyType FUNCTION = new PyType("function", OBJECT);
  public static final PyType BUILTIN_FUNCTION = new PyType("builtin_function_or_method", OBJECT);

  private final String name;
  private final PyType base;

  private PyType(String name, PyType base) {
    this.name = name;
    this.base = base;
  }

  public boolean isSubtypeOf(PyType other) {
    Objects.requireNonNull(other);
    for (var t = this; t != null; t = t.base) {
      if (t == other) {
        return true;
      }
    }
    return false;
  }

  @Override
  public String toString() { return "<class '" + name + "'>"; }
}