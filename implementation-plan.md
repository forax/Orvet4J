# Orvet4J — Implementation Plan (proposed revision)

## 0. Status vocabulary and semantic contract

Use these status tags consistently:

- **[IMPLEMENTED]** — present in the source code. This does not imply that every
  edge case is CPython-identical or that every behavior has a differential test.
- **[PARTIAL]** — a working subset exists; the limitations are stated alongside it.
- **[NOT IMPLEMENTED]** — rejected or unavailable today.
- **[KNOWN DIVERGENCE]** — accepted or exposed behavior is known to differ from CPython.
- **[ACCEPTED OPTIMIZATION]** — a deliberate difference permitted for performance.

### 0.1 Semantic target

CPython 3.14 is the reference point for the supported language subset, but the
implementation does **not** currently preserve Python semantics for every accepted
construct. The known divergences in this document are part of the current state and
must not be hidden behind a blanket conformance claim.

There is an explicit exception for **constant-origin values**:

- A value represented as, or produced from, a JVM constant may be shared, cached,
  canonicalized, or otherwise treated differently from a freshly allocated Python
  value. Identity-sensitive behavior and other specifically documented observations
  of such values need not match CPython.
- Examples include `ConstantDynamic`-resolved numeric literals, JVM string constants,
  singleton booleans and `None`, the shared empty tuple, and a zero-capture function
  definition site that returns one constant `PyFunction` on every execution.
- In particular, a zero-capture nested `def` executed repeatedly can return the same
  function object. Function equality is identity-based in this runtime, so that choice
  can be observed. This is intentional and is not a task to change merely for CPython
  identity equivalence.
- NaN hashing is deliberately constant (`0`) rather than identity-derived as in
  CPython. `NaN == NaN` remains false; container membership/comparison uses an
  identity-or-equality shortcut where Python does.
- Tuple operations also reuse immutable tuple instances in some cases (for example,
  concatenation with an empty tuple and repetition by one).

Do not generalize this exception into permission for arbitrary semantic shortcuts.
Every divergence must be documented by value/operation and justified. A runtime unit
test may assert an intentional constant policy (as the current no-capture-def test does),
but a CPython differential test must not treat that identity behavior as a conformance
requirement. In particular, `range` and `bytearray` currently use approximate
representations; those are known builtin semantic gaps, not examples of the
constant-value optimization policy.

### 0.2 Architectural principles

1. Use ordinary JVM objects as Python values; there is no universal `PyValue` hierarchy.
2. Keep generic runtime intrinsics readable and authoritative for implemented operations.
   Specialized versions are generated from those intrinsic methods and must preserve
   their behavior.
3. Use `invokedynamic` for dynamic operators, comparisons, item operations, iteration,
   global access, function definition and function calls. Container construction and
   some tuple construction use direct JVM calls.
4. Keep mutable lists and dictionaries as mutable runtime objects.
5. Python equality, hashing and ordering are implemented explicitly by runtime
   intrinsics. Do not use Java `equals`/`hashCode` as a substitute for Python semantics.
6. Unsupported syntax should fail with a located `UnsupportedSyntaxException`;
   some constructs are parsed into the AST and rejected later by bytecode generation.
7. Runtime gaps currently use a mixture of `UnimplementedFeatureError` and Python
   exceptions; this is not yet a uniform invariant (see §10 and §15).

## 1. Architecture [IMPLEMENTED, with lazy function compilation]

```text
Python source
  └─ ANTLR lexer/parser (Python 3.14 grammar)
       └─ ASTBuilder
            └─ AST: Script / Block / Stmt / Expr / Target
                 └─ Engine.compile / BytecodeGenerator
                      └─ generated Script.__main__()
                           └─ RT.defineClass (new FunctionClassLoader)
                                └─ invokedynamic bootstraps in RT
                                     ├─ global access: GlobalSupport
                                     ├─ def/call: FunctionSupport
                                     ├─ type guards: TypeGuardSupport
                                     └─ intrinsic dispatch: SpecializationSupport
                                          └─ Specializer (ASM; in engine package)
```

### 1.1 Packages

| Package | Contents |
|---|---|
| `grammar.antlr` | ANTLR-generated lexer/parser plus `PythonLexerBase` |
| `ast` | AST records, `ASTBuilder`, `ASTPrinter`, literal decoding and syntax exceptions |
| `engine` | `Engine`, `BytecodeGenerator`, `ScopeAnalyzer`, `Specializer`, class-file version and semantic diagnostics |
| `rt` | `RT`, `ModuleContext`, `Dictionary`, function/global/type-guard/specialization support, runtime values, builtins and iterators |
| `rt.intrinsic` | `@Intrinsic` methods and non-specialized helpers |
| `rt.error` | Python exception classes and `UnimplementedFeatureError` |
| `perf` | JMH benchmarks and benchmark Python programs |

There is no `compiler` package and no `rt.specializer` package in the audited source.
`Specializer` is in `engine`; `SpecializationSupport` is in `rt`.

