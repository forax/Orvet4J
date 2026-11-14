package com.github.forax.orvet.rt;

public final class PyNone {
  public static final PyNone INSTANCE = new PyNone();

  private PyNone() {
  }

  @Override
  public String toString() {
    return "None";
  }
}