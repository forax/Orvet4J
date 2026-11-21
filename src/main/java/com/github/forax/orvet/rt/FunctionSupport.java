package com.github.forax.orvet.rt;

import com.github.forax.orvet.ast.Stmt;
import com.github.forax.orvet.engine.BytecodeGenerator;
import com.github.forax.orvet.engine.ScopeAnalyzer;
import com.github.forax.orvet.rt.error.PyAttributeError;
import com.github.forax.orvet.rt.error.PyTypeError;
import com.github.forax.orvet.rt.error.UnimplementedFeatureError;
import com.github.forax.orvet.rt.intrinsic.DictIntrinsics;
import com.github.forax.orvet.rt.intrinsic.IntrinsicSupport;
import com.github.forax.orvet.rt.intrinsic.ListIntrinsics;
import com.github.forax.orvet.rt.intrinsic.StringIntrinsics;
import com.github.forax.orvet.rt.intrinsic.TupleIntrinsics;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import java.lang.invoke.VarHandle;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import static java.lang.invoke.MethodHandles.dropArguments;
import static java.lang.invoke.MethodHandles.exactInvoker;
import static java.lang.invoke.MethodHandles.foldArguments;
import static java.lang.invoke.MethodHandles.guardWithTest;
import static java.lang.invoke.MethodHandles.throwException;
import static java.lang.invoke.MethodType.methodType;

/// Runtime support for Python function definitions, lazy compilation, and dynamic invocations.
///
/// ## Calling Conventions
/// Every compiled or native function entry point adheres to one of two signatures:
/// - **Python Function:**
///   ```
///   (captures..., PyFunction self, Object p0..pn-1 [, PyTuple rest]) -> Object
///   ```
///   Captured variables precede `self`. When the function is variadic, excess arguments
///   are collected into an immutable [PyTuple].
/// - **Built-in / Native Function:**
///   ```
///   (PyFunction self, Object p0..pn-1 [, Object[] rest]) -> Object
///   ```
///   Built-ins do not capture variables. When variadic, excess arguments are delivered
///   as a raw `Object[]` array.
///
/// ## Dispatch Pipeline
/// Function call dispatch uses a two-tier strategy:
/// 1. **Polymorphic Inline Cache ([FunctionCallSite]):** Call sites maintain a bounded
///    cascade of [java.lang.invoke.MethodHandles#guardWithTest] guards (up to depth 4).
/// 2. **Megamorphic Fallback ([Megamorphic]):** Call sites exceeding the
///    polymorphic depth threshold are flattened into a single collector that dynamically
///    dispatches via uniform thunks without cache thrashing.
final class FunctionSupport {
  private FunctionSupport() { throw new AssertionError(); }

  /// An invokedynamic [MutableCallSite] managing function invocation and inline caching.
  ///
  /// ## Function Creation
  /// ```
  /// (Object[] captures) -> Object
  /// ```
  static final class DefCallSite {
    private DefCallSite() {
      throw new AssertionError();
    }

    //                    invokedynamic #def : (captures...) -> Object
    //                                          |
    //                                          v
    //          DefCallSite.def(ModuleContext, Dictionary, MethodType, int astIndex)
    //                        functionDef = dictionary.lookup(astIndex)
    //                                          |
    //                +-------------------------+-------------------------+
    //                |                                                   |
    //                v (captureCount == 0)                               v (captureCount > 0)
    //     Stateless / Zero-Capture                            Lexical Closure Factory
    //                |                                                   |
    //                v                                                   v
    //    Pre-allocate PyFunction:                            Bind Constructor into MH:
    //    new PyFunction(code, EMPTY)                         PY_FUNCTION.bindTo(code)
    //                |                                                   |
    //                v                                                   v
    //    MethodHandles.constant(...)                         asCollector(Object[].class, k)
    //                |                                                   |
    //                v                                                   v
    //        ConstantCallSite                                     ConstantCallSite
    // (Always returns same instance)                     (Invokes factory on each execution)