### 1.2 Generated code and class loading

- `Engine.compile` creates a generated `Script` class with a static `__main__` method.
- `RT.defineClass` creates a fresh private `FunctionClassLoader` for each generated class.
  The loader carries its `ModuleContext` and the `Dictionary` mapping `def` sites to AST nodes.
- Function bodies are compiled on first call, not all at script compilation time (§8.5).
- Runtime bootstraps recover module state from the generated class's loader.
- Dynamic operators and calls are linked through `invokedynamic`. List/dict display construction
  calls storage methods directly; tuple displays use direct tuple constructors/factories.
- A type-guard call site is emitted after a function call to specialize the observed result class.

## 2. Runtime values and representation [IMPLEMENTED / PARTIAL]

| Python concept | Current representation | Current status |
|---|---|---|
| `None` | `PyNone.INSTANCE` | [IMPLEMENTED] singleton |
| `bool` | `Boolean` | [IMPLEMENTED] |
| `int` | `Long` when it fits, otherwise `BigInteger` | [IMPLEMENTED] normalized arithmetic values |
| `float` | `Double` | [IMPLEMENTED] |
| `str` | `String` | [PARTIAL] code-point-aware iteration and ordering; no indexing/slicing |
| `tuple` | sealed `PyTuple` (`PyTuple1..4`, `PyTupleN`) | [IMPLEMENTED] core operations |
| `list` | final `PyList<E>` | [IMPLEMENTED] core operations; no Python method table |
| `dict` | final `PyDict<K,V>` | [IMPLEMENTED] core operations; no Python method table |
| Python function | `PyFunction` / shared `PyFunction.Code` | [IMPLEMENTED] with constant no-capture def sites |
| `range` | eagerly materialized `PyList` | [KNOWN DIVERGENCE] |
| `bytearray` | `PyList<Long>` of unsigned byte values | [KNOWN DIVERGENCE] |
| slice object | — | [NOT IMPLEMENTED] |
| range object | — | [NOT IMPLEMENTED] distinct runtime type |
| type object | — | [NOT IMPLEMENTED] no `PyType` |
| internal iterators | `PyIterator` nested classes | [IMPLEMENTED] internal only |

### 2.1 Representation invariants

- `BigInteger` arithmetic results are normalized to `Long` whenever the value fits.
- Tuple factories in `TupleIntrinsics` make canonical shapes: 1–4 elements use fixed records;
  empty and 5+ element tuples use `PyTupleN`. The bytecode generator directly constructs
  fixed tuple records for displays of size 1–4.
- The empty tuple is shared.
- Runtime values and container contents reject Java `null`. `null` is reserved for internal
  states such as an unbound global, absent dictionary lookup result, or exhausted iterator.
- `PyDict.get` returns Java `null` for an absent key; stored values cannot be Java `null`.
- Lists and dictionaries are not thread-safe. Synchronization is the caller's responsibility.

### 2.2 Java interop views

- `PyList` does not extend `AbstractList` and does not implement `java.util.List`; it exposes
  `asJavaList()` as a view.
- `PyDict` does not extend `AbstractMap` and does not implement `java.util.Map`; it exposes
  `asJavaMap()` as a view.
- `PyTuple` exposes `asJavaList()`.
- Java equality on the storage classes is not the Python equality protocol. Python-visible
  equality is routed through `ComparisonIntrinsics`.

## 3. Constants and interop

### 3.1 Constant loading [IMPLEMENTED]

- `Long` and `Double` literals are emitted through `ConstantDynamic` and `RT.bsmConst`.
- `BigInteger` literals use `ConstantDynamic` and `RT.bsmBigInt`, which parses a decimal string.
- Booleans load `Boolean.TRUE` / `Boolean.FALSE`; `None` loads `PyNone.INSTANCE`.
- Strings use JVM string constants. Long strings are split into chunks and concatenated.
- List and dictionary displays always allocate a fresh object and evaluate elements in order.
- Tuple displays are implemented, not constant-folded by the bytecode generator: empty tuple uses
  `TupleIntrinsics.empty`; sizes 1–4 construct records directly; larger displays use an array and
  `TupleIntrinsics.fromArray`.
- A no-capture `def` site links to a `ConstantCallSite` containing one `PyFunction`; a capturing
  `def` site is a factory that creates a new `PyFunction` with captured values per execution.

### 3.2 Java-to-Python normalization [NOT IMPLEMENTED]

`ModuleContext.setGlobal`, `PyList`, and `PyDict` reject Java `null` but do not normalize other
Java values. There is no `Interop.toPython` boundary today. In particular, Java `Integer`,
`Short`, `Byte`, `Float`, and `Character` are not converted to canonical Python representations.
Intrinsics expect canonical runtime values.

## 4. Collections [IMPLEMENTED core behavior; methods and slices missing]

### 4.1 Lists

`PyList` is array-backed, grows by approximately 1.5×, and caps its storage at
`Integer.MAX_VALUE - 8`. Oversize list storage raises `UnimplementedFeatureError`.

