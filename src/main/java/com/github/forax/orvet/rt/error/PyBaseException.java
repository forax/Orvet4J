package com.github.forax.orvet.rt.error;

import java.io.Serial;

/// Runtime representation of the Python BaseException exception
public sealed class PyBaseException extends RuntimeException permits PyException {
  @Serial
  private static final long serialVersionUID = 1L;

  public PyBaseException(String message) {
    super(message);
  }

  public PyBaseException(String message, Throwable cause) {
    super(message, cause);
  }
}