    private static final MethodHandle PY_FUNCTION;
    static {
      var lookup = MethodHandles.lookup();
      try {
        PY_FUNCTION = lookup.findConstructor(PyFunction.class,
            methodType(void.class, PyFunction.Code.class, Object[].class));
      } catch (NoSuchMethodException | IllegalAccessException e) {
        throw new AssertionError(e);
      }
    }

    private static final Object[] EMPTY_CAPTURED_ARGS = new Object[0];

    static CallSite def(ModuleContext context, Dictionary dictionary, MethodType type, int astIndex) {
      var def = dictionary.lookup(astIndex);

      var functionName = def.name();
      var parameters = def.parameters().stream().map(Stmt.FunctionDef.Param::name).toList();
      var hasRestParameter = def.rest().isPresent();
      var captureCount = type.parameterCount();
      var code = new PyFunction.Code(functionName, parameters, hasRestParameter, captureCount, context, def);

      // CPython, creates a fresh function object each time it is executed
      var factory = PY_FUNCTION.bindTo(code);
      var mh = captureCount == 0
          ? factory.bindTo(EMPTY_CAPTURED_ARGS)
          : factory.asCollector(Object[].class, captureCount);
      var target = mh.asType(type);
      return new ConstantCallSite(target);
    }
  }

  /// Handles megamorphic function calls when a call site exceeds the PIC depth limit.
  ///
  /// Also serves as the backend execution engine for direct reflection-like calls
  /// via [PyFunction#call(Object...)].
  ///
  /// ## Universal Thunk
  /// Every function entry is adapted into a universal thunk:
  /// ```
  /// (Object[] captured, PyFunction self, Object[] args) -> Object
  /// ```
  static final class Megamorphic {
    private Megamorphic() {
      throw new AssertionError();
    }

    //                  Call Site: fn(arg0, arg1, arg2...)
    //                                 |
    //                                 v
    //                 asCollector(Object[].class, N)
    //      Packs all call-site arguments into a single Object[] args
    //                                 |
    //                                 v
    //           Does the call site provide keyword arguments?
    //                                 |
    //               +-----------------+-----------------+
    //               | NO                                | YES
    //               v                                   v
    //    Megamorphic.megamorphic          Megamorphic.megamorphicWithKeywords
    //         (callee, args)               (callee, args, posCount, kwNames)
    //               |                                   |
    //               |                                   v
    //               |                        reorderKeywordArguments(...)
    //               |                        Computes canonical slot plan
    //               |                                   |
    //               |                                   v
    //               |                       Allocate: Object[canonicalSize]
    //               |                         ordered[plan[i]] = args[i]
    //               |                                   |
    //               v                                   v
    //       args (canonical)                     ordered (canonical)
    //               |                                   |
    //               +-----------------+-----------------+
    //                                 |
    //                                 v
    //         Validate: asFunction(callee), checkArity(function, len)
    //                                 |
    //                                 v
    //                         getThunkEntry(function)
    //            (Retrieves or lazily constructs the uniform thunk)
    //                                 |
    //                                 v
    //     thunkEntry.invokeExact(function.capturedArgs, function, args_ordered)

    static final MethodHandle MEGAMORPHIC, MEGAMORPHIC_WITH_KEYWORDS;
    private static final MethodHandle ARRAY_ELEMENT, REST_TUPLE, BUILTIN_REST;
    static {
      var lookup = MethodHandles.lookup();
      ARRAY_ELEMENT = MethodHandles.arrayElementGetter(Object[].class);
      try {
        MEGAMORPHIC = lookup.findStatic(Megamorphic.class, "megamorphic",
            methodType(Object.class, Object.class, Object[].class));
        MEGAMORPHIC_WITH_KEYWORDS = lookup.findStatic(Megamorphic.class, "megamorphicWithKeywords",
            methodType(Object.class, Object.class, Object[].class, int.class, String[].class));
        REST_TUPLE = lookup.findStatic(Megamorphic.class, "restTuple",
            methodType(PyTuple.class, Object[].class, int.class));
        BUILTIN_REST = lookup.findStatic(Megamorphic.class, "builtinRest",
            methodType(Object[].class, Object[].class, int.class));
      } catch (NoSuchMethodException | IllegalAccessException e) {
        throw new AssertionError(e);
      }
    }