Python-facing operations implemented through `ListIntrinsics` include:

- length, indexing with negative indexes, item assignment and deletion;
- concatenation and repetition (always a new list, including `list * 1`);
- in-place `+=` and `*=` (mutate and return the receiver);
- membership, equality and ordering through intrinsics;
- truthiness and string/repr rendering.

The list iterator reads the list's current size at every `next`, so mutations may affect an
ongoing iteration. `PyList` has Java storage methods such as `append`, `insert`, `remove`,
`clear`, and `repeatInPlace`, but those are not a Python method table. Python `list.append`,
`pop`, `insert`, etc. are not currently callable as methods from source.

### 4.2 Dictionaries

`PyDict` stores entries in insertion order using parallel key/value/hash arrays and an
open-addressing index. The index uses perturbation probing and tombstones; sparse entry storage
can be compacted.

Implemented Python-facing operations include length, lookup, assignment, deletion, membership,
equality, truthiness, repr and iteration over keys. Replacing the value for an equal key retains
the original key object and position. Deleting and reinserting appends the key at the end.

Dictionary iteration allows value replacement but detects key-set/size changes and raises
`PyRuntimeError`; after failure, the iterator remains failed. Dicts are unhashable; list/dict keys
are rejected by hashing.

The Python method table (`get`, `pop`, `setdefault`, `update`, `clear`, `copy`, etc.) is not
implemented. `items`, `keys`, and `values` are not exposed as Python methods.

### 4.3 Tuples

Tuple storage and core operations are implemented: size, indexing, concatenation, repetition,
equality, hashing, ordering, membership, iteration, and repr. Tuple displays are accepted and
compiled. Tuple unpacking and slicing are not implemented.

Tuple operations may reuse immutable tuple objects (empty tuple, concatenation with an empty
operand, and repetition by one). Oversized tuple concatenation/repetition currently raises
`OutOfMemoryError`, not the `UnimplementedFeatureError` used for oversized lists/strings.

### 4.4 Hashing

- Lists and dictionaries raise `PyTypeError` as unhashable types.
- Equal numeric values across bool/int/float representations have compatible hashes.
- Integer and float hashes follow the 64-bit CPython algorithm for supported values.
- String, tuple, `None`, and function hash values are implementation details; tests should not
  assert their exact values.
- NaN hashes to constant zero [ACCEPTED OPTIMIZATION / KNOWN DIVERGENCE].

## 5. Intrinsics and builtins

### 5.1 Intrinsic inventory [IMPLEMENTED core set]

All intrinsic entry points are public static methods annotated `@Intrinsic`, named `$name$`.

| Class | Current operations |
|---|---|
| `ArithmeticIntrinsics` | binary/unary arithmetic, bitwise operations, shifts and augmented-assignment variants |
| `ComparisonIntrinsics` | `$eq$`, `$ne$`, `$lt$`, `$le$`, `$gt$`, `$ge$`, `$sameOrEq$` |
| `HashingIntrinsics` | `$hash$` |
| `TupleIntrinsics` | `$size$`, `$get$`, `$concat$`, `$repeat$`, `$in$`; factories `empty`, `fromArray`, `fromArrayRange` |
| `ListIntrinsics` | `$size$`, `$get$`, `$set$`, `$delete$`, `$concat$`, `$repeat$`, `$iadd$`, `$imul$`, `$in$`; `newList` |
| `DictIntrinsics` | `$size$`, `$get$`, `$set$`, `$delete$`, `$in$`; `newDict`, `equals` |
| `ItemIntrinsics` | `$getItem$`, `$setItem$`, `$deleteItem$`, `$toIndex$` |
| `ContainmentIntrinsics` | `$in$` |
| `TruthinessIntrinsics` | `$truthy$`, `$not$` |
| `IteratorIntrinsics` | `$iterator$`, `$next$` |
| `StringIntrinsics` | `$str$`, `$repr$` |

`len` is implemented as a builtin, not as a `$len$` intrinsic. `type`, `isinstance`, slicing,
formatting and Python container method-table intrinsics are not implemented.

### 5.2 Augmented assignment [IMPLEMENTED]

Every arithmetic/bitwise operator has an `$i...$` intrinsic. Most immutable-value paths delegate
to the corresponding binary operation. List `+=` and `*=` mutate the existing list and return it.

- Name target: load, evaluate right operand, apply augmented intrinsic, store.
- Subscript target: evaluate container and index once, read old item, evaluate right operand,
  apply operation, then write the result back.

### 5.3 Builtins [PARTIAL]

`Builtins.BUILTINS` is a static shared `HashMap<String, PyFunction>` populated with these names:

`abs`, `bytearray`, `bin`, `bool`, `dict`, `divmod`, `hex`, `int`, `len`, `oct`, `ord`, `pow`,
`print`, `range`, `repr`, `round`.

The map is not made immutable with `Map.copyOf`; it is a `static final HashMap`.
A `ModuleContext` creates each global cell with the same-name builtin as its initial value.
A subsequent module assignment replaces that value. This is how builtin lookup/shadowing works;
there is no separate `ModuleContext.defineBuiltin` or per-read fallback lookup.

