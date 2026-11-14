package com.github.forax.orvet.rt.error;

import java.io.Serial;

/// Runtime representation of the Python ValueError exception.
public final class PyValueError extends PyException {
  @Serial
  private static final long serialVersionUID = 1L;

  public PyValueError(String message) {
    super(message);
  }

  public PyValueError(String message, Throwable cause) {
    super(message, cause);
  }
}