    /// Entry point for megamorphic calls with purely positional arguments.
    ///
    /// @param callee the target function (must be a [PyFunction])
    /// @param args positional arguments array
    /// @return return value of the function invocation
    /// @throws Throwable if invocation or argument validation fails
    static Object megamorphic(Object callee, Object[] args) throws Throwable {
      var function = asFunction(callee);
      checkArity(function, args.length);
      var thunkEntry = getThunkEntry(function);
      return thunkEntry.invokeExact(function.capturedArgs, function, args);
    }

    /// Entry point for megamorphic calls with keyword arguments.
    ///
    /// @param callee the target function (must be a [PyFunction])
    /// @param args argument array holding positional arguments followed by keyword values
    /// @param positionalCount number of positional arguments in `args`
    /// @param keywordNames names of the keyword arguments
    /// @return return value of the function invocation
    /// @throws Throwable if invocation or keyword reordering fails
    static Object megamorphicWithKeywords(Object callee, Object[] args,
                                          int positionalCount, String[] keywordNames) throws Throwable {
      var function = asFunction(callee);
      var plan = reorderKeywordArguments(function, positionalCount, keywordNames);
      var ordered = new Object[Math.max(positionalCount, function.arity())];
      for (var i = 0; i < plan.length; i++) {
        ordered[plan[i]] = args[i];
      }
      var thunkEntry = getThunkEntry(function);
      return thunkEntry.invokeExact(function.capturedArgs, function, ordered);
    }

    private static MethodHandle getThunkEntry(PyFunction function) {
      var code = function.code;
      var thunkEntry = code.thunkEntry;  // racy read
      if (thunkEntry != null) {
        return thunkEntry;
      }
      thunkEntry = createThunk(FunctionCallSite.getCompiledEntry(code), code);  // idempotent
      code.thunkEntry = thunkEntry;  // racy write
      return thunkEntry;
    }

    /// Creates a universal thunk handle adapting the compiled entry to
    /// `(Object[] captured, PyFunction self, Object[] args) -> Object`.
    private static MethodHandle createThunk(MethodHandle compiledEntry, PyFunction.Code code) {
      // 1. (Object[] captured, PyFunction self, <declared parameters> [, rest])
      var captureCount = code.captureCount();
      var handle = captureCount == 0
          ? dropArguments(compiledEntry, 0, Object[].class)
          : compiledEntry.asSpreader(0, Object[].class, captureCount);

      // 2. No rest parameter: spread the declared parameters (or ignore the array if there is none).
      var arity = code.parameters().size();
      if (!code.isVariadic()) {
        return arity == 0
            ? dropArguments(handle, 2, Object[].class)
            : handle.asSpreader(2, Object[].class, arity);
      }

      // 3. Rest parameter (a PyTuple for Python code, an Object[] for a builtin):
      //    read the declared parameters one by one and pack the remaining arguments.
      var packer = code.isBuiltin() ? BUILTIN_REST : REST_TUPLE;
      var filters = new MethodHandle[arity + 1];
      for (var i = 0; i < arity; i++) {
        filters[i] = MethodHandles.insertArguments(ARRAY_ELEMENT, 1, i);
      }
      filters[arity] = MethodHandles.insertArguments(packer, 1, arity);
      var filtered = MethodHandles.filterArguments(handle, 2, filters);

      // 4. Every filtered parameter reads the same args array.
      var type = methodType(Object.class, Object[].class, PyFunction.class, Object[].class);
      var reorder = new int[filtered.type().parameterCount()];
      reorder[0] = 0;  // captured
      reorder[1] = 1;  // self
      Arrays.fill(reorder, 2, reorder.length, 2);  // args
      return MethodHandles.permuteArguments(filtered, type, reorder);
    }