Builtin ABI: `(PyFunction self, Object... args)` at the Java source level, adapted to a fixed
method-handle signature whose trailing variadic parameter is `Object[]`. User-defined Python
functions instead receive a `PyTuple` for `*rest`.

Implemented behavior includes:

- `print(*args)`: converts each argument with `$str$`, joins with one space, writes an explicit
  `\n`, returns `None`. `print()` with no arguments is supported.
- `range(...)`: accepts one to three integer-like arguments, validates zero step and materializes
  the result as a `PyList`.
- `bytearray(...)`: accepts supported integer, string/encoding, or iterable inputs and represents
  bytes as a `PyList<Long>` of unsigned byte values.
- `dict(...)`: empty construction, copy from `PyDict`, or construction from an iterable of pairs.
- `abs`, `bin`, `hex`, `oct`, `bool`, `int`, `len`, `ord`, `pow` (including modular power),
  `repr`, `round`, and `divmod` have implementations with the limits described in source.

**Known builtin divergences:** `range` is a list, not a range object; `bytearray` is a list, not a
bytearray object. Their type, identity, method surface and some operation semantics therefore
differ from CPython. Builtins not in the table are not automatically recognized as unsupported:
lookup of a missing builtin generally raises `PyNameError`, not `UnimplementedFeatureError`.

Keyword arguments are supported for user-defined functions. Builtin keyword handling is not:
fixed-arity builtins reject keywords with `PyTypeError`; variadic builtins reject keyword calls
with `UnimplementedFeatureError`.

### 5.4 Special methods

There is no generic Python special-method dispatch. Implemented arithmetic/comparison/container
operations are dispatched directly by intrinsics for the supported runtime representations.

## 6. Language subset

### 6.1 Parsed and generated constructs

| Construct | ASTBuilder | Bytecode/runtime |
|---|---|---|
| Expression statements, `pass`, `return`, `break`, `continue` | accepted | [IMPLEMENTED] |
| Name assignment and chained assignment | accepted | [IMPLEMENTED] |
| Subscript assignment and deletion | accepted | [IMPLEMENTED] |
| Augmented assignment to names/subscripts | accepted | [IMPLEMENTED] |
| `if` / `elif` / `else` | accepted | [IMPLEMENTED] |
| `while` and `for`, including loop `else` | accepted | [IMPLEMENTED] over str/tuple/list/dict iterables |
| `def`, nested defs, closures, positional args and one `*rest` | accepted | [IMPLEMENTED] |
| Calls with positional arguments | accepted | [IMPLEMENTED] |
| Calls with keyword arguments | accepted | [IMPLEMENTED] for user functions; builtin keyword calls as above |
| Arithmetic, bitwise, comparisons, comparison chains | accepted | [IMPLEMENTED] for supported value types |
| `and`, `or`, `not`, conditional expressions | accepted | [IMPLEMENTED] |
| `in` / `not in` | accepted | [IMPLEMENTED] for str/tuple/list/dict |
| List and dictionary displays | accepted | [IMPLEMENTED] |
| Tuple displays | accepted | [IMPLEMENTED] |
| Subscript loads `a[i]` | accepted | [IMPLEMENTED] for list/dict/tuple; str get raises UFE |
| Tuple-unpacking assignment / `for` target | parsed into tuple target | [NOT IMPLEMENTED] rejected by code generation |
| Slices | parsed into `Expr.Slice` | [NOT IMPLEMENTED] rejected by code generation |
| `obj.name(args)` | parsed into `Expr.MethodCall` | [NOT IMPLEMENTED] rejected by code generation |
| `%` string formatting | parsed as `%` binary operator | [NOT IMPLEMENTED] runtime UFE |

### 6.2 Front-end rejections

The ASTBuilder rejects unsupported syntax with source location. Current categories include:

- `is` / `is not`;
- set displays; list/dict/set comprehensions and generator expressions;
- `lambda`, assignment expressions (`:=`), star expressions/unpacking and `**` unpacking;
- keyword unpacking, duplicate keyword names, defaults, `**kwargs`, keyword-only parameters,
  positional-only `/`, annotations, type parameters/type aliases and decorators;
- classes, `with`, `try`, `raise`, `assert`, imports, `global`, `nonlocal`, `match`;
- async/await/yield constructs;
- attribute access and assignment except the method-call AST form `obj.name(args)`;
- `del name`, attribute deletion, matrix multiplication, ellipsis, f-strings/t-strings, bytes,
  imaginary literals, unsupported/lone-surrogate escapes.

Some limitations are detected later by `BytecodeGenerator`, not by `ASTBuilder`: tuple-unpacking
targets, slices, method calls and calls with more than 253 arguments. The source location is
preserved in the diagnostic.

### 6.3 Strings [PARTIAL]

