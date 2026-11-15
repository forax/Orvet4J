package com.github.forax.orvet.rt;

import com.github.forax.orvet.ast.Stmt;
import com.github.forax.orvet.engine.BytecodeGenerator;
import com.github.forax.orvet.engine.ScopeAnalyzer;
import com.github.forax.orvet.rt.error.PyTypeError;
import com.github.forax.orvet.rt.error.UnimplementedFeatureError;
import com.github.forax.orvet.rt.intrinsic.IntrinsicSupport;
import com.github.forax.orvet.rt.intrinsic.TupleIntrinsics;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.util.CheckClassAdapter;

import java.io.PrintWriter;
import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static java.lang.invoke.MethodHandles.arrayElementGetter;
import static java.lang.invoke.MethodHandles.collectArguments;
import static java.lang.invoke.MethodHandles.constant;
import static java.lang.invoke.MethodHandles.dropArguments;
import static java.lang.invoke.MethodHandles.empty;
import static java.lang.invoke.MethodHandles.exactInvoker;
import static java.lang.invoke.MethodHandles.filterArguments;
import static java.lang.invoke.MethodHandles.foldArguments;
import static java.lang.invoke.MethodHandles.guardWithTest;
import static java.lang.invoke.MethodHandles.insertArguments;
import static java.lang.invoke.MethodHandles.permuteArguments;

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
///    cascade of [MethodHandles#guardWithTest] guards (up to depth 4).
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
            MethodType.methodType(void.class, PyFunction.Code.class, Object[].class));
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

      if (captureCount == 0) {
        var function = new PyFunction(code, EMPTY_CAPTURED_ARGS);
        return new ConstantCallSite(constant(Object.class, function));
      }

      var factory = PY_FUNCTION.bindTo(code);
      var target = factory
          .asCollector(Object[].class, captureCount)
          .asType(type);
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
      ARRAY_ELEMENT = arrayElementGetter(Object[].class);
      try {
        MEGAMORPHIC = lookup.findStatic(Megamorphic.class, "megamorphic",
            MethodType.methodType(Object.class, Object.class, Object[].class));
        MEGAMORPHIC_WITH_KEYWORDS = lookup.findStatic(Megamorphic.class, "megamorphicWithKeywords",
            MethodType.methodType(Object.class, Object.class, Object[].class, int.class, String[].class));
        REST_TUPLE = lookup.findStatic(Megamorphic.class, "restTuple",
            MethodType.methodType(PyTuple.class, Object[].class, int.class));
        BUILTIN_REST = lookup.findStatic(Megamorphic.class, "builtinRest",
            MethodType.methodType(Object[].class, Object[].class, int.class));
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
        filters[i] = insertArguments(ARRAY_ELEMENT, 1, i);
      }
      filters[arity] = insertArguments(packer, 1, arity);
      var filtered = filterArguments(handle, 2, filters);

      // 4. Every filtered parameter reads the same args array.
      var type = MethodType.methodType(Object.class, Object[].class, PyFunction.class, Object[].class);
      var reorder = new int[filtered.type().parameterCount()];
      reorder[0] = 0;  // captured
      reorder[1] = 1;  // self
      Arrays.fill(reorder, 2, reorder.length, 2);  // args
      return permuteArguments(filtered, type, reorder);
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
        TUPLE1 = lookup.findConstructor(PyTuple.PyTuple1.class, MethodType.methodType(void.class, Object.class))
            .asType(MethodType.methodType(PyTuple.class, Object.class));
        TUPLE2 = lookup.findConstructor(PyTuple.PyTuple2.class, MethodType.methodType(void.class, Object.class, Object.class))
            .asType(MethodType.methodType(PyTuple.class, Object.class, Object.class));
        TUPLE3 = lookup.findConstructor(PyTuple.PyTuple3.class, MethodType.methodType(void.class, Object.class, Object.class, Object.class))
            .asType(MethodType.methodType(PyTuple.class, Object.class, Object.class, Object.class));
        TUPLE4 = lookup.findConstructor(PyTuple.PyTuple4.class, MethodType.methodType(void.class, Object.class, Object.class, Object.class, Object.class))
            .asType(MethodType.methodType(PyTuple.class, Object.class, Object.class, Object.class, Object.class));
        TUPLE_N = lookup.findConstructor(PyTuple.PyTupleN.class, MethodType.methodType(void.class, Object[].class))
            .asType(MethodType.methodType(PyTuple.class, Object[].class));
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
    private static final MethodHandle FALLBACK, SAME_FUNCTION, SAME_CODE, CAPTURED_ARGS;
    static {
      var lookup = MethodHandles.lookup();
      LOOKUP = lookup;
      try {
        FALLBACK = lookup.findVirtual(FunctionCallSite.class, "fallback",
            MethodType.methodType(MethodHandle.class, Object.class));
        SAME_FUNCTION = lookup.findStatic(FunctionCallSite.class, "sameFunction",
            MethodType.methodType(boolean.class, PyFunction.class, Object.class));
        SAME_CODE = lookup.findStatic(FunctionCallSite.class, "sameCode",
            MethodType.methodType(boolean.class, PyFunction.Code.class, Object.class));
        CAPTURED_ARGS = lookup.findVirtual(PyFunction.class, "capturedArgs",
            MethodType.methodType(Object[].class));
      } catch (NoSuchMethodException | IllegalAccessException e) {
        throw new AssertionError(e);
      }
    }

    static CallSite call(MethodType type, int positionalCount, String[] keywordNames) {
      return new FunctionSupport.FunctionCallSite(type, positionalCount, keywordNames, null, 0);
    }

    private static final int MAX_DEPTH = 4;

    private final int positionalCount;
    private final String[] keywordNames;
    private final FunctionCallSite root;
    private final int depth;

    private FunctionCallSite(MethodType type, int positionalCount, String[] keywordNames, FunctionCallSite root, int depth) {
      this.positionalCount = positionalCount;
      this.keywordNames = keywordNames;
      this.depth = depth;
      super(type);
      this.root = root == null ? this : root;
      setTarget(foldArguments(exactInvoker(type()), FALLBACK.bindTo(this)));
    }

    private MethodHandle fallback(Object callee) {
      var function = asFunction(callee);
      var argCount = type().parameterCount() - 1;

      // 1. Megamorphic: the arguments are validated at each call
      if (depth == MAX_DEPTH) {
        var entryPoint = keywordNames.length == 0
            ? Megamorphic.MEGAMORPHIC
            : insertArguments(Megamorphic.MEGAMORPHIC_WITH_KEYWORDS, 2, positionalCount, keywordNames);
        var target = entryPoint.asCollector(Object[].class, argCount);
        root.setTarget(target);
        return target;
      }

      // 2. Validate the arguments before compiling the callee
      int[] reorderPlan;
      if (keywordNames.length == 0) {
        checkArity(function, argCount);
        reorderPlan = null;
      } else {
        reorderPlan = reorderKeywordArguments(function, positionalCount, keywordNames);
      }

      // 3. Entry of the callee: (Object callee | PyFunction self, <declared parameters> [, rest])
      var code = function.code;
      MethodHandle target, test;
      if (code.captureCount() == 0) {
        test = SAME_FUNCTION.bindTo(function);
        target = getNoCaptureMH(function);
      } else {
        test = SAME_CODE.bindTo(code);
        target = getEntry(code);
      }

      // 4. Adapt to the call site
      var arity = function.arity();
      var adapted = reorderPlan == null
          ? adapt(function, target, 1 + arity, argCount - arity).asType(type())
          : adaptKeywords(function, target, type(), reorderPlan);

      // 5. Install the inlining cache
      var fallback = new FunctionCallSite(type(), positionalCount, keywordNames, root, depth + 1).dynamicInvoker();
      setTarget(guardWithTest(test, adapted, fallback));

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
        return code.compiledEntry = Builtins.resolve(code);  // idempotent, volatile write
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

    private static boolean sameFunction(PyFunction expected, Object callee) {
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

      // DEBUG
      //var checkedBytes = Arrays.copyOf(bytes, bytes.length);
      //checkedBytes[4] = 0;
      //checkedBytes[5] = 0;
      //CheckClassAdapter.verify(new ClassReader(checkedBytes), true, new PrintWriter(System.err));

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
      return permuteArguments(adapted, type, reorder);
    }
  }


  /// Raises NameError if the global is unbound. A cell never goes back to unbound,
  /// so the first successful check replaces the target by a nop.
  static final class CheckGlobalCallSite extends MutableCallSite {
    private static final MethodHandle FALLBACK, NOP;
    static {
      var type = MethodType.methodType(void.class);
      NOP = empty(type);
      var lookup = MethodHandles.lookup();
      try {
        FALLBACK = lookup.findVirtual(CheckGlobalCallSite.class, "fallback", type);
      } catch (NoSuchMethodException | IllegalAccessException e) {
        throw new AssertionError(e);
      }
    }

    static CallSite checkGlobal(ModuleContext context, String name) {
      return new CheckGlobalCallSite(context.cell(name));
    }

    private final GlobalSupport.GlobalCell cell;

    private CheckGlobalCallSite(GlobalSupport.GlobalCell cell) {
      this.cell = cell;
      super(NOP.type());
      setTarget(FALLBACK.bindTo(this));
    }

    private void fallback() {
      cell.get();  // throws NameError if unbound
      setTarget(NOP);
    }
  }


  /// A call `name(args...)` where `name` is a global: `(Object arg0, ..., Object argN-1) -> Object`.
  ///
  /// While the cell holds a function, the target is a direct call to its compiled entry (captured values
  /// and self already bound), guarded by the SwitchPoint of the cell. After MAX_INVALIDATIONS invalidations
  /// the global is considered unstable and the site reads the cell on every call (megamorphic path).
  static final class GlobalCallCallSite extends MutableCallSite {
    private static final MethodHandle FALLBACK;
    static {
      var lookup = MethodHandles.lookup();
      try {
        FALLBACK = lookup.findVirtual(GlobalCallCallSite.class, "fallback",
            MethodType.methodType(MethodHandle.class));
      } catch (NoSuchMethodException | IllegalAccessException e) {
        throw new AssertionError(e);
      }
    }

    static CallSite globalCall(ModuleContext context, MethodType type, String globalName,
                               int positionalCount, String[] keywordNames) {
      var cell = context.cell(globalName);
      return new GlobalCallCallSite(cell, type, positionalCount, keywordNames);
    }

    private static final int MAX_INVALIDATIONS = 4;

    private final GlobalSupport.GlobalCell cell;
    private final int positionalCount;
    private final String[] keywordNames;
    private final MethodHandle fallback;

    private int invalidationCount;  // guards installed so far; racy updates are benign

    private GlobalCallCallSite(GlobalSupport.GlobalCell cell, MethodType type,
                               int positionalCount, String[] keywordNames) {
      this.cell = cell;
      this.positionalCount = positionalCount;
      this.keywordNames = keywordNames;
      super(type);
      this.fallback = foldArguments(exactInvoker(type), FALLBACK.bindTo(this));
      setTarget(fallback);
    }

    /// Called on the first call and every time a guard is invalidated.
    private MethodHandle fallback() {
      // 1. unstable global: read the cell on every call, for good
      var invalidationCount = this.invalidationCount;
      if (invalidationCount >= MAX_INVALIDATIONS) {
        var megamorphic = megamorphicTarget();
        setTarget(megamorphic);
        return megamorphic;
      }
      this.invalidationCount = invalidationCount + 1;

      // 2. atomic snapshot of the value and of the SwitchPoint that protects it
      var resolved = cell.resolveCell();
      var value = resolved.value();
      if (value == null) {
        throw new AssertionError();  // CheckGlobalCallSite is called before
      }

      // 3. errors (not callable, arity, keywords) are raised here, nothing is installed
      var function = asFunction(value);
      var direct = directTarget(function);

      // 4. install the guard; once the SwitchPoint is invalidated the guard calls link() again
      var guard = resolved.switchPoint().guardWithTest(direct, fallback);
      setTarget(guard);

      return direct;
    }

    private MethodHandle megamorphicTarget() {
      var argCount = type().parameterCount();
      var entryPoint = keywordNames.length == 0
          ? Megamorphic.MEGAMORPHIC
          : insertArguments(Megamorphic.MEGAMORPHIC_WITH_KEYWORDS,
          2, positionalCount, keywordNames);

      // (Object callee, Object... args) with the callee read from the cell
      var target = entryPoint.asCollector(Object[].class, argCount);
      var combiner = GlobalSupport.CELL_GET.bindTo(cell);
      return foldArguments(target, combiner);
    }

    /// The compiled entry bound to the function, adapted to the type of the call site.
    private MethodHandle directTarget(PyFunction function) {
      var type = type();
      var argCount = type.parameterCount();
      var arity = function.arity();

      // 1. validate before compiling the callee
      int[] plan = null;
      if (keywordNames.length == 0) {
        checkArity(function, argCount);
      } else {
        plan = reorderKeywordArguments(function, positionalCount, keywordNames);
      }

      // 2. (captures..., self, params... [, rest]) -> (params... [, rest])
      var compiledEntry = FunctionCallSite.getCompiledEntry(function.code);
      var captured = function.capturedArgs;
      var boundValues = new Object[captured.length + 1];
      System.arraycopy(captured, 0, boundValues, 0, captured.length);
      boundValues[captured.length] = function;
      var entry = insertArguments(compiledEntry, 0, boundValues);

      // 3. rest parameter, then keyword reordering
      if (plan == null) {
        return FunctionCallSite.adapt(function, entry, arity, argCount - arity).asType(type);
      }
      var restCount = Math.max(0, positionalCount - arity);
      var adapted = FunctionCallSite.adapt(function, entry, arity, restCount).asType(type);
      var reorder = new int[arity + restCount];
      for (var i = 0; i < plan.length; i++) {
        reorder[plan[i]] = i;
      }
      return permuteArguments(adapted, type, reorder);
    }
  }




  // -- helpers

  static PyFunction asFunction(Object callee) {
    if (callee instanceof PyFunction function) {
      return function;
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