    private static PyTuple restTuple(Object[] args, int start) {
      return TupleIntrinsics.fromArrayRange(args, start);
    }

    /// The array is handed to the builtin as is when there is no declared parameter:
    /// builtin functions must neither retain nor modify it.
    private static Object[] builtinRest(Object[] args, int start) {
      return start == 0 ? args : Arrays.copyOfRange(args, start, args.length);
    }
  }

  /// Adapter factory optimizing variadic (`*rest`) argument packing into [PyTuple] instances.
  ///
  /// When a compiled Python function declares a rest parameter, its method handle expects
  /// a [PyTuple] at the designated parameter index. Instead of allocating an intermediate
  /// `Object[]` array on every invocation, this adapter selects the most efficient tuple constructor:
  ///
  /// This design allows HotSpot C2 to perform escape analysis and completely eliminate heap
  /// allocations for short variadic argument lists.
  private static final class RestAdapter {
    private RestAdapter() {
      throw new AssertionError();
    }

    private static final PyTuple TUPLE_EMPTY = TupleIntrinsics.fromArray();
    private static final MethodHandle TUPLE1, TUPLE2, TUPLE3, TUPLE4, TUPLE_N;

    static {
      var lookup = MethodHandles.lookup();
      try {
        // collectArguments requires the filter's return type to be exactly the target parameter type (PyTuple),
        // hence the asType that widens PyTupleK to PyTuple
        TUPLE1 = lookup.findConstructor(PyTuple.PyTuple1.class, methodType(void.class, Object.class))
            .asType(methodType(PyTuple.class, Object.class));
        TUPLE2 = lookup.findConstructor(PyTuple.PyTuple2.class, methodType(void.class, Object.class, Object.class))
            .asType(methodType(PyTuple.class, Object.class, Object.class));
        TUPLE3 = lookup.findConstructor(PyTuple.PyTuple3.class, methodType(void.class, Object.class, Object.class, Object.class))
            .asType(methodType(PyTuple.class, Object.class, Object.class, Object.class));
        TUPLE4 = lookup.findConstructor(PyTuple.PyTuple4.class, methodType(void.class, Object.class, Object.class, Object.class, Object.class))
            .asType(methodType(PyTuple.class, Object.class, Object.class, Object.class, Object.class));
        TUPLE_N = lookup.findConstructor(PyTuple.PyTupleN.class, methodType(void.class, Object[].class))
            .asType(methodType(PyTuple.class, Object[].class));
      } catch (NoSuchMethodException | IllegalAccessException e) {
        throw new AssertionError(e);
      }
    }

    /// Adapts `entry` by replacing the rest parameter at `position` with `restCount`
    /// positional argument slots packed into a [PyTuple].
    ///
    /// @param entry the method handle with signature `(..., PyTuple rest) -> Object`
    /// @param position the zero-based parameter index of the rest parameter
    /// @param restCount the number of extra arguments passed at the call site
    /// @return the adapted method handle
    static MethodHandle adapt(MethodHandle entry, int position, int restCount) {
      return switch (restCount) {
        case 0 -> MethodHandles.insertArguments(entry, position, TUPLE_EMPTY);
        case 1 -> MethodHandles.collectArguments(entry, position, TUPLE1);
        case 2 -> MethodHandles.collectArguments(entry, position, TUPLE2);
        case 3 -> MethodHandles.collectArguments(entry, position, TUPLE3);
        case 4 -> MethodHandles.collectArguments(entry, position, TUPLE4);
        default -> MethodHandles.collectArguments(entry, position,
            TUPLE_N.asCollector(Object[].class, restCount));
      };
    }
  }

