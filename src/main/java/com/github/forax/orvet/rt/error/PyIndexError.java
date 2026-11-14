package com.github.forax.orvet.rt.error;

import java.io.Serial;

/// Runtime representation of the Python IndexError exception.
public final class PyIndexError extends PyLookupError {
  @Serial
  private static final long serialVersionUID = 1L;

  public PyIndexError(String message) {
    super(message);
  }

  public PyIndexError(String message, Throwable cause) {
    super(message, cause);
  }
}