Implemented: concatenation, repetition, equality, ordering by Unicode code point, containment,
iteration by code point, `str`/`repr` conversion and float/int formatting. String indexing and
slicing are not implemented. The `repr` printability decision uses JDK Unicode tables rather
than an explicitly bundled CPython Unicode database.

## 7. Functions, calls, globals and scopes

### 7.1 Function representation and calling convention [IMPLEMENTED]

User function entry point:

```text
static Object body(Object capture0..captureN, PyFunction self,
                   Object arg0..argM [, PyTuple rest])
```

Builtin entry point:

```text
static Object builtin(PyFunction self, Object fixedArgs..., Object[] rest)
```

- Captures are leading parameters, followed by `self` and explicit parameters.
- A function's own recursive name is resolved through `self`, not captured.
- `*rest` in user code is packed into `PyTuple`; builtin variadic arguments use `Object[]`.
- A body is compiled lazily on first call. The compiled entry and adapters are cached in shared
  `PyFunction.Code` state.

### 7.2 Definition identity [ACCEPTED OPTIMIZATION]

Each `def` has a shared `PyFunction.Code`. For a definition site with zero captures, the
`DefCallSite` returns one `PyFunction` from a `ConstantCallSite`; repeated execution of that
site therefore returns the same function object. A definition with captures uses a factory and
creates a new function object with the captured values on each execution.

This behavior is an explicit constant-value/performance exception to CPython allocation identity.
Do not list it as a defect or schedule a change to allocate a fresh zero-capture function unless
the project revises this policy.

### 7.3 Function call sites [IMPLEMENTED]

- Calls use `invokedynamic` and `RT.bsmCall`.
- The `FunctionCallSite` keeps a bounded polymorphic guard chain with `MAX_DEPTH = 4`; after
  that it switches to a megamorphic array-based path.
- No-capture calls are guarded by function identity; capturing calls are guarded by shared
  `Code` identity and read captured values from the callee.
- The call site validates arity, adapts `*rest`, and supports reordering keyword arguments for
  user-defined functions.
- Arguments are evaluated before the arity check. Failed calls and exceptions do not permanently
  poison a site.
- `PyFunction.call(Object...)` uses the megamorphic path.
- `PyFunction.createDirectMethodHandle(MethodType)` validates arity, binds captures and self,
  adapts rest arguments and returns a direct handle.
- There is no specialized global-call path in `FunctionCallSite` today.

### 7.4 Globals [IMPLEMENTED]

A `ModuleContext` owns global cells in a `ConcurrentHashMap`. A cell starts with the builtin value
for that name, if one exists, or with an unbound (`null`) value. A module assignment updates the
cell and shadows the builtin.

`GlobalSupport` optimizes reads with a `SwitchPoint`-guarded constant value. Rebinding invalidates
the switch point; after two invalidations, the site falls back to a volatile cell read. This
switch-point mechanism exists for **global reads**; it is not a function-call-site optimization.

### 7.5 Local variables and closures [IMPLEMENTED]

`ScopeAnalyzer` performs four passes over an AST-identity-keyed function-scope tree:

1. collect locals, parameters, assignments and child scopes;
2. resolve free variables to captures or module globals;
3. enforce effectively-final capture rules;
4. check definite assignment over control flow.

Assignments bind a name for the whole function (no block scoping). Captures propagate through
intermediate functions. Captured locals cannot be reassigned; captured parameters cannot be
reassigned; captured writes in loops are rejected. Mutual recursion between nested functions is
rejected; self recursion works through `self`. Captured object mutation is allowed.

### 7.6 Compilation and diagnostic timing [PARTIAL]

- The script class and module-level code are generated eagerly.
- Function bodies are generated on first call; nested scopes are registered when their enclosing
  function is generated.
- Module-level `scopeFor` resolves passes 1–3 when needed; pass 4 (definite assignment) and
  bytecode generation happen at first call of the function body.
- Therefore, diagnostics in never-called function bodies may never be reported, and diagnostics
  in called functions may appear after the script has already produced output.

An eager source-validation/preflight phase is not implemented.

## 8. Runtime exceptions and unsupported runtime operations [PARTIAL]

Implemented Python exception classes include `PyArithmeticError`, `PyOverflowError`,
`PyZeroDivisionError`, `PyLookupError`, `PyIndexError`, `PyKeyError`, `PyAttributeError`,
`PyNameError`, `PyRuntimeError`, `PyTypeError`, and `PyValueError` (some classes are not yet
raised by supported source constructs).

`UnimplementedFeatureError` is a Java-side signal for valid operations the runtime does not
implement. Current examples include string indexing, `%` formatting, selected huge operations,
negative floating bases raised to non-integral floating exponents, and selected unknown-runtime-
value operations.

Known inconsistency: not every unimplemented/noncanonical case uses `UnimplementedFeatureError`.
For example, `HashingIntrinsics.$hash$` defaults to `PyTypeError`, while truthiness and string
conversion of unknown Java objects use UFE. Oversized tuple concatenation/repetition throws
`OutOfMemoryError`.