  static final class FunctionCallSite extends MutableCallSite {
    private static final MethodHandles.Lookup LOOKUP;
    private static final MethodHandle FALLBACK, SAME_CALLEE, SAME_CODE, CAPTURED_ARGS;
    private static final VarHandle NEXT;
    static {
      var lookup = MethodHandles.lookup();
      LOOKUP = lookup;
      try {
        FALLBACK = lookup.findVirtual(FunctionCallSite.class, "fallback",
            methodType(MethodHandle.class, Object.class));
        SAME_CALLEE = lookup.findStatic(FunctionCallSite.class, "sameCallee",
            methodType(boolean.class, Object.class, Object.class));
        SAME_CODE = lookup.findStatic(FunctionCallSite.class, "sameCode",
            methodType(boolean.class, PyFunction.Code.class, Object.class));
        CAPTURED_ARGS = lookup.findVirtual(PyFunction.class, "capturedArgs",
            methodType(Object[].class));
        NEXT = lookup.findVarHandle(FunctionCallSite.class, "next", NextCallSite.class);
      } catch (NoSuchMethodException | IllegalAccessException | NoSuchFieldException e) {
        throw new AssertionError(e);
      }
    }

    static CallSite call(MethodType type, int positionalCount, String[] keywordNames) {
      return new FunctionSupport.FunctionCallSite(type, positionalCount, keywordNames, null, 0);
    }

    // A link list of all guarded call sites
    // code of call site guarded by SAME_CALLEE on a PyFunction, null otherwise
    private record NextCallSite(FunctionCallSite site, PyFunction.Code code) {}

    private static final int MAX_DEPTH = 4;

    private final int positionalCount;
    private final String[] keywordNames;
    private final FunctionCallSite root;
    private final int depth;

    private volatile NextCallSite next;

    private FunctionCallSite(MethodType type, int positionalCount, String[] keywordNames, FunctionCallSite root, int depth) {
      this.positionalCount = positionalCount;
      this.keywordNames = keywordNames;
      this.depth = depth;
      super(type);
      this.root = root == null ? this : root;
      setTarget(foldArguments(exactInvoker(type()), FALLBACK.bindTo(this)));
    }

    private MethodHandle fallback(Object callee) {
      // 1. Check the callee is callable
      var function = asFunction(callee);
      var argCount = type().parameterCount() - 1;

      // 2. check if there is not an existing call site to update (promote)
      var code = function.code;
      var promote = (FunctionCallSite) null;
      if (code.captureCount() == 0) {
        for (var site = root;;) {
          var next = site.next;  // volatile read
          if (next == null) {
            break;
          }
          if (next.code == code) {
            promote = site;
            break;
          }
          site = next.site;
        }
      }

      // 3. Megamorphic: the arguments are validated at each call
      if (promote == null && depth == MAX_DEPTH) {
        var entryPoint = keywordNames.length == 0
            ? Megamorphic.MEGAMORPHIC
            : MethodHandles.insertArguments(Megamorphic.MEGAMORPHIC_WITH_KEYWORDS, 2, positionalCount, keywordNames);
        var target = entryPoint.asCollector(Object[].class, argCount);
        root.setTarget(target);
        next = null;  // volatile write, do not keep the linked list
        return target;
      }

      // 4. Validate the arguments before compiling the callee
      int[] reorderPlan;
      if (keywordNames.length == 0) {
        checkArity(function, argCount);
        reorderPlan = null;
      } else {
        reorderPlan = reorderKeywordArguments(function, positionalCount, keywordNames);
      }

      // 5. Entry of the callee: (Object callee | PyFunction self, <declared parameters> [, rest])
      MethodHandle target, test;
      if (promote == null && code.captureCount() == 0) {
        test = SAME_CALLEE.bindTo(function);
        target = getNoCaptureMH(function);
      } else {
        test = SAME_CODE.bindTo(code);
        target = getEntry(code);
      }

      // 6. Adapt to the call site
      var arity = function.arity();
      var adapted = reorderPlan == null
          ? adapt(function, target, 1 + arity, argCount - arity).asType(type())
          : adaptKeywords(function, target, type(), reorderPlan);

      // 7. Change the target of the existing call site
      if (promote != null) {
        var fallback = promote.next.site.dynamicInvoker();
        promote.setTarget(guardWithTest(test, adapted, fallback));
        return adapted;
      }

      // 8. Install the inlining cache
      var nextSite = new FunctionCallSite(type(), positionalCount, keywordNames, root, depth + 1);
      var fallback = nextSite.dynamicInvoker();
      var next = new NextCallSite(nextSite,
          code.captureCount() == 0 && callee instanceof PyFunction ? code : null);
      if (NEXT.compareAndSet(this, null, next)) {
        setTarget(guardWithTest(test, adapted, fallback));
      }
      return adapted;
    }

