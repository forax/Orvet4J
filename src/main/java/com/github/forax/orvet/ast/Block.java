package com.github.forax.orvet.ast;

import java.util.List;

/** A suite of statements. {@code pass} produces no statement, so an empty block is legal. */
public record Block(List<Stmt> stmts, int line, int column) {
  public Block {
    stmts = List.copyOf(stmts);
  }
}