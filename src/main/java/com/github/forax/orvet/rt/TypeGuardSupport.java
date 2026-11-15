package com.github.forax.orvet.rt;

import java.lang.invoke.CallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;

final class TypeGuardSupport {
  private TypeGuardSupport() {
    throw new AssertionError();
  }

  static final class TypeGuardCallSite extends MutableCallSite {
    private static final MethodType TYPE = MethodType.methodType(Object.class, Object.class);
    private static final MethodHandle IDENTITY = MethodHandles.identity(Object.class);
    private static final MethodHandle FALLBACK, TYPE_CHECK;
    static {
      var lookup = MethodHandles.lookup();
      try {
        FALLBACK = lookup.findVirtual(TypeGuardCallSite.class, "fallback", TYPE);
        TYPE_CHECK = lookup.findStatic(TypeGuardCallSite.class, "typeCheck",
            MethodType.methodType(boolean.class, Class.class, Object.class));
      } catch (NoSuchMethodException | IllegalAccessException e) {
        throw new AssertionError(e);
      }
    }

    private TypeGuardCallSite() {
      super(TYPE);
      setTarget(FALLBACK.bindTo(this));
    }

    private static boolean typeCheck(Class<?> type, Object object) {
      return object.getClass() == type;
    }

    private Object fallback(Object object) {
      var type = object.getClass();

      var test = TYPE_CHECK.bindTo(type);
      var target = IDENTITY
          .asType(MethodType.methodType(type, Object.class))
          .asType(TYPE);
      var guard = MethodHandles.guardWithTest(test, target, IDENTITY);
      setTarget(guard);

      return object;
    }
  }

  static CallSite typeGuard() {
    return new TypeGuardCallSite();
  }
}