    static MethodHandle getNoCaptureMH(PyFunction function) {
      var compiledEntry = getCompiledEntry(function.code);
      var bound = compiledEntry.bindTo(function);
      return dropArguments(bound, 0, Object.class);  // drop the callee
    }

    static MethodHandle getEntry(PyFunction.Code code) {
      var entry = code.entry;  // racy read
      if (entry != null) {
        return entry;
      }
      var compiledEntry = getCompiledEntry(code);

      var captureCount = code.captureCount();  // != 0
      var spread = compiledEntry.asSpreader(0, Object[].class, captureCount);     // (Object[], PyFunction, ...)
      entry = foldArguments(spread, CAPTURED_ARGS);
      code.entry = entry;  // racy write
      return entry;
    }

    /// Compiles a function body lazily if not already compiled.
    /// @param code the function code metadata
    /// @return the compiled method handle entry point
    static MethodHandle getCompiledEntry(PyFunction.Code code) {
      // We do not want to compile the function several times (synchronized + Double Check Locking)
      var compiledEntry = code.compiledEntry;  // volatile read
      if (compiledEntry != null) {
        return compiledEntry;
      }
      if (code.isBuiltin()) {
        var mh = Builtins.resolve(code);
        if (code.specializationContext() != null) {
          return mh;  // specializable function are not shared
        }
        return code.compiledEntry = mh;  // idempotent, volatile write
      }
      synchronized (code) {
        compiledEntry = code.compiledEntry;  // volatile read
        if (compiledEntry != null) {
          return compiledEntry;
        }
        compiledEntry = compileBody(code.context(), code.functionDef());
        code.compiledEntry = compiledEntry;  // volatile write
        return compiledEntry;
      }
    }

    private static boolean sameCallee(Object expected, Object callee) {
      return callee == expected;
    }

    private static boolean sameCode(PyFunction.Code expected, Object callee) {
      return callee instanceof PyFunction f && f.code == expected;
    }

    private static MethodHandle compileBody(ModuleContext context, Stmt.FunctionDef def) {
      var scope = context.scopeFor(def);
      ScopeAnalyzer.checkDefiniteAssignment(scope);

      var dictionary = new Dictionary();
      var bytes = BytecodeGenerator.generateFunction(context, dictionary, def, scope);

      var className = BytecodeGenerator.functionClassName(def).replace('/', '.');
      var clazz = RT.defineClass(context, dictionary, className, bytes);

      var captureCount = BytecodeGenerator.captureNames(def, scope.analysis()).size();
      var type = BytecodeGenerator.bodyType(captureCount, def.parameters().size(), def.rest().isPresent());
      try {
        return LOOKUP.findStatic(clazz, def.name(), type);
      } catch (NoSuchMethodException | IllegalAccessException e) {
        throw new AssertionError(e);
      }
    }

    /// Adapts the entry to `(X, Object x (arity + restCount)) -> Object`: the rest parameter, at `position`,
    /// is replaced by `restCount` parameters (packed into a PyTuple for a Python function, into an Object[]
    /// for a builtin one).
    static MethodHandle adapt(PyFunction function, MethodHandle entry, int position, int restCount) {
      var code = function.code;
      if (!code.isVariadic()) {
        return entry;  // exact arity: no adaptation at all
      }
      if (code.isBuiltin()) {
        return entry.asCollector(position, Object[].class, restCount);
      }
      return RestAdapter.adapt(entry, position, restCount);
    }

