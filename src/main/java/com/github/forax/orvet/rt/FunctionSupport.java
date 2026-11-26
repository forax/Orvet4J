package com.github.forax.orvet.rt;

import com.github.forax.orvet.ast.Stmt;
import com.github.forax.orvet.engine.BytecodeGenerator;
import com.github.forax.orvet.engine.ScopeAnalyzer;
import com.github.forax.orvet.rt.error.PyTypeError;
import com.github.forax.orvet.rt.error.UnimplementedFeatureError;
import com.github.forax.orvet.rt.intrinsic.IntrinsicSupport;
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

import static java.lang.invoke.MethodHandles.arrayElementGetter;
import static java.lang.invoke.MethodHandles.collectArguments;
import static java.lang.invoke.MethodHandles.dropArguments;
import static java.lang.invoke.MethodHandles.exactInvoker;
import static java.lang.invoke.MethodHandles.filterArguments;
import static java.lang.invoke.MethodHandles.foldArguments;
import static java.lang.invoke.MethodHandles.guardWithTest;
import static java.lang.invoke.MethodHandles.identity;
import static java.lang.invoke.MethodHandles.insertArguments;
import static java.lang.invoke.MethodHandles.permuteArguments;
import static java.lang.invoke.MethodType.methodType;

/// Runtime support for Python function definitions, lazy compilation, and dynamic invocations.
///
/// ## Calling Conventions
/// Every compiled or native function entry point adheres to one of two signatures:
/// - **Python Function:**
///   ```
///   (captures..., PyFunction self, Object p0..pn-1 [, PyTuple rest] [, PyDict kwargs]) -> Object
///   ```
///   Captured variables precede `self`. When the function is variadic, excess arguments
///   are collected into an immutable [PyTuple].
/// - **Built-in / Native Function:**
///   ```
///   (Object[] captured, PyFunction self, Object[] args, PyDict kwargs) -> Object
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
  /// (Object... captures Object... defaults) -> Object
  /// ```
  /// The captured arguments and the default values are evaluated each time the `def` is
  /// executed, in that order, and stored in a fresh function object.
  static final class DefCallSite {
    private DefCallSite() {
      throw new AssertionError();
    }

    private static final MethodHandle PY_FUNCTION;
    static {
      var lookup = MethodHandles.lookup();
      try {
        PY_FUNCTION = lookup.findConstructor(PyFunction.class,
            methodType(void.class, PyFunction.Code.class, Object[].class, Object[].class));
      } catch (NoSuchMethodException | IllegalAccessException e) {
        throw new AssertionError(e);
      }
    }

    private static final Object[] EMPTY = new Object[0];

    static CallSite def(ModuleContext context, Dictionary dictionary, MethodType type, int astIndex) {
      var def = dictionary.lookup(astIndex);

      var functionName = def.name();
      var parameters = def.parameters().stream().map(Stmt.FunctionDef.Param::name).toList();
      var varargs = def.rest().isPresent();
      var defaultCount = countDefaults(def);
      var captureCount = type.parameterCount() - defaultCount;
      var code = new PyFunction.Code(functionName, parameters, varargs,
          defaultCount, captureCount, context, def);

      // CPython, creates a fresh function object each time it is executed
      var factory = PY_FUNCTION.bindTo(code);
      var mh = captureCount == 0
          ? insertArguments(factory, 0, (Object) EMPTY)
          : collectArguments(factory, 0, arrayCollector(captureCount));
      mh = defaultCount == 0
          ? insertArguments(mh, captureCount, (Object) EMPTY)
          : collectArguments(mh, captureCount, arrayCollector(defaultCount));
      var target = mh.asType(type);
      return new ConstantCallSite(target);
    }

    private static MethodHandle arrayCollector(int count) {
      return identity(Object[].class).asCollector(Object[].class, count);
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
    //     thunkEntry.invokeExact(function.capturedArgs, function, args_ordered, keyword_rest)

    static final MethodHandle MEGAMORPHIC, MEGAMORPHIC_WITH_KEYWORDS;
    private static final MethodHandle ARRAY_ELEMENT, REST_TUPLE, BUILTIN_REST;
    static {
      var lookup = MethodHandles.lookup();
      ARRAY_ELEMENT = arrayElementGetter(Object[].class);
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
      var code = function.code;
      checkArity(code, args.length);
      var thunkEntry = getThunkEntry(code);
      return thunkEntry.invokeExact(function.capturedArgs, function,
          withDefaults(function, args), newKeywordRest(code));
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
      var code = function.code;
      var plan = reorderKeywordArguments(code, positionalCount, keywordNames);
      var ordered = new Object[Math.max(positionalCount, code.arity())];
      var keywordRest = newKeywordRest(code);
      for (var i = 0; i < plan.length; i++) {
        var target = plan[i];
        if (isKwArgTarget(target)) {
          keywordRest.put(keywordNames[i - positionalCount], args[i]);
        } else {
          ordered[target] = args[i];
        }
      }
      fillMissing(function, ordered);
      var thunkEntry = getThunkEntry(code);
      return thunkEntry.invokeExact(function.capturedArgs, function, ordered, keywordRest);
    }

    private static PyDict<Object, Object> newKeywordRest(PyFunction.Code code) {
      return code.hasKeywordRest() ? new PyDict<>() : null;
    }

    /// The thunk spreads exactly `arity` declared parameters: complete a short array with the defaults.
    private static Object[] withDefaults(PyFunction function, Object[] args) {
      var code = function.code;
      var arity = code.arity();
      if (args.length >= arity) {
        return args;
      }
      var filled = Arrays.copyOf(args, arity);   // checkArity guarantees args.length >= firstDefault()
      var first = code.firstDefault();
      for (var i = args.length; i < arity; i++) {
        filled[i] = function.defaults[i - first];
      }
      return filled;
    }

    /// reorderKeywordArguments guarantees that only parameters with a default can be unassigned (null).
    private static void fillMissing(PyFunction function, Object[] ordered) {
      var code = function.code;
      var first = code.firstDefault();
      for (var i = first; i < code.arity(); i++) {
        if (ordered[i] == null) {
          ordered[i] = function.defaults[i - first];
        }
      }
    }

    private static MethodHandle getThunkEntry(PyFunction.Code code) {
      var thunkEntry = code.thunkEntry;  // racy read
      if (thunkEntry != null) {
        return thunkEntry;
      }
      thunkEntry = createThunk(FunctionCallSite.getCompiledEntry(code), code);  // idempotent
      code.thunkEntry = thunkEntry;  // racy write
      return thunkEntry;
    }

    /// Creates a universal thunk handle adapting the compiled entry to
    /// `(Object[] captured, PyFunction self, Object[] args, PyDict kwargs) -> Object`.
    private static MethodHandle createThunk(MethodHandle compiledEntry, PyFunction.Code code) {
      // 1. (Object[] captured, PyFunction self, <declared parameters> [, rest] [, kwargs])
      var captureCount = code.captureCount();
      var handle = captureCount == 0
          ? dropArguments(compiledEntry, 0, Object[].class)
          : compiledEntry.asSpreader(0, Object[].class, captureCount);

      // 2. No rest parameter: spread the declared parameters (a trailing kwargs parameter is untouched).
      var arity = code.arity();
      var keywordRest = code.hasKeywordRest();
      if (!code.isVariadic()) {
        var spread = arity == 0
            ? dropArguments(handle, 2, Object[].class)
            : handle.asSpreader(2, Object[].class, arity);
        return keywordRest ? spread : dropArguments(spread, 3, PyDict.class);
      }

      // 3. Rest parameter (a PyTuple for Python code, an Object[] for a builtin):
      //    read the declared parameters one by one and pack the remaining arguments.
      var packer = code.isBuiltin() ? BUILTIN_REST : REST_TUPLE;
      var filters = new MethodHandle[arity + 1];
      for (var i = 0; i < arity; i++) {
        filters[i] = insertArguments(ARRAY_ELEMENT, 1, i);
      }
      filters[arity] = insertArguments(packer, 1, arity);
      var filtered = filterArguments(handle, 2, filters);

      // 4. Every filtered parameter reads the same args array
      var reorder = new int[filtered.type().parameterCount()];
      reorder[0] = 0;  // captured
      reorder[1] = 1;  // self
      Arrays.fill(reorder, 2, 2 + arity + 1, 2);  // args
      if (keywordRest) {
        reorder[reorder.length - 1] = 3;  // kwargs
        return permuteArguments(filtered,
            methodType(Object.class, Object[].class, PyFunction.class, Object[].class, PyDict.class), reorder);
      }
      var mh = permuteArguments(filtered,
          methodType(Object.class, Object[].class, PyFunction.class, Object[].class), reorder);
      return dropArguments(mh, 3, PyDict.class);
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
        case 0 -> insertArguments(entry, position, TUPLE_EMPTY);
        case 1 -> collectArguments(entry, position, TUPLE1);
        case 2 -> collectArguments(entry, position, TUPLE2);
        case 3 -> collectArguments(entry, position, TUPLE3);
        case 4 -> collectArguments(entry, position, TUPLE4);
        default -> collectArguments(entry, position,
            TUPLE_N.asCollector(Object[].class, restCount));
      };
    }
  }

  static final class FunctionCallSite extends MutableCallSite {
    private static final MethodHandles.Lookup LOOKUP;
    private static final MethodHandle FALLBACK, SAME_CALLEE, SAME_CODE, CAPTURED_ARGS, DEFAULT_AT, BUILD_KEYWORD_REST;
    private static final VarHandle INFO;
    private static final String[] EMPTY_NAMES = new String[0];
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
        DEFAULT_AT = lookup.findStatic(FunctionCallSite.class, "defaultAt",
            methodType(Object.class, PyFunction.class, int.class));
        BUILD_KEYWORD_REST = lookup.findStatic(FunctionCallSite.class, "buildKeywordRest",
            methodType(PyDict.class, String[].class, Object[].class));
        INFO = lookup.findVarHandle(FunctionCallSite.class, "guardInfo", GuardInfo.class);
      } catch (NoSuchMethodException | IllegalAccessException | NoSuchFieldException e) {
        throw new AssertionError(e);
      }
    }

    static CallSite call(MethodType type, int positionalCount, String[] keywordNames) {
      return new FunctionCallSite(type, positionalCount, keywordNames, null, 0);
    }

    // A linked list of all guarded call sites
    // code of call site guarded by SAME_CALLEE on a PyFunction, null otherwise
    private record GuardInfo(PyFunction.Code code, FunctionCallSite next) {}
    private static final GuardInfo NO_GUARD = new GuardInfo(null, null);

    private static final int MAX_DEPTH = 4;

    private final int positionalCount;
    private final String[] keywordNames;
    private final FunctionCallSite root;
    private final int depth;

    private volatile GuardInfo guardInfo;

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

      // 2. Check if there is not an existing call site to update (promote)
      var code = function.code;
      var notTooManyCaptureArgs = code.captureCount() < 200; // Java MH implementation has a limit at 250
      var promoted = callee instanceof PyFunction && notTooManyCaptureArgs ?
          findPromoted(root, code) :
          null;

      // 3. Megamorphic: the arguments are validated and the defaults filled at each call
      if (promoted == null && depth == MAX_DEPTH) {
        var entryPoint = keywordNames.length == 0
            ? Megamorphic.MEGAMORPHIC
            : insertArguments(Megamorphic.MEGAMORPHIC_WITH_KEYWORDS, 2, positionalCount, keywordNames);
        var target = entryPoint.asCollector(Object[].class, argCount);
        root.setTarget(target);
        root.guardInfo = NO_GUARD;
        return target;
      }

      // 4. Validate the arguments before compiling the code
      int[] reorderPlan;
      if (keywordNames.length == 0) {
        checkArity(code, argCount);
        reorderPlan = null;
      } else {
        reorderPlan = reorderKeywordArguments(code, positionalCount, keywordNames);
      }

      // 5. Entry of the callee: (Object callee | PyFunction self, <declared parameters> [, rest])
      var byIdentity = promoted == null && notTooManyCaptureArgs;
      MethodHandle target, test;
      if (byIdentity) {
        test = SAME_CALLEE.bindTo(callee);
        target = getFunctionMH(function);
      } else {
        test = SAME_CODE.bindTo(code);
        target = getEntry(code);
      }

      // 6. Adapt to the call site.
      var defaultValues = byIdentity ? function.defaults : null;
      var adapted = reorderPlan == null
          ? adaptArguments(code, target, 1, defaultValues, argCount).asType(type())
          : adaptKeywords(code, target, type(), reorderPlan, defaultValues);

      // 7. Change the target of the existing call site
      if (promoted != null) {
        var guardInfo = promoted.guardInfo;  // volatile read
        if (guardInfo == NO_GUARD) {  // the root call site has been updated in between
          return root.getTarget();
        }
        var fallback = guardInfo.next.dynamicInvoker();
        promoted.setTarget(guardWithTest(test, adapted, fallback));
        return adapted;
      }

      // 8. Install the inlining cache
      var nextSite = new FunctionCallSite(type(), positionalCount, keywordNames, root, depth + 1);
      var info = new GuardInfo(
          callee instanceof PyFunction && notTooManyCaptureArgs ? code : null,  // can be promoted in the future?
          nextSite);
      if (INFO.compareAndSet(this, null, info)) {  // volatile write
        var fallback = nextSite.dynamicInvoker();
        setTarget(guardWithTest(test, adapted, fallback));
      }
      return adapted;
    }

    private static FunctionCallSite findPromoted(FunctionCallSite root, PyFunction.Code code) {
      for (var site = root;;) {
        var info = site.guardInfo;  // volatile read
        if (info == null || info == NO_GUARD) {
          return null;
        }
        if (info.code == code) {
          return site;
        }
        site = info.next;
      }
    }

    static MethodHandle getFunctionMH(PyFunction function) {
      var compiledEntry = getCompiledEntry(function.code);
      var capturedArgs = function.capturedArgs;

      var mh = capturedArgs.length != 0 ?
          insertArguments(compiledEntry, 0, capturedArgs) :
          compiledEntry;

      var bound = mh.bindTo(function);
      return dropArguments(bound, 0, Object.class);  // drop the callee
    }

    static MethodHandle getEntry(PyFunction.Code code) {
      var entry = code.entry;  // racy read
      if (entry != null) {
        return entry;
      }
      var compiledEntry = getCompiledEntry(code);

      var captureCount = code.captureCount();
      entry = captureCount == 0
          ? compiledEntry
          : foldArguments(
              compiledEntry.asSpreader(0, Object[].class, captureCount),
          CAPTURED_ARGS);

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

    private static Object defaultAt(PyFunction function, int index) {
      return function.defaults[index];
    }

    private static PyDict<Object, Object> buildKeywordRest(String[] names, Object[] values) {
      var dict = new PyDict<Object, Object>();
      for (var i = 0; i < names.length; i++) {
        dict.put(names[i], values[i]);
      }
      return dict;
    }

    private static MethodHandle compileBody(ModuleContext context, Stmt.FunctionDef def) {
      var scope = context.scopeFor(def);
      ScopeAnalyzer.checkDefiniteAssignment(scope);

      var dictionary = new Dictionary();
      var bytes = BytecodeGenerator.generateFunction(context, dictionary, def, scope);

      var className = BytecodeGenerator.functionClassName(def).replace('/', '.');
      var clazz = RT.defineClass(context, dictionary, className, bytes);

      var captureCount = BytecodeGenerator.captureNames(def, scope.analysis()).size();
      var type = BytecodeGenerator.bodyType(captureCount, def.parameters().size(),
          def.rest().isPresent(), def.keywordRest().isPresent());
      try {
        return LOOKUP.findStatic(clazz, def.name(), type);
      } catch (NoSuchMethodException | IllegalAccessException e) {
        throw new AssertionError(e);
      }
    }

    /// Adapts the entry to `(X, Object x (arity + restCount)) -> Object`: the rest parameter, at `position`,
    /// is replaced by `restCount` parameters (packed into a PyTuple for a Python function, into an Object[]
    /// for a builtin one).
    static MethodHandle adapt(PyFunction.Code code, MethodHandle entry, int position, int restCount) {
      if (!code.isVariadic()) {
        return entry;  // exact arity: no adaptation at all
      }
      if (code.isBuiltin()) {
        return entry.asCollector(position, Object[].class, restCount);
      }
      return RestAdapter.adapt(entry, position, restCount);
    }

    /// Adapts `entry`, whose declared parameters start at index `firstParam`, to a call with `argCount`
    /// positional arguments: packs the extra arguments into the rest parameter or, if the call is short,
    /// supplies the default values of the missing parameters (and an empty rest).
    /// If `defaults` is null, the entry is (PyFunction self, ...) and the defaults are read from self.
    static MethodHandle adaptArguments(PyFunction.Code code, MethodHandle entry, int firstParam,
                                       Object[] defaults, int argCount) {
      if (code.hasKeywordRest()) {
        entry = collectKeywordRest(entry, EMPTY_NAMES);   // positional call: an empty dict per call
      }
      var arity = code.arity();
      if (argCount >= arity) {
        return adapt(code, entry, firstParam + arity, argCount - arity);
      }
      // checkArity guarantees argCount >= firstDefault(); the missing parameters are contiguous
      var first = code.firstDefault();
      var adapted = code.isVariadic() ? adapt(code, entry, firstParam + arity, 0) : entry;

      if (defaults != null) {   // constants
        var values = Arrays.copyOfRange(defaults, argCount - first, defaults.length);
        return insertArguments(adapted, firstParam + argCount, values);   // values is the varargs array
      }

      // dynamic: insert all the default value calling DEFAULT_AT on `self`
      assert firstParam == 1;
      for (var i = arity - 1; i >= argCount; i--) {   // each step replaces one parameter by one: positions stay valid
        var filter = insertArguments(DEFAULT_AT, 1, i - first);
        adapted = collectArguments(adapted, firstParam + i, filter);
      }
      // adapted: (PyFunction self, Object x argCount, PyFunction x (arity - argCount)) -> Object
      // all the trailing PyFunction parameters are the same `self`
      var adaptedType = adapted.type();
      var keep = firstParam + argCount;
      var reorder = new int[adaptedType.parameterCount()];
      for (var j = 0; j < reorder.length; j++) {
        reorder[j] = j < keep ? j : 0;
      }
      return permuteArguments(adapted, adaptedType.dropParameterTypes(keep, adaptedType.parameterCount()), reorder);
    }

    /// Adapts the entry to the call site, permuting the arguments according to the plan.
    /// The parameters the call does not supply get their default value (see adaptArguments for `defaults`).
    private MethodHandle adaptKeywords(PyFunction.Code code, MethodHandle entry, MethodType type,
                                        int[] plan, Object[] defaults) {
      var arity = code.arity();
      var restCount = Math.max(0, positionalCount - arity);

      var supplied = new boolean[arity];
      var extraNames = new ArrayList<String>();   // keywords collected by **kwargs, in call-site order
      for (var i = 0; i < plan.length; i++) {
        var target = plan[i];
        if (isKwArgTarget(target)) {
          extraNames.add(keywordNames[i - positionalCount]);
        } else if (target < arity) {
          supplied[target] = true;
        }
      }
      var extraCount = extraNames.size();
      if (code.hasKeywordRest()) {
        entry = collectKeywordRest(entry, extraNames.toArray(EMPTY_NAMES));
      }
      // (X, Object x (arity + restCount + extraCount)) -> Object
      var adapted = adapt(code, entry, 1 + arity, restCount);

      var first = code.firstDefault();
      if (defaults != null) {   // constants: insert defaults for all the unsupplied parameters
        var slots = new int[arity];   // position in `adapted` of each supplied parameter once the others are bound
        var suppliedCount = 0;
        for (var i = 0; i < arity; i++) {
          if (supplied[i]) {
            slots[i] = 1 + suppliedCount++;
          }
        }
        for (var i = arity - 1; i >= first; i--) {
          if (!supplied[i]) {
            adapted = insertArguments(adapted, 1 + i, defaults[i - first]);
          }
        }
        var reorder = new int[1 + suppliedCount + restCount + extraCount];
        var extraIndex = 0;
        for (var i = 0; i < plan.length; i++) {
          var target = plan[i];
          int slot;
          if (isKwArgTarget(target)) {
            slot = 1 + suppliedCount + restCount + extraIndex++;
          } else if (target < arity) {
            slot = slots[target];
          } else {
            slot = 1 + suppliedCount + (target - arity);
          }
          reorder[slot] = 1 + i;
        }
        return permuteArguments(adapted.asType(type), type, reorder);
      }

      // dynamic: for each unsupplied parameter, call DEFAULT_AT on `self`
      for (var i = arity - 1; i >= first; i--) {
        if (!supplied[i]) {
          var filter = insertArguments(DEFAULT_AT, 1, i - first);
          adapted = collectArguments(adapted, 1 + i, filter);
        }
      }
      var reorder = new int[1 + arity + restCount + extraCount];   // zero (`self`) unless a call-site argument goes there
      var extraIndex = 0;
      for (var i = 0; i < plan.length; i++) {
        var target = plan[i];
        var slot = isKwArgTarget(target) ? 1 + arity + restCount + extraIndex++ : 1 + target;
        reorder[slot] = 1 + i;
      }
      var generic = adapted.asType(MethodType.genericMethodType(reorder.length));
      return permuteArguments(generic, type, reorder);
    }

    /// Replaces the last parameter of `entry` (the `**kwargs` dict) by one parameter per extra keyword value.
    /// The dict is created at each call, with the keywords in call-site order.
    private static MethodHandle collectKeywordRest(MethodHandle entry, String[] names) {
      var position = entry.type().parameterCount() - 1;
      var collector = insertArguments(BUILD_KEYWORD_REST, 0, (Object) names)
          .asCollector(Object[].class, names.length);
      return collectArguments(entry, position, collector);
    }
  }


  // -- helpers

  private static final int KWARG_TARGET = -1;

  private static boolean isKwArgTarget(int target) {
    // equivalent to == KWARG_INDEX but merged with subsequent bound check
    return target < 0;
  }

  static PyFunction asFunction(Object callee) {
    if (callee instanceof PyFunction function) {
      return function;
    }
    if (callee instanceof PyType type) {
      return type.function();
    }
    throw new PyTypeError("'" + IntrinsicSupport.typeName(callee) + "' object is not callable");
  }

  static void checkArity(PyFunction.Code code, int argCount) {
    var required = code.firstDefault();
    if (argCount < required) {
      throw missingArguments(code, code.parameters().subList(argCount, required));
    }
    if (!code.isVariadic() && argCount > code.arity()) {
      throw tooManyPositional(code, argCount);
    }
  }

  /// Computes the argument reordering plan mapping call-site arguments to canonical slots.
  /// @param code the target code
  /// @param positionalCount count of positional arguments
  /// @param keywordNames names of keyword arguments
  /// @return array where index `i` maps call-site argument `i` to its canonical slot
  /// @throws PyTypeError if keywords are unknown, duplicate, or missing required parameters
  static int[] reorderKeywordArguments(PyFunction.Code code, int positionalCount, String[] keywordNames) {
    if (code.isBuiltin()) {
      throw builtinKeywords(code);
    }
    var parameters = code.parameters();
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
      var index = code.parameterIndex(name);
      if (index < 0) {
        if (!code.hasKeywordRest()) {
          throw unexpectedKeyword(code, name);
        }
        plan[positionalCount + i] = KWARG_TARGET;   // goes to **kwargs
        continue;
      }
      if (assigned[index]) {
        throw multipleValues(code, name);
      }
      assigned[index] = true;
      plan[positionalCount + i] = index;
    }

    // as CPython: checked after the keywords, before the missing arguments
    if (!code.isVariadic() && positionalCount > arity) {
      throw tooManyPositional(code, positionalCount);
    }

    var missing = (ArrayList<String>) null;
    for (var i = 0; i < code.firstDefault(); i++) {
      if (!assigned[i]) {
        if (missing == null) {
          missing = new ArrayList<>();
        }
        missing.add(parameters.get(i));
      }
    }
    if (missing != null) {
      throw missingArguments(code, missing);
    }
    return plan;
  }

  // -- errors

  private static int countDefaults(Stmt.FunctionDef def) {
    var count = 0;
    for (var parameter : def.parameters()) {
      if (parameter.defaultExpr().isPresent()) {
        count++;
      }
    }
    return count;
  }

  /// Fixed-arity builtins are positional-only (as in CPython). Variadic builtins accept keywords in CPython
  /// (print(sep=...), int(x, base=...)) but we do not have keyword ABI for builtins yet.
  private static RuntimeException builtinKeywords(PyFunction.Code code) {
    if (code.isVariadic()) {
      return new UnimplementedFeatureError("keyword arguments of the built-in function " + code.name() + "()");
    }
    return new PyTypeError(code.name() + "() takes no keyword arguments");
  }

  private static PyTypeError unexpectedKeyword(PyFunction.Code code, String name) {
    return new PyTypeError(code.name() + "() got an unexpected keyword argument '" + name + "'");
  }

  private static PyTypeError multipleValues(PyFunction.Code code, String name) {
    return new PyTypeError(code.name() + "() got multiple values for argument '" + name + "'");
  }

  private static PyTypeError missingArguments(PyFunction.Code code, List<String> missing) {
    return new PyTypeError(code.name() + "() missing " + missing.size() + " required positional argument"
        + (missing.size() == 1 ? "" : "s") + ": " + joinNames(missing));
  }

  private static PyTypeError tooManyPositional(PyFunction.Code code, int given) {
    var arity = code.arity();
    var required = code.firstDefault();
    var expected = required == arity ? Integer.toString(arity) : "from " + required + " to " + arity;
    var plural = required != arity || arity != 1;
    return new PyTypeError(code.name() + "() takes " + expected + " positional argument"
        + (plural ? "s" : "") + " but " + given + (given == 1 ? " was" : " were") + " given");
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