There is no source-level `try`/`except`/`finally`; uncaught runtime exceptions propagate out of
`Engine.run`. Python-style traceback formatting is not implemented.

## 9. Numeric behavior [IMPLEMENTED with limits and divergences]

- Integers use `Long` fast paths and promote to `BigInteger` on overflow.
- Integer true division is rounded to binary64 using a dedicated big-integer division routine.
- Floor division and modulo use Python floor/sign behavior, including floating signed zero.
- Integer powers, negative integer powers, bitwise operations and arbitrary-precision shifts have
  implementations, subject to JVM/resource limits.
- Exact int/float comparison avoids converting the integer to `double`.
- NaN is unordered and `-0.0 == 0.0`.
- `bool & bool` produces a boolean.
- Float exponentiation uses a CPython-like special-case routine and `StrictMath.pow` for the
  ordinary finite positive-base case. The last digit can differ from the platform libm used by
  CPython.
- Non-trivial left shifts with counts beyond JVM-supported limits may raise UFE or
  `PyOverflowError`; right shifts with very large positive counts are handled directly by
  sign extension (`-1` for negative values, `0` otherwise). Some large exponents and sequence
  sizes exceed JVM-supported limits and raise UFE, `PyOverflowError`, or (for tuples)
  `OutOfMemoryError`, depending on the path.
- Decimal integer literal parsing and integer-to-string conversion use the 4300-digit limit.

## 10. Intrinsics and specialization [IMPLEMENTED]

### 10.1 Intrinsic contract

Intrinsics are public static `@Intrinsic` methods with names `$name$`. The specializer enforces:

1. Pattern switches use type patterns; no `when`, record patterns or `case null`.
2. A `default` arm is expected unless the selector is sealed and cases are exhaustive.
3. Generic intrinsic bodies define the behavior for their supported canonical inputs.
4. Calls to other `$name$` methods are rewritten to `invokedynamic`; direct self-recursion is
   rejected. Private helpers, JDK calls and public non-intrinsic helpers remain direct calls.
5. Primitive results are used where appropriate (`boolean`, `long`).
6. Default arms for unsupported canonical runtime types should use UFE; the current source is not
   yet fully consistent with this rule (see §15).
7. Generic methods are supported; lookup keys use intrinsic name plus erased JVM descriptor.

### 10.2 Specializer

`engine.Specializer` reads intrinsic class files with ASM. For each public static `$name$` method
it builds a factory for a copied method body inside a hidden nestmate class. It:

- rewrites eligible `SwitchBootstraps.typeSwitch` call sites (class-only labels) to
  `RT.bsmTypeSwitch`, using the `(Object, int) -> int` descriptor and restart index zero;
- rewrites calls to other intrinsic methods into `invokedynamic` linked by `RT.bsmSpecialize`;
- rejects public static intrinsic-named methods that are not annotated `@Intrinsic`.

`SpecializationSupport` caches the immutable factory map per intrinsic class with `ClassValue`.
Each intrinsic linkage creates its own hidden class and `ConstantCallSite`.

### 10.3 Type-switch inline cache

`SpecializationSupport.InliningCache` caches exact receiver classes, checks assignable labels in
source order, chains three guards, then switches the root site to the JDK's
`SwitchBootstraps.typeSwitch`. A null selector is an internal error and results in NPE.

### 10.4 Return-type guard

`TypeGuardSupport` is a separate mutable call site emitted after calls. It caches the exact
runtime class of the returned object and returns the object unchanged.

### 10.5 JDK/class-file coupling

The Maven compiler targets Java release 25. `ClassFileVersion` chooses the generated bytecode
class-file version from the running JDK feature version, with special handling for a detected
Valhalla preview class-file marker on JDK 28+. The type-switch rewrite depends on `javac`'s
pattern-switch lowering; `IntrinsicShapeTest` checks the bytecode shape.

## 11. Limits and diagnostics [PARTIAL]

- Function body parameter slots (captures + `self` + fixed parameters + optional rest) are limited
  to 254; excess raises `SemanticErrorException`.
- A call with more than 253 arguments (callee excluded) is rejected by bytecode generation.
- String constants longer than the class-file modified-UTF-8 constant limit are emitted in
  chunks (`LDC_CHUNK = 20_000` characters).
- Decimal integer literals longer than 4300 digits are rejected by the front end.
- Integer string conversion uses the same 4300-digit CPython default.
- The 64 KB JVM method-code limit is not translated into a source diagnostic.
- Local-variable slots are allocated monotonically and never reused; exceeding the JVM slot limit
  is not translated into a friendly source diagnostic.
- Source-dependent diagnostics are not guaranteed to be eager because function bodies are
  compiled lazily (§7.6).

## 12. Build and toolchain [IMPLEMENTED with inactive corpus-generation steps]

- Maven project/artifact: `orvet`.
- Maven compiler plugin 3.16.0, `<release>25</release>`.
- ANTLR 4.13.2; ASM and ASM-util 9.11.1; JUnit Jupiter 5.14.3.
- JMH core and annotation processor 1.37 are dependencies.
- Shade plugin 3.6.2; the shaded jar manifest main class is `org.openjdk.jmh.Main` (JMH), not
  an Orvet command-line launcher.
- Surefire 3.6.0 is configured. Maven Failsafe is not configured.
- The `update-corpus` profile clones MicroPython `tests/basics` at `v1.24.1` and downloads
  python-build-standalone CPython 3.14.10 release `20261026`.
- The Python archive property is currently hardcoded to macOS aarch64; the commented Linux
  default/profile selection is not active.
- The archive extraction and golden-file generation executions in `pom.xml` are commented out.
  Resource copying from the cloned corpus remains configured.
- No SHA-256 verification of the downloaded CPython archive is configured.
- The project exposes `Engine` as a Java API; there is no command-line `orvet` launcher.

## 13. Tests and verification

### 13.1 Existing test classes

- AST/compiler: `ASTBuilderTest`, `ScopeAnalyzerTest`, `BytecodeGeneratorTest`,
  `ConformanceTestIT`, `SpecializerTest`, `SpecializerFixtures`.
- Runtime/function/global: `FunctionSupportTest`, `PyFunctionTest`, `BuiltinsTest`,
  `GlobalSwitchPointTest`, `RTTest`.
- Values/storage: `PyListTest`, `PyDictTest`, `PyTupleTest`, `PyNoneTest`.
- Intrinsics: `ArithmeticIntrinsicsTest`, `ComparisonIntrinsicsTest`,
  `HashingIntrinsicsTest`, `StringIntrinsicsTest`, `TupleIntrinsicsTest`,
  `ListIntrinsicsTest`, `DictIntrinsicsTest`, `ItemIntrinsicsTest`,
  `ContainmentIntrinsicsTest`, `IntrinsicShapeTest`.

### 13.2 Notable coverage already present

- `BuiltinsTest` covers the implemented builtin families, including `print()` with no arguments,
  `print` at monomorphic/polymorphic/megamorphic sites, `range`, and `bytearray` conversions.
- `FunctionSupportTest` covers polymorphic/megamorphic calls, closures, rest arguments, keyword
  arguments for user functions, builtin keyword errors, rebinding, evaluation-before-arity-check,
  and concurrent call/compile behavior.
- `PyListTest`, `PyDictTest`, `ListIntrinsicsTest`, `DictIntrinsicsTest`, `ItemIntrinsicsTest`,
  and `ContainmentIntrinsicsTest` exist; collection storage and several intrinsic layers are not
  covered only through `BytecodeGeneratorTest`.
- `ContainmentIntrinsicsTest` explicitly checks membership of the same NaN instance.
- `ComparisonIntrinsicsTest` includes a specialized equality comparison test.
- `TupleIntrinsicsTest` includes a generic-versus-specialized check.
- `GlobalSwitchPointTest` checks global rebinding, invalidation and builtin shadowing.
- JMH benchmark classes exist: `LookupBenchmark`, `RecursiveFunctionBenchmark`, and
  `SumLoopBenchmark`.

### 13.3 Remaining verification gaps

1. No dedicated `PyIteratorTest`, `IteratorIntrinsicsTest`, or `TruthinessIntrinsicsTest` exists.
2. `IntrinsicShapeTest` does not cover `ItemIntrinsics`. Its tuple concat/repeat positive-switch
   assertions remain commented out even though those methods do not contain a pattern switch.
3. Generic-versus-specialized equivalence is not systematically tested for every intrinsic.
   Existing targeted checks include equality and tuple operations, plus generic specializer fixtures.
4. List/dict recursive-cycle repr does not have a dedicated test.
5. No test currently verifies a source program's lazy diagnostic timing policy or friendly
   handling of the JVM 64 KB method-code / local-slot limits.
6. `ConformanceTestIT` is named as an integration test but there is no Maven Failsafe plugin, so
   the usual Surefire lifecycle does not run it as an `*IT` test. It reads pre-existing `.golden`
   files and compares stdout only; it does not launch pinned CPython via `ProcessBuilder` or
   compare uncaught exception type/message.
7. The conformance runner skips syntax/unsupported-syntax failures during test-pair creation and
   catches `UnsupportedSyntaxException` during execution, but does not skip runtime
   `UnimplementedFeatureError`. Missing builtins commonly fail as `PyNameError`.
8. There is no active Maven step that extracts the downloaded CPython archive and regenerates
   committed goldens; the relevant POM executions are commented out. No SHA-256 or
   cross-platform archive verification is present.
9. JMH benchmarks exist, but there is no isolated baseline whose sole measurement is a plain
   `invokedynamic` call to a generic intrinsic, as would be useful for attributing specialization
   gains.
10. `IntrinsicShapeTest` checks several intrinsic classes but omits `ItemIntrinsics`.
11. `ArithmeticIntrinsicsTest.augmentedImmutableTypes` contains a tautological assertion for
    `$imul$("ab", -1L)`; replace it with `assertEquals("", ArithmeticIntrinsics.$imul$("ab", -1L))`.