    /// Adapts the entry to the call site, permuting the arguments according to the plan.
    private MethodHandle adaptKeywords(PyFunction function, MethodHandle entry, MethodType type, int[] plan) {
      var arity = function.arity();
      var restCount = Math.max(0, positionalCount - arity);

      // reorder[j] = position in the call site of the j-th parameter of the adapted entry (the callee stays first)
      var reorder = new int[1 + arity + restCount];
      reorder[0] = 0;
      for (var i = 0; i < plan.length; i++) {
        reorder[1 + plan[i]] = 1 + i;
      }

      var adapted = adapt(function, entry, 1 + arity, restCount).asType(type);
      return MethodHandles.permuteArguments(adapted, type, reorder);
    }
  }


  static final class MethodCallSite extends MutableCallSite {
    private static final MethodHandles.Lookup LOOKUP;
    private static final MethodHandle FALLBACK, TYPE_CHECK;
    static {
      var lookup = MethodHandles.lookup();
      LOOKUP = lookup;
      try {
        FALLBACK = lookup.findVirtual(MethodCallSite.class, "fallback",
            methodType(MethodHandle.class, Object.class));
        TYPE_CHECK = lookup.findStatic(MethodCallSite.class, "typeCheck",
            methodType(boolean.class, Class.class, Object.class));
      } catch (NoSuchMethodException | IllegalAccessException e) {
        throw new AssertionError(e);
      }
    }

