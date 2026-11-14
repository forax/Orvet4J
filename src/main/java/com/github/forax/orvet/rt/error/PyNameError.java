package com.github.forax.orvet.rt.error;

import java.io.Serial;

/// Runtime representation of the Python NameError exception.
public final class PyNameError extends PyException {
  @Serial
  private static final long serialVersionUID = 1L;

  public PyNameError(String message) {
    super(message);
  }

  public PyNameError(String message, Throwable cause) {
    super(message, cause);
  }
}