12. Some test suites use Java record equality for tuple assertions. That is acceptable for tests,
    but runtime code must continue to use `ComparisonIntrinsics` / `HashingIntrinsics`.

## 14. Known deviations and current implementation issues

| ID | Current state | Details / action |
|---|---|---|
| K1 | [KNOWN DIVERGENCE] | `HashingIntrinsics.$hash$` defaults to `PyTypeError("not a canonical runtime value")`, not UFE/internal assertion. Align the fallback policy or document it. |
| K2 | [KNOWN DIVERGENCE] | `IntrinsicSupport.typeName` reports `function` for builtins; CPython reports `builtin_function_or_method`. Its fallback is `java_type(<class>)`. |
| K3 | [KNOWN DIVERGENCE] | Equality dispatch is left-operand-driven: `None == unknown` returns false, while `unknown == None` can raise UFE. Review cross-type equality asymmetry. |
| K4 | [KNOWN DIVERGENCE] | Tuple concatenation/repetition over JVM array limits throws `OutOfMemoryError`; lists use UFE. Decide and unify the policy (CPython would raise `MemoryError`). |
| K5 | [PARTIAL] | Source-only diagnostics are not eagerly validated because function bodies are compiled on first call. |
| K6 | [NOT IMPLEMENTED] | Java interop normalization is absent; values are only null-checked at entry points. |
| K7 | [ACCEPTED OPTIMIZATION] | Zero-capture `def` sites return a shared constant `PyFunction`; this is an accepted exception to CPython function-allocation identity. |
| K8 | [PARTIAL] | Function call sites have no global-call fast path or global-value `SwitchPoint` integration. Global variable reads themselves do use a SwitchPoint. |
| K9 | [PARTIAL] | String indexing now raises UFE, but `$toIndex$` still hard-codes `not str` in its error text for every invalid index type. |
| K10 | [RESOLVED] | Tuple concatenation/repetition duplication in `ArithmeticIntrinsics` has been removed; arithmetic delegates to `TupleIntrinsics`. Oversize tuple error behavior remains K4. |
| K11 | [RESOLVED] | List and tuple membership use `$sameOrEq$`, including same-object NaN membership. |
| K12 | [RESOLVED] | `print` writes an explicit newline with `System.out.print`, rather than relying on platform `println` line separators. |
| K13 | [RESOLVED] | Builtins use an explicit zero-fixed-parameter variadic ABI; `print()` and N-argument builtin calls work through ordinary and megamorphic paths. |
| K14 | [RESOLVED] | Duplicate `PyFunction` arity-error code has been removed; `FunctionSupport.checkArity` is shared. |
| K15 | [RESOLVED] | Lone-surrogate escapes are rejected during literal decoding. |
| K16 | [KNOWN DIVERGENCE] | `float ** float` uses `StrictMath.pow` after CPython-like special cases; last-bit results can differ from the host libm used by CPython. |
| K17 | [KNOWN DIVERGENCE] | String repr printability follows the JDK's Unicode tables, not a separately pinned CPython Unicode 16.0 database. |
| K18 | [ACCEPTED DIVERGENCE] | NaN hashes to zero rather than using identity-derived CPython hashing. |
| K19 | [DOC/COMMENT CLEANUP] | Some Javadocs/comments are stale: `TupleIntrinsics` says selectors are cast to `Object` although the code has no such casts; `PyList` Javadoc says it implements `java.util.List` although it exposes `asJavaList()`; revise comments to match code. `PyDict` is a wrapper-view design, not an `AbstractMap` subclass. |

## 15. Work still to decide or implement

1. Decide whether `range` and `bytearray` remain explicit approximations or become proper runtime
   types; do not imply CPython type semantics while they are lists.
2. Decide whether missing CPython builtin names should produce UFE (recognize known builtin names)
   rather than `PyNameError`.
3. Decide which Python container/string methods to add and how to represent method calls.
4. Decide whether builtin keyword arguments get a small keyword-capable ABI; user-function keyword
   arguments already work.
5. Add proper range/slice/type objects if those features are brought into scope.
6. Decide the policy for tuple allocation failures and `MemoryError` mapping.
7. Add an eager validation/preflight phase if no-output-on-rejection becomes a requirement.
8. Add systematic generic-versus-specialized property tests and missing iterator/truthiness tests.
9. Wire conformance tests into the Maven lifecycle, invoke a checksum-verified CPython build,
   regenerate goldens reproducibly and select platform-specific Python archives.
10. Add a direct generic-indy JMH baseline before attributing performance gains to specialization.
11. Add a command-line launcher and specify exit-code/uncaught-exception formatting only if a CLI is
    a project goal; the current shaded jar is a JMH runner.
12. Consider replacing the repr recursion guard's `ThreadLocal<IdentityHashMap<...>>` with
    `ScopedValue` when the supported JDK/runtime policy permits it.
13. Consider a specialized global-call path separately from the existing global-read SwitchPoint
    optimization.
