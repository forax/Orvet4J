package com.github.forax.orvet.engine;

import com.github.forax.orvet.ast.ASTBuilder;
import com.github.forax.orvet.ast.Script;
import com.github.forax.orvet.rt.Dictionary;
import com.github.forax.orvet.rt.ModuleContext;
import com.github.forax.orvet.rt.RT;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.UndeclaredThrowableException;
import java.util.Objects;

/// Facade: source -> AST -> `Script_<n>` class -> run `__main__`.
public final class Engine {
  private Engine() { throw new AssertionError(); }

  private static final MethodHandles.Lookup LOOKUP = MethodHandles.lookup();

  /// Compiles the script eagerly and returns its `__main__`; function bodies are compiled on their first call.
  public static MethodHandle compile(ModuleContext context, Script script) {
    var internalName = "com/github/forax/orvet/gen/Script";
    var dictionary = new Dictionary();
    var bytes = BytecodeGenerator.generateScript(context, dictionary, script, internalName);

    var className = internalName.replace('/', '.');
    var clazz = RT.defineClass(context, dictionary, className, bytes);
    try {
      return LOOKUP.findStatic(clazz, "__main__", MethodType.methodType(void.class));
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new AssertionError(e);
    }
  }

  /// Runs a program and returns its module, whose globals can then be inspected.
  public static void run(ModuleContext context, String source) {
    Objects.requireNonNull(context);
    Objects.requireNonNull(source);
    var script = ASTBuilder.createScript(source);
    run(context, script);
  }

  public static void run(ModuleContext context, Script script) {
    Objects.requireNonNull(context);
    Objects.requireNonNull(script);
    var main = compile(context, script);
    try {
      main.invokeExact();
    } catch(RuntimeException | Error e) {
      throw e;
    } catch (Throwable e) {
      throw new UndeclaredThrowableException(e);
    }
  }
}