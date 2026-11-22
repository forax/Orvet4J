package com.github.forax.orvet.rt;

import com.github.forax.orvet.rt.error.PyAttributeError;
import com.github.forax.orvet.rt.error.UnimplementedFeatureError;
import com.github.forax.orvet.rt.intrinsic.DictIntrinsics;
import com.github.forax.orvet.rt.intrinsic.IntrinsicSupport;
import com.github.forax.orvet.rt.intrinsic.ListIntrinsics;
import com.github.forax.orvet.rt.intrinsic.StringIntrinsics;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.invoke.MethodHandles.dropArguments;
import static java.lang.invoke.MethodHandles.exactInvoker;
import static java.lang.invoke.MethodHandles.foldArguments;
import static java.lang.invoke.MethodHandles.guardWithTest;
import static java.lang.invoke.MethodHandles.throwException;
import static java.lang.invoke.MethodType.methodType;

final class MethodSupport {
  private MethodSupport() { throw new AssertionError(); }

  static final class MethodCallSite extends MutableCallSite {
    private static final MethodHandles.Lookup LOOKUP;
    private static final MethodHandle FALLBACK, TYPE_CHECK, INSTANCE_CHECK;
    static {
      var lookup = MethodHandles.lookup();
      LOOKUP = lookup;
      try {
        FALLBACK = lookup.findVirtual(MethodCallSite.class, "fallback",
            methodType(MethodHandle.class, Object.class));
        TYPE_CHECK = lookup.findStatic(MethodCallSite.class, "typeCheck",
            methodType(boolean.class, Class.class, Object.class));
        INSTANCE_CHECK = lookup.findStatic(MethodCallSite.class, "instanceCheck",
            methodType(boolean.class, Object.class, Object.class));
      } catch (NoSuchMethodException | IllegalAccessException e) {
        throw new AssertionError(e);
      }
    }

    private record MethodKey(String name, MethodType type) {}

    private static final ClassValue<ConcurrentHashMap<MethodKey, MethodHandle>> METHOD_CACHE = new ClassValue<>() {
      @Override
      protected ConcurrentHashMap<MethodKey, MethodHandle> computeValue(Class<?> type) {
        return new ConcurrentHashMap<>();
      }
    };

    private final String name;

    private MethodCallSite(MethodType type, String name) {
      this.name = name;
      super(type);
      setTarget(foldArguments(exactInvoker(type), FALLBACK.bindTo(this)));
    }

    private MethodHandle fallback(Object receiver) {
      var receiverClass = receiver.getClass();
      var intrinsicAndTest = intrinsicForClass(receiver);
      var methodType = MethodType.genericMethodType(type().parameterCount())
          .changeParameterType(0, receiverClass);

      var mh = resolveAndCache(intrinsicAndTest.intrinsicClass, name, methodType);
      if (mh == null) {
        throw new PyAttributeError("'" + IntrinsicSupport.typeName(receiver) +
            "' object has no attribute '" + name + "'");
      }

      var test = intrinsicAndTest.test;
      var target = mh.asType(type());
      var guard = guardWithTest(test, target, new MethodCallSite(type(), name).dynamicInvoker());
      setTarget(guard);

      return target;
    }

    private static boolean typeCheck(Class<?> expectedClass, Object receiver) {
      return expectedClass == receiver.getClass();
    }
    private static boolean instanceCheck(Object expected, Object receiver) {
      return expected == receiver;
    }

    private static MethodHandle resolveAndCache(Class<?> intrinsic, String name, MethodType methodType) {
      var methodMap = METHOD_CACHE.get(intrinsic);
      var key = new MethodKey(name, methodType);
      var mh = methodMap.get(key);
      if (mh != null) {
        return mh;
      }
      mh = resolve(intrinsic, name, methodType);
      if (mh == null) {
        return null;
      }
      var existingMH = methodMap.putIfAbsent(key, mh);
      if (existingMH == null) {
        return mh;
      }
      // the methodMap has been updated by another thread
       return existingMH;
    }

    private static MethodHandle lookup(Class<?> intrinsic, String name, MethodType methodType) {
      try {
        return LOOKUP.findStatic(intrinsic, "_" + name + "_", methodType);
      } catch (IllegalAccessException e) {
        throw new AssertionError(e);
      } catch (NoSuchMethodException _) {
        return null;
      }
    }

    private static MethodHandle resolve(Class<?> intrinsic, String name, MethodType methodType) {
      // try with a direct signature
      var mh = lookup(intrinsic, name, methodType);
      if (mh != null) {
        return mh;
      }
      // try with a varargs signature
      return lookup(intrinsic, name, MethodType.methodType(methodType.returnType(),
          methodType.parameterType(0), Object[].class));
    }

    static CallSite methodCall(String name, MethodType type, int positionalCount, String... keywordNames) {
      if (keywordNames.length != 0) {
        return new ConstantCallSite(throwMH(type,
            new UnimplementedFeatureError("method calls with keyword arguments are not supported yet")));
      }
      return new MethodCallSite(type, name);
    }

    private record IntrinsicAndTest(Class<?> intrinsicClass, MethodHandle test) {}

    private static IntrinsicAndTest intrinsicClass(Class<?> intrinsicClass, Object receiver) {
      return new IntrinsicAndTest(intrinsicClass, TYPE_CHECK.bindTo(receiver.getClass()));
    }
    private static IntrinsicAndTest intrinsicPyType(Class<?> intrinsicClass, PyType pyType) {
      return new IntrinsicAndTest(intrinsicClass, INSTANCE_CHECK.bindTo(pyType));
    }

    private IntrinsicAndTest intrinsicForClass(Object receiver) {
      return switch (receiver) {
        case String _ -> intrinsicClass(StringIntrinsics.class, receiver);
        case PyList<?> _ -> intrinsicClass(ListIntrinsics.class, receiver);
        case PyDict<?, ?> _ -> intrinsicClass(DictIntrinsics.class, receiver);
        case PyType type -> switch (type) {
          case PyType.DICT -> intrinsicPyType(DictIntrinsics.class, type);
          default -> throw new PyAttributeError("'" + type.function().name() + "' has no attribute '" + name + "'");
        };
        default -> throw new PyAttributeError("'" + IntrinsicSupport.typeName(receiver) + "' object has no attribute '" + name + "'");
      };
    }

    private static MethodHandle throwMH(MethodType type, Throwable throwable) {
      return dropArguments(
          throwException(type.returnType(), throwable.getClass()).bindTo(throwable),
          0, type.parameterArray());
    }
  }
}