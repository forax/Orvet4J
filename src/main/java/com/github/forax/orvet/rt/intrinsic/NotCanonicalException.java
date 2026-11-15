package com.github.forax.orvet.rt.intrinsic;

import java.io.Serial;

/// A Java object that is not a canonical runtime value reached the runtime.
public final class NotCanonicalException extends RuntimeException {
  @Serial
  private static final long serialVersionUID = 1L;

  public NotCanonicalException(String message) {
    super(message);
  }

  public NotCanonicalException(String message, Object value) {
    super(message + ": " + name(value));
  }

  private static String name(Object value) {
    return value == null ? "null" : value.getClass().getName();
  }
}