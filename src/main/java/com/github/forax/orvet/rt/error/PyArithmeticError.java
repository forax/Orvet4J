package com.github.forax.orvet.rt.error;

import java.io.Serial;

/// Runtime representation of the Python ArithmeticError exception.
public sealed class PyArithmeticError extends PyException permits PyOverflowError, PyZeroDivisionError {
  @Serial
  private static final long serialVersionUID = 1L;

  public PyArithmeticError(String message) {
    super(message);
  }

  public PyArithmeticError(String message, Throwable cause) {
    super(message, cause);
  }
}