    private static final ClassValue<ConcurrentHashMap<String, MethodHandle>> METHOD_CACHE = new ClassValue<>() {
      @Override
      protected ConcurrentHashMap<String, MethodHandle> computeValue(Class<?> type) {
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
      var intrinsic = intrinsicForClass(receiver);
      var methodType = MethodType.genericMethodType(type().parameterCount())
          .changeParameterType(0, receiverClass);

      var mh = resolveAndCache(intrinsic, name, methodType);
      if (mh == null) {
        throw new PyAttributeError("'" + IntrinsicSupport.typeName(receiver) +
            "' object has no attribute '" + name + "'");
      }

      var test = TYPE_CHECK.bindTo(receiverClass);
      var target = mh.asType(type());
      var guard = guardWithTest(test, target, new MethodCallSite(type(), name).dynamicInvoker());
      setTarget(guard);

      return target;
    }

    private static boolean typeCheck(Class<?> receiverClass, Object receiver) {
      return receiverClass == receiver.getClass();
    }

    private static MethodHandle resolveAndCache(Class<?> intrinsic, String name, MethodType methodType) {
      var methodMap = METHOD_CACHE.get(intrinsic);
      var key = name + "/" + methodType.parameterCount();
      var mh = methodMap.get(key);
      if (mh != null) {
        return mh;
      }
      mh = resolve(intrinsic, name, methodType);
      if (mh == null) {
        return null;
      }
      if (methodMap.putIfAbsent(key, mh) != null) {
        // the methodMap has been updated by another thread
        mh = methodMap.get(key);
      }
      return mh;
    }

    private static MethodHandle lookup(Class<?> intrinsic, String name, MethodType methodType) {
      try {
        return LOOKUP.findStatic(intrinsic, "_" + name + "_", methodType);
      } catch (IllegalAccessException e) {
        throw new AssertionError(e);
      } catch (NoSuchMethodException e) {
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

    private Class<?> intrinsicForClass(Object receiver) {
      return switch (receiver) {
        case String _ -> StringIntrinsics.class;
        case PyList<?> _ -> ListIntrinsics.class;
        case PyDict<?, ?> _ -> DictIntrinsics.class;
        case PyType type -> switch (type) {
          case PyType.DICT -> DictIntrinsics.class;
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

  // -- helpers

  static PyFunction asFunction(Object callee) {
    if (callee instanceof PyFunction function) {
      return function;
    }
    if (callee instanceof PyType type) {
      return type.function();
    }
    throw new PyTypeError("'" + IntrinsicSupport.typeName(callee) + "' object is not callable");
  }

  static void checkArity(PyFunction function, int argCount) {
    var arity = function.arity();
    if (function.code.isVariadic()) {
      if (argCount < arity) {
        throw arityError(function, argCount);
      }
    } else if (argCount != arity) {
      throw arityError(function, argCount);
    }
  }

  /// Computes the argument reordering plan mapping call-site arguments to canonical slots.
  /// @param function the target function
  /// @param positionalCount count of positional arguments
  /// @param keywordNames names of keyword arguments
  /// @return array where index `i` maps call-site argument `i` to its canonical slot
  /// @throws PyTypeError if keywords are unknown, duplicate, or missing required parameters
  static int[] reorderKeywordArguments(PyFunction function, int positionalCount, String[] keywordNames) {
    if (function.code.isBuiltin()) {
      throw builtinKeywords(function);
    }
    var parameters = function.parameters();
    var arity = parameters.size();

    var plan = new int[positionalCount + keywordNames.length];
    var assigned = new boolean[arity];
    for (var i = 0; i < positionalCount; i++) {
      plan[i] = i;
      if (i < arity) {
        assigned[i] = true;
      }
    }
    for (var i = 0; i < keywordNames.length; i++) {
      var name = keywordNames[i];
      var index = parameters.indexOf(name);
      if (index < 0) {
        throw unexpectedKeyword(function, name);
      }
      if (assigned[index]) {
        throw multipleValues(function, name);
      }
      assigned[index] = true;
      plan[positionalCount + i] = index;
    }

    var missing = (ArrayList<String>) null;
    for (var i = 0; i < arity; i++) {
      if (!assigned[i]) {
        if (missing == null) {
          missing = new ArrayList<>();
        }
        missing.add(parameters.get(i));
      }
    }
    if (missing != null) {
      throw missingArguments(function, missing);
    }
    return plan;
  }

  // -- errors

  private static PyTypeError arityError(PyFunction function, int given) {
    var arity = function.arity();
    if (given < arity) {
      return missingArguments(function, function.parameters().subList(given, arity));
    }
    return tooManyPositional(function, given);
  }

  /// Fixed-arity builtins are positional-only (as in CPython). Variadic builtins accept keywords in CPython
  /// (print(sep=...), int(x, base=...)) but we do not have keyword ABI for builtins yet.
  private static RuntimeException builtinKeywords(PyFunction function) {
    if (function.code.isVariadic()) {
      return new UnimplementedFeatureError("keyword arguments of the built-in function " + function.name() + "()");
    }
    return new PyTypeError(function.name() + "() takes no keyword arguments");
  }

  private static PyTypeError unexpectedKeyword(PyFunction function, String name) {
    return new PyTypeError(function.name() + "() got an unexpected keyword argument '" + name + "'");
  }

  private static PyTypeError multipleValues(PyFunction function, String name) {
    return new PyTypeError(function.name() + "() got multiple values for argument '" + name + "'");
  }

  private static PyTypeError missingArguments(PyFunction function, List<String> missing) {
    return new PyTypeError(function.name() + "() missing " + missing.size() + " required positional argument"
        + (missing.size() == 1 ? "" : "s") + ": " + joinNames(missing));
  }

  private static PyTypeError tooManyPositional(PyFunction function, int given) {
    var arity = function.arity();
    return new PyTypeError(function.name() + "() takes " + arity + " positional argument"
        + (arity == 1 ? "" : "s") + " but " + given + (given == 1 ? " was" : " were") + " given");
  }

  private static String joinNames(List<String> names) {
    return switch (names.size()) {
      case 1 -> "'" + names.get(0) + "'";
      case 2 -> "'" + names.get(0) + "' and '" + names.get(1) + "'";
      default -> {
        var sb = new StringBuilder();
        for (var i = 0; i < names.size() - 1; i++) {
          sb.append('\'').append(names.get(i)).append("', ");
        }
        yield sb.append("and '").append(names.getLast()).append('\'').toString();
      }
    };
  }
}