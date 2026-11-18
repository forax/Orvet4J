# Orvet4J — Implementation Status and Forward Plan

## 0. Status vocabulary and semantic target

Use these labels consistently:

- **[IMPLEMENTED]** — source and runtime paths exist for the stated behavior.
- **[PARTIAL]** — a useful subset exists; limitations are stated explicitly.
- **[NOT IMPLEMENTED]** — unsupported or currently unavailable.
- **[KNOWN DIVERGENCE]** — accepted behavior differs from the CPython reference.
- **[DEFECT]** — the current implementation appears internally inconsistent or incorrect.
- **[ACCEPTED OPTIMIZATION]** — an intentional implementation choice whose observable
  consequences are documented and accepted for now.

CPython 3.14 is the semantic reference for the supported subset. Do not infer that a
construct is conformant merely because it parses or executes. Tests are a mix of focused
runtime tests and a checked-in golden-output corpus; they do not provide complete
differential coverage of every accepted operation.

### 0.1 Constant-origin values

The runtime deliberately permits sharing/canonicalization for some values that originate
as JVM constants or immutable constant-like values. Examples include `ConstantDynamic`
literals, JVM strings, boolean and `None` singletons, the empty tuple.

A definition site for a zero-capture function also returns one shared `PyFunction`;
repeatedly executing that `def` site can therefore return the same function object.
A capturing definition site creates a function with the current captured
values. This function-identity difference is an **[ACCEPTED OPTIMIZATION]**,
not a task to change without revisiting the policy.

NaN hashing is another explicit difference: `HashingIntrinsics` returns the constant hash
`0` for NaN rather than CPython's identity-derived hash. NaN equality remains false, and
container membership uses an identity-or-equality shortcut.

These exceptions do not justify arbitrary semantic shortcuts.

### 0.2 Architectural principles

1. Python values are represented by ordinary JVM objects; there is no universal `PyValue`
   hierarchy.
2. Generic runtime intrinsic methods are the readable source of truth.
   Specialized intrinsic bodies are generated from them and should preserve their behavior.
3. `invokedynamic` is used for dynamic operators, comparisons, item operations, iteration,
   global access, function definition, and function calls.
   Some collection and tuple construction paths use direct JVM calls.
4. Lists and dictionaries are mutable runtime objects; tuples, ranges, and slices are
   immutable value objects.
5. Python equality and hashing are implemented by runtime intrinsics, not by substituting
   Java `equals`/`hashCode` for Python semantics.
6. Unsupported syntax is rejected with located syntax/semantic diagnostics where the
   relevant compiler path detects it. Some unsupported AST forms are rejected later by
   bytecode generation.
7. Runtime failures are not yet represented by one uniform policy: Python exception
   classes, `UnimplementedFeatureError`, and `NotCanonicalException` are all present.

## 1. Architecture [IMPLEMENTED, with lazy function-body compilation]

```text
Python source
  └─ ANTLR lexer/parser (Python grammar)
       └─ ASTBuilder
            └─ AST: Script / Block / Stmt / Expr / Target
                 └─ Engine.compile / BytecodeGenerator
                      └─ generated Script.__main__()
                           └─ RT.defineClass / FunctionClassLoader
                                └─ invokedynamic bootstraps in RT
                                     ├─ global access: GlobalSupport
                                     ├─ function definition/call: FunctionSupport
                                     ├─ intrinsic dispatch: SpecializationSupport
                                     └─ type-switch dispatch: SpecializationSupport
                                          └─ engine.Specializer (ASM)
```

### 1.1 Source packages

| Package | Current contents |
|---|---|
| `grammar.antlr` | ANTLR lexer/parser sources and `PythonLexerBase` |
| `ast` | AST records, `ASTBuilder`, `ASTPrinter`, literal decoding, syntax exceptions |
| `engine` | `Engine`, `BytecodeGenerator`, `ScopeAnalyzer`, `Specializer`, class-file version and semantic diagnostics |
| `rt` | `RT`, `ModuleContext`, `Dictionary`, function/global/specialization support, runtime values, iterators and builtins |
| `rt.intrinsic` | `@Intrinsic` entry points and intrinsic helpers, including arithmetic, item, range and type operations |
| `rt.error` | Python exception classes and `UnimplementedFeatureError` |
| `perf` | JMH benchmarks and benchmark Python programs |

### 1.2 Compilation and generated code

- `Engine.compile` parses are supplied as an AST and generates a `Script` class with a
  static `__main__` method. `Engine.run` is the Java API for running source or an AST.
- `RT.defineClass` creates a private `FunctionClassLoader` for a generated class. The
  loader carries its `ModuleContext` and the `Dictionary` mapping function-definition
  sites to AST nodes.
- Module-level code and the script class are generated eagerly. Function bodies are
  generated on their first call and cached in shared `PyFunction.Code` state.
- Dynamic operations are linked through bootstraps in `RT`; list/dict display construction
  and some tuple construction are emitted as direct JVM operations.
- A return-type guard call site is emitted after a function call to specialize the observed
  result class.

## 2. Runtime values and representation [IMPLEMENTED / PARTIAL]

| Python concept | Current representation | Status and notes |
|---|---|---|
| `None` | `PyNone.INSTANCE` | [IMPLEMENTED] singleton |
| `bool` | `Boolean` | [IMPLEMENTED] |
| `int` | `Long` when it fits, otherwise `BigInteger` | [IMPLEMENTED] normalized arithmetic values |
| `float` | `Double` | [IMPLEMENTED] arithmetic representation; `float()` constructor has a defect (see §14) |
| `str` | `String` | [PARTIAL] code-point-aware length, iteration, indexing and slicing; no method table |
| `tuple` | sealed `PyTuple` (`PyTuple1..4`, `PyTupleN`) | [IMPLEMENTED] core immutable operations, including slicing |
| `list` | final `PyList<E>` | [IMPLEMENTED] core mutable operations and slicing; no Python method table |
| `dict` | final `PyDict<K,V>` | [IMPLEMENTED] core mapping operations; no Python method table |
| Python function | `PyFunction` / shared `PyFunction.Code` | [IMPLEMENTED] with lazy compilation and constant no-capture definition sites |
| `range` | sealed `PyRange`: `PyRangeSmall` or `PyRangeBig` | [PARTIAL] dedicated immutable sequence; see §4.4 |
| `slice` | immutable `PySlice` | [IMPLEMENTED] represented by start/stop/step values and resolved against a sequence length |
| type object | `PyType` enum | [PARTIAL] built-in type objects only; no user-defined classes or general metaclass model |
| iterators | nested `PyIterator` implementations | [IMPLEMENTED] internal iteration for tuple, string, list, dict and range |

### 2.1 Representation invariants

- `BigInteger` arithmetic results are normalized to `Long` whenever the value fits.
- `PyTuple` uses fixed records for one to four elements and `PyTupleN` for the empty tuple
  and larger tuples. The empty tuple is shared.
- `PyRangeSmall` stores start/stop/step/length as `long`; `PyRangeBig` stores them
   as `BigInteger`. The representation is selected to avoid overflow in span/length arithmetic.
- `PySlice` stores start/stop/step as runtime values; omitted slice components are represented
  by `PyNone.INSTANCE`.
- Runtime values and container contents reject Java `null`. `null` is reserved for internal
  states such as an unbound global, absent dictionary lookup result, or exhausted iterator.
- `PyDict.get` returns Java `null` for an absent key; stored values cannot be Java `null`.
- Lists and dictionaries are not thread-safe. Synchronization is the caller's responsibility.

### 2.2 Java interop views

- `PyList` does not implement `java.util.List`; it exposes an `asJavaList()` adapter/view.
- `PyDict` does not implement `java.util.Map`; it exposes an `asJavaMap()` adapter/view.
- `PyTuple` exposes `asJavaList()`.
- Java equality on storage classes is not the Python equality protocol. Python-visible
  equality is routed through `ComparisonIntrinsics`.
- [NOT IMPLEMENTED] There is no general `Interop.toPython` normalization boundary. Global
  assignment and collection storage null-check values but do not normalize arbitrary Java
  wrappers such as `Integer`, `Short`, `Byte`, `Float`, or `Character` into canonical Python
  representations. Intrinsics expect canonical runtime values.

## 3. Constants and value creation [IMPLEMENTED]

- `Long` and `Double` literals are emitted through `ConstantDynamic` and `RT.bsmConst`.
- `BigInteger` literals use `ConstantDynamic` and `RT.bsmBigInt`, which parses a decimal string.
- Booleans load `Boolean.TRUE` / `Boolean.FALSE`; `None` loads `PyNone.INSTANCE`.
- Strings use JVM string constants. Long strings are split into chunks and concatenated.
- List and dictionary displays allocate fresh objects and evaluate elements in order.
- Tuple displays are not generally constant-folded by the bytecode generator: empty tuple uses
  `TupleIntrinsics.empty`; sizes one to four construct fixed records directly; larger displays
  use an array and `TupleIntrinsics.fromArray`.
- A zero-capture `def` site links to a `ConstantCallSite` containing one `PyFunction`; a
  capturing `def` site is a factory that captures values for each execution.

## 4. Collections and sequence operations

### 4.1 Lists [IMPLEMENTED core operations; methods are NOT IMPLEMENTED]

`PyList` is array-backed, grows by approximately 1.5×, and caps storage at
`Integer.MAX_VALUE - 8`. Oversize list storage raises `UnimplementedFeatureError`.

Python-facing operations implemented through `ListIntrinsics` and `ItemIntrinsics` include:

- length, integer indexing with negative indices, item assignment and deletion;
- slicing, slice assignment and slice deletion;
- concatenation, repetition, in-place `+=` and `*=`;
- membership, equality and ordering through runtime intrinsics;
- truthiness and string/repr rendering.

The list iterator reads the list's current size at every `next`, so mutations can affect an
ongoing iteration. `PyList` also has Java storage methods such as `append`, `insert`, `remove`,
`clear`, and `repeatInPlace`; these do not make Python methods callable from source. Python
`list.append`, `pop`, `insert`, `sort`, and related methods are not exposed through a method
table. Method-call syntax is currently rejected by bytecode generation (§7.2).

### 4.2 Dictionaries [IMPLEMENTED core mapping behavior; methods are NOT IMPLEMENTED]

`PyDict` stores entries in insertion order using parallel key/value/hash arrays and an
open-addressing index. The index uses perturbation probing and tombstones; sparse entry
storage can be compacted.

Implemented operations include length, lookup, assignment, deletion, membership, equality,
truthiness, repr, and iteration over keys. Replacing the value for an equal key retains the
original key object and position. Deleting and reinserting appends the key at the end.

Dictionary iteration allows value replacement but detects key-set/size changes and raises
`PyRuntimeError`; after failure, the iterator remains failed. Dicts are unhashable; list/dict
keys are rejected by hashing.

The Python method table (`get`, `pop`, `setdefault`, `update`, `clear`, `copy`, etc.) is not
implemented. `items`, `keys`, and `values` are not exposed as Python methods.

### 4.3 Tuples [IMPLEMENTED core operations]

Tuple storage and core operations are implemented: size, integer indexing, slicing,
concatenation, repetition, equality, hashing, ordering, membership, iteration, and repr.
Tuple displays are accepted and compiled. Tuple unpacking is not implemented.

Tuple operations may reuse immutable tuple objects (empty tuple, concatenation with an empty
operand, and repetition by one). Very large tuple concatenation/repetition is still subject to
JVM array/resource limits; allocation failure is not consistently translated to a Python
`MemoryError`-like exception.

### 4.4 Ranges [PARTIAL: dedicated runtime type]

`range(...)` now creates an immutable `PyRange`, not a `PyList`. `PyRangeSmall` and
`PyRangeBig` represent ranges whose arithmetic fits in `long` or requires `BigInteger`.
`RangeIntrinsics` provides:

- construction for one, two, or three integer-like (`bool`/integer) arguments and zero-step
  validation;
- length (raising `PyOverflowError` when the length does not fit the supported signed
  sequence-size range), emptiness, indexing, membership, sequence equality and hashing;
- lazy iteration through dedicated small-range and big-range iterators;
- slicing that returns another range for supported lengths.

Known limitations: slicing a range whose length exceeds `2**63 - 1` raises
`UnimplementedFeatureError`. Construction currently accepts the canonical integer/bool
representations rather than a general Python `__index__` protocol. The existence of `PyRange`
does not imply full CPython range behavior in every boundary case.

### 4.5 Slices [IMPLEMENTED for supported sequence operations]

The parser/AST and bytecode generator build a `PySlice` from slice syntax. `ItemIntrinsics`
uses it for string, list, tuple, and range subscripting. List slice assignment and deletion are
also implemented. Tuple, string, and range are immutable and reject item assignment/deletion.
Slice-bound normalization handles omitted bounds, negative bounds, positive/negative steps,
and a zero-step `PyValueError`. Very large range slicing has the limitation in §4.4.

### 4.6 Hashing [IMPLEMENTED for canonical values, with documented divergence]

- Lists and dictionaries raise `PyTypeError` as unhashable types.
- Equal numeric values across bool/int/float representations have compatible hashes.
- Integer and float hashes follow the 64-bit CPython algorithm for supported values.
- Range hashing uses a canonical tuple derived from the sequence; slice hashing uses its
  `(start, stop, step)` tuple representation.
- String, tuple, `None`, and function hash values are implementation details; tests should
  not assert exact values unless specifically testing the algorithm.
- NaN hashes to constant zero [ACCEPTED OPTIMIZATION / KNOWN DIVERGENCE].
- Hashing a non-canonical Java object raises `NotCanonicalException`, not a Python exception
  or `UnimplementedFeatureError`.

## 5. Intrinsics and builtins

### 5.1 Intrinsic inventory [IMPLEMENTED]

Intrinsic entry points are public static methods annotated `@Intrinsic`, with names such as
`$add$` or `$getItem$`.

| Class | Current responsibilities |
|---|---|
| `ArithmeticIntrinsics` | numeric/string/tuple/list arithmetic, unary operations, bitwise operations, shifts and augmented-assignment variants |
| `ComparisonIntrinsics` | equality, inequality, ordering, comparison chains and identity-or-equality helper |
| `HashingIntrinsics` | Python-compatible hashes for supported canonical values; explicit NaN policy |
| `TupleIntrinsics` | tuple factories, size/get/concat/repeat/membership/slicing |
| `ListIntrinsics` | list construction, size/get/set/delete/concat/repeat/in-place operations/membership/slicing |
| `DictIntrinsics` | dict construction, size/get/set/delete/membership/equality |
| `ItemIntrinsics` | `len`, generic get/set/delete item, index conversion; string/range/list/tuple/dict subscripting |
| `RangeIntrinsics` | range construction helpers, size/empty/get/membership/slicing/iteration/equality support |
| `TypeIntrinsics` | `type`, `bool`, and `int` conversions (but no `$float$` implementation yet) |
| `ContainmentIntrinsics` | generic containment dispatch |
| `TruthinessIntrinsics` | truthiness and logical not |
| `IteratorIntrinsics` | iterator creation and next-element dispatch |
| `StringIntrinsics` | `str` and `repr` conversion, including numeric formatting |

`len` is linked to `ItemIntrinsics.$len$`; `type`, `bool`, and `int` use `TypeIntrinsics`.
The presence of a runtime type or intrinsic does not imply that all related builtins or
methods have been implemented.

### 5.2 Augmented assignment [IMPLEMENTED]

Arithmetic/bitwise operators have augmented-assignment intrinsic paths. Most immutable-value
paths delegate to the corresponding binary operation. List `+=` and `*=` mutate the existing
list and return it.

- Name target: load, evaluate right operand, apply augmented intrinsic, store.
- Subscript target: evaluate container and index once, read old item, evaluate right operand,
  apply operation, then write the result back.

### 5.3 Builtin registry and current implementation [PARTIAL]

`Builtins.CodeInitialization` declares constructor/type functions and a registry of builtin
names. `Builtins.BUILTINS` is a shared mutable `HashMap`; `ModuleContext` initializes each
new global cell with the builtin of the same name when one exists. Assigning a module global
shadows that initial builtin value. There is no separate per-read builtin fallback.

The registry includes the following implemented or specialized operations (subject to the
limits of their implementations):

- `abs`, `all`, `any`, `ascii`, `bin`, `bool`, `callable`, `chr`, `dict`, `divmod`, `hex`,
  `int`, `len`, `list`, `max`, `min`, `oct`, `ord`, `pow`, `print`, `range`, `repr`, `round`,
  `slice`, `str`, `sum`, `tuple`, and `type`;
- constructor/type objects for `int`, `bool`, `float`, `str`, `tuple`, `list`, `dict`,
  `range`, and `slice`, plus internal `NoneType`/`function` constructor metadata.

Important limits and caveats:

- `float` is registered as a type constructor, but the current `TypeIntrinsics` class has no
  `$float$` method even though the constructor is configured to specialize that method.
  The first call to `float(...)` therefore appears to fail during specialization linkage
  (see defect D1 in §14).
- `int` supports zero, one, or two arguments in the implemented forms; `bool` supports zero
  or one argument. `str`, `list`, `tuple`, `dict`, `range`, and `slice` constructors have
  limited argument handling, not a complete CPython constructor surface.
- `range` returns `PyRange`; `slice` returns `PySlice`.
- `print(*args)` converts values with `$str$`, joins with one space, writes an explicit `\n`
  using `System.out.print`, and returns `None`.
- User-defined functions support keyword arguments. Builtin keyword support is not
  implemented: fixed-arity builtins reject keywords with `PyTypeError`; variadic builtins
  reject keyword calls with `UnimplementedFeatureError`.

The registry also contains names for builtins that are not implemented. Examples include
`bytearray`, `bytes`, `hash`, `isinstance`, `issubclass`, `iter`, `next`, `enumerate`, `map`,
`filter`, `zip`, `set`, `object`, `getattr`, `setattr`, `open`, `eval`, and `exec`. These
names are registered so lookup finds a builtin function, but resolution fails with
`UnimplementedFeatureError` when no implementation exists. A genuinely unknown name still
raises `PyNameError`.

### 5.4 Special methods and attribute access [NOT IMPLEMENTED]

There is no generic Python special-method or descriptor dispatch. Arithmetic, comparison,
container and item behavior is implemented directly for supported runtime representations.
Python attribute access/assignment and method-call execution are not implemented. The AST
can represent a method-call form, but bytecode generation rejects it (§7.2).

## 6. Language subset

### 6.1 Parsed and generated constructs

| Construct | Front end / AST | Bytecode/runtime status |
|---|---|---|
| Expression statements, `pass`, `return`, `break`, `continue` | accepted | [IMPLEMENTED] |
| Name assignment and chained assignment | accepted | [IMPLEMENTED] |
| Subscript assignment and deletion | accepted | [IMPLEMENTED] for supported containers |
| Augmented assignment to names/subscripts | accepted | [IMPLEMENTED] |
| `if` / `elif` / `else` | accepted | [IMPLEMENTED] |
| `while` and `for`, including loop `else` | accepted | [IMPLEMENTED] `for` supports string, tuple, list, dict and range iterables |
| `def`, nested defs, closures, positional args and one `*rest` | accepted | [IMPLEMENTED] |
| Calls with positional arguments | accepted | [IMPLEMENTED] |
| Calls with keyword arguments | accepted | [IMPLEMENTED] for user functions; builtin keyword limits are in §5.3 |
| Arithmetic, bitwise, comparisons, comparison chains | accepted | [IMPLEMENTED] for supported value types |
| `and`, `or`, `not`, conditional expressions | accepted | [IMPLEMENTED] |
| `in` / `not in` | accepted | [IMPLEMENTED] for supported strings, tuples, lists, dicts and ranges |
| List and dictionary displays | accepted | [IMPLEMENTED] |
| Tuple displays | accepted | [IMPLEMENTED] |
| Subscript loads `a[i]` | accepted | [IMPLEMENTED] for list/dict/tuple/range/string |
| Slice expressions and `a[start:stop:step]` | accepted | [IMPLEMENTED] as described in §4.5 |
| List slice assignment/deletion | accepted | [IMPLEMENTED] |
| Tuple-unpacking assignment / `for` target | parsed into tuple target | [NOT IMPLEMENTED] rejected by code generation |
| `obj.name(args)` | parsed into `Expr.MethodCall` | [NOT IMPLEMENTED] rejected by code generation |
| `%` string formatting | parsed as `%` binary operator | [NOT IMPLEMENTED] runtime `UnimplementedFeatureError` |

### 6.2 Front-end rejections

The AST builder rejects unsupported syntax with source location. Current unsupported
categories include:

- `is` / `is not`;
- set displays; list/dict/set comprehensions and generator expressions;
- `lambda`, assignment expressions (`:=`), star expressions/unpacking and `**` unpacking;
- keyword unpacking, duplicate keyword names, defaults, `**kwargs`, keyword-only parameters,
  positional-only `/`, annotations, type parameters/type aliases and decorators;
- classes, `with`, `try`, `raise`, `assert`, imports, `global`, `nonlocal`, `match`;
- async/await/yield constructs;
- attribute access and assignment (except that a method-call form can be represented in the
  AST before later rejection by bytecode generation);
- `del name`, attribute deletion, matrix multiplication, ellipsis, f-strings/t-strings, bytes,
  imaginary literals, unsupported escapes and lone-surrogate escapes.

Some limitations are represented in the AST and rejected later by bytecode generation rather
than by `ASTBuilder`.

### 6.3 Strings [PARTIAL]

Implemented: concatenation, repetition, equality, ordering by Unicode code point, containment,
iteration by code point, length by code point, indexing, slicing, `str`/`repr` conversion and
integer/float formatting. String item access currently materializes a code-point array, so
indexing/slicing is not an optimized constant-time operation. String methods and `%` formatting
are not implemented. The `repr` printability decision uses JDK Unicode tables rather than a
separately pinned CPython Unicode database.

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

### 7.2 Function call sites [IMPLEMENTED]

- Calls use `invokedynamic` and `RT.bsmCall`.
- `FunctionCallSite` keeps a bounded polymorphic guard chain with `MAX_DEPTH = 4`; after that
  it switches to a megamorphic array-based path.
- No-capture calls are guarded by function identity; capturing calls are guarded by shared
  `Code` identity and read captured values from the callee.
- The call site validates arity, adapts `*rest`, and supports reordering keyword arguments
  for user-defined functions.
- Arguments are evaluated before the arity check. Failed calls and exceptions do not
  permanently poison a site.
- `PyFunction.call(Object...)` uses the megamorphic path.
- `PyFunction.createDirectMethodHandle(MethodType)` validates arity, binds captures and self,
  adapts rest arguments and returns a direct handle.
- There is no specialized global-call fast path or integration of global-value switch points
  into `FunctionCallSite`.

### 7.3 Definition identity [ACCEPTED OPTIMIZATION]

Each `def` has shared `PyFunction.Code`. A definition site with zero captures returns one
`PyFunction` from a `ConstantCallSite`; repeated execution of that site therefore returns the
same function object. A definition with captures uses a factory and creates a new function
object with the captured values on each execution. This is the accepted constant-origin
optimization described in §0.1.

### 7.4 Globals [IMPLEMENTED]

A `ModuleContext` owns global cells in a `ConcurrentHashMap`. A cell starts with the builtin
value for that name, if one exists, or with an unbound (`null`) value. A module assignment
updates the cell and shadows the builtin.

`GlobalSupport` optimizes reads with a `SwitchPoint`-guarded constant value. Rebinding
invalidates the switch point; after two invalidations, the site falls back to a volatile cell
read. This optimization applies to global reads, not to function-call-site dispatch. Stores
use the global cell and invalidate an existing switch point.

### 7.5 Local variables and closures [IMPLEMENTED]

`ScopeAnalyzer` performs four passes over an AST-identity-keyed function-scope tree:

1. collect locals, parameters, assignments and child scopes;
2. resolve free variables to captures or module globals;
3. enforce effectively-final capture rules;
4. check definite assignment over control flow.

Assignments bind a name for the whole function (no block scoping). Captures propagate through
intermediate functions. Captured locals and captured parameters cannot be reassigned; captured
writes in loops are rejected. Mutual recursion between nested functions is rejected; self
recursion works through `self`. Captured object mutation is allowed.

### 7.6 Compilation and diagnostic timing [PARTIAL]

- The script class and module-level code are generated eagerly.
- Function bodies are generated on first call; nested scopes are registered when their
  enclosing function is generated.
- Module-level scope analysis resolves the first three passes when needed; definite
  assignment and bytecode generation happen at first call of a function body.
- Diagnostics in never-called function bodies may never be reported, and diagnostics in
  called functions may appear after the script has already produced output.

An eager source-validation/preflight phase is not implemented.

## 8. Runtime exceptions and unsupported runtime operations [PARTIAL]

Implemented Python exception classes include `PyArithmeticError`, `PyOverflowError`,
`PyZeroDivisionError`, `PyLookupError`, `PyIndexError`, `PyKeyError`, `PyAttributeError`,
`PyNameError`, `PyRuntimeError`, `PyTypeError`, and `PyValueError` (not every class is
currently raised by supported source constructs).

`UnimplementedFeatureError` is a Java-side signal for valid operations the runtime does not
implement. Examples include `%` formatting, missing builtin implementations, selected huge
operations and some range-slicing sizes. `NotCanonicalException` is another Java-side signal
used when non-canonical Java values reach operations such as hashing, comparison, type lookup,
or conversion.

There is no source-level `try` / `except` / `finally`; uncaught runtime exceptions propagate
out of `Engine.run`. Python-style traceback formatting is not implemented.

## 9. Numeric behavior [IMPLEMENTED with limits and divergences]

- Integers use `Long` fast paths and promote to `BigInteger` on overflow.
- Integer true division is rounded to binary64 using a dedicated big-integer division routine.
- Floor division and modulo use Python floor/sign behavior, including floating signed zero.
- Integer powers, negative integer powers, bitwise operations and arbitrary-precision shifts
  have implementations, subject to JVM/resource limits.
- Exact int/float comparison avoids converting the integer to `double`.
- NaN is unordered and `-0.0 == 0.0`.
- `bool & bool` produces a boolean.
- Float exponentiation uses CPython-like special cases and `StrictMath.pow` for the ordinary
  finite positive-base case. The last digit can differ from the platform libm used by CPython.
- Non-trivial left shifts with counts beyond JVM-supported limits may raise UFE or
  `PyOverflowError`; right shifts with very large positive counts are handled directly by
  sign extension (`-1` for negative values, `0` otherwise). Some large exponents and sequence
  sizes exceed JVM-supported limits and raise UFE, `PyOverflowError`, or (for tuples)
  `OutOfMemoryError`, depending on the path.
- Decimal integer literal parsing and integer-to-string conversion use the 4300-digit limit.
- `int(float('nan'))` raises `PyValueError`; conversion of positive/negative infinity raises
  `PyOverflowError`.

## 10. Intrinsics and specialization [IMPLEMENTED]

### 10.1 Intrinsic contract

Intrinsics are public static `@Intrinsic` methods with names `$name$`. The specializer enforces:

1. Pattern switches use type patterns; no `when`, record patterns or `case null`.
2. A `default` arm is expected unless the selector is sealed and cases are exhaustive.
3. Generic intrinsic bodies define behavior for supported canonical inputs.
4. Calls to other `$name$` methods are rewritten to `invokedynamic`; direct self-recursion is
   rejected. Private helpers, JDK calls and public non-intrinsic helpers remain direct calls.
5. Primitive results are used where appropriate (`boolean`, `long`).
6. Non-canonical inputs are not handled uniformly; some paths throw `NotCanonicalException`,
   others use UFE or a Python exception.
7. Generic methods are supported; lookup keys use intrinsic name plus erased JVM descriptor.

### 10.2 Specializer

`engine.Specializer` reads intrinsic class files with ASM. For each public static `$name$`
method it builds a factory for a copied method body inside a hidden nestmate class. It:

- rewrites eligible `SwitchBootstraps.typeSwitch` call sites (class-only labels) to
  `RT.bsmTypeSwitch`, using the `(Object, int) -> int` descriptor and restart index zero;
- rewrites calls to other intrinsic methods into `invokedynamic` linked by
  `RT.bsmSpecialize`;
- rejects public static intrinsic-named methods that are not annotated `@Intrinsic`.

`SpecializationSupport` caches the immutable factory map per intrinsic class with `ClassValue`.
Each intrinsic linkage creates its own hidden class and `ConstantCallSite`.

### 10.3 Type-switch inline cache

`SpecializationSupport.InliningCache` caches exact receiver classes, checks assignable labels
in source order, chains three guards, then switches the root site to the JDK's
`SwitchBootstraps.typeSwitch`. A null selector is an internal error and results in NPE.

### 10.4 JDK/class-file coupling

The Maven compiler targets Java release 25. `ClassFileVersion` chooses the generated bytecode
class-file version from the running JDK feature version, with special handling for a detected
Valhalla preview class-file marker on JDK 28+. The type-switch rewrite depends on `javac`'s
pattern-switch lowering; `IntrinsicShapeTest` checks selected bytecode shapes.

## 11. Limits and diagnostics [PARTIAL]

- Function body parameter slots (captures + `self` + fixed parameters + optional rest) are
  limited to 254; excess raises `SemanticErrorException`.
- A call with more than 253 arguments (callee excluded) is rejected by bytecode generation.
- String constants longer than the class-file modified-UTF-8 constant limit are emitted in
  chunks (`LDC_CHUNK = 20_000` characters).
- Decimal integer literals longer than 4300 digits are rejected by the front end.
- Integer string conversion uses the same 4300-digit CPython default.
- The 64 KB JVM method-code limit is not translated into a source diagnostic.
- Local-variable slots are allocated monotonically and never reused; exceeding the JVM slot
  limit is not translated into a friendly source diagnostic.
- Source-dependent diagnostics are not guaranteed to be eager because function bodies are
  compiled lazily (§7.6).

## 12. Build and toolchain [IMPLEMENTED; conformance corpus generation is inactive]

- Maven project/artifact: `com.github.forax.ula4j:orvet:0.1`.
- Maven compiler plugin 3.16.0, `<release>25</release>`.
- ANTLR 4.13.2; ASM and ASM-util 9.11.1; JUnit Jupiter 5.14.3.
- JMH core and annotation processor 1.37 are dependencies.
- Shade plugin 3.6.2; the shaded jar manifest main class is `org.openjdk.jmh.Main` (JMH),
  not an Orvet command-line launcher.
- Surefire 3.6.0 is configured. Maven Failsafe is not configured.
- The `update-corpus` profile clones MicroPython `tests/basics` at `v1.24.1` and downloads
  python-build-standalone CPython 3.14.10 release `20261026`.
- The active Python archive property is macOS aarch64; the commented Linux x86_64 default
  and platform profiles are not active.
- Archive extraction and golden-file generation executions in `pom.xml` are commented out.
  Copying the cloned corpus resources remains configured.
- No SHA-256 verification of the downloaded CPython archive is configured.
- The project exposes `Engine` as a Java API; there is no command-line `orvet` launcher.

## 13. Tests and verification

### 13.1 Existing test classes

- AST/compiler: `ASTBuilderTest`, `ScopeAnalyzerTest`, `BytecodeGeneratorTest`,
  `ConformanceTestIT`, `SpecializerTest`, `SpecializerFixtures`.
- Runtime/functions/globals: `FunctionSupportTest`, `GlobalCallTest`, `PyFunctionTest`,
  `BuiltinsTest`, `GlobalSwitchPointTest`, `RTTest`.
- Values/storage: `PyListTest`, `PyDictTest`, `PyTupleTest`, `PyNoneTest`, `PyIteratorTest`,
  `PyRangeTest`, `PyTypeTest`.
- Intrinsics: `ArithmeticIntrinsicsTest`, `ComparisonIntrinsicsTest`,
  `HashingIntrinsicsTest`, `StringIntrinsicsTest`, `TupleIntrinsicsTest`,
  `ListIntrinsicsTest`, `DictIntrinsicsTest`, `ItemIntrinsicsTest`,
  `ContainmentIntrinsicsTest`, `RangeIntrinsicsTest`, `TypeIntrinsicsTest`,
  `TruthinessIntrinsicsTest`, `IntrinsicShapeTest`. There is no dedicated
  `IteratorIntrinsicsTest`; iterator behavior is covered by `PyIteratorTest` and
  `IntrinsicShapeTest`.

### 13.2 Notable coverage already present

- `BuiltinsTest` exercises implemented builtin families, including `range`, `slice`, `type`,
  and type-conversion behavior; the current suite should not be described as testing a working
  `bytearray` implementation.
- `PyRangeTest` and `RangeIntrinsicsTest` cover range representation boundaries, construction,
  length/indexing, membership, equality and slicing, including large integer cases.
- `PyTypeTest` and `TypeIntrinsicsTest` cover built-in type metadata, type recognition,
  truthiness, and implemented `int`/`bool` conversions. They do not demonstrate a working
  `float()` conversion.
- `PyIteratorTest` covers iterator storage/behavior; `IntrinsicShapeTest` checks iterator and
  truthiness intrinsic bytecode shapes.
- `GlobalCallTest` covers direct/recursive/builtin calls, keyword calls to user functions,
  closures assigned to globals, and repeated rebinding of a global function.
- `FunctionSupportTest` covers polymorphic/megamorphic calls, closures, rest arguments,
  keyword arguments for user functions, builtin keyword errors, rebinding,
  evaluation-before-arity-check, and concurrent call/compile behavior.
- `GlobalSwitchPointTest` checks global rebinding, invalidation and builtin shadowing.
- `ComparisonIntrinsicsTest` includes a specialized equality comparison test; `TupleIntrinsicsTest`
  includes a generic-versus-specialized check.
- JMH benchmark classes exist: `LookupBenchmark`, `RecursiveFunctionBenchmark`, and
  `SumLoopBenchmark`.

### 13.3 Remaining verification gaps

1`IntrinsicShapeTest` does not cover `ItemIntrinsics`, `RangeIntrinsics`, or `TypeIntrinsics`.
   Its tuple concat/repeat positive-switch assertions remain commented out.
2. Generic-versus-specialized equivalence is not systematically tested for every intrinsic.
   Add cross-checks for all intrinsic methods, especially new range/type/item operations.
3. List/dict recursive-cycle repr does not have a dedicated test.
4. No test verifies the lazy diagnostic timing policy or friendly handling of JVM 64 KB
   method-code / local-slot limits.
5. `ConformanceTestIT` is named as an integration test but no Maven Failsafe plugin is
   configured, so the usual Maven lifecycle does not run it as an `*IT` test. It reads
   pre-existing `.golden` files and compares stdout only; it does not launch pinned CPython
   via `ProcessBuilder` or compare uncaught exception type/message.
6. The conformance runner filters syntax/unsupported-syntax failures during test-pair creation
   and catches `UnsupportedSyntaxException` during execution, but does not classify all runtime
   `UnimplementedFeatureError`/`NotCanonicalException` cases as skips. Registered-but-unimplemented
   builtins fail with UFE; unknown names fail with `PyNameError`.
7. There is no active Maven step that extracts the downloaded CPython archive and regenerates
   committed goldens; the relevant POM executions are commented out. No SHA-256 or
   cross-platform archive verification is present.
8. JMH benchmarks exist, but there is no isolated baseline whose sole measurement is a plain
   `invokedynamic` call to a generic intrinsic, useful for attributing specialization gains.
9. `ArithmeticIntrinsicsTest.augmentedImmutableTypes` contains a tautological assertion for
    `$imul$("ab", -1L)`; replace it with
    `assertEquals("", ArithmeticIntrinsics.$imul$("ab", -1L))`.
10. Some test suites use Java record equality for tuple assertions. That is acceptable for
    tests, but runtime code must continue to use `ComparisonIntrinsics` / `HashingIntrinsics`.
11. Add an end-to-end test for `float(...)`; the missing specialization method is described in
    D1 below and is not covered by the existing type tests.

## 14. Known deviations, defects and documentation issues

| ID | Status | Current state |
|---|---|---|
| D1 | [DEFECT] | `Builtins.CodeInitialization` registers `float` as a specialized variadic constructor targeting `TypeIntrinsics.$float$`, but `TypeIntrinsics` has no `$float$` method. The first `float(...)` call therefore appears to fail with a specialization-linkage error. Implement the intrinsic and tests, or stop advertising the constructor until supported. |
| D2 | [PARTIAL] | The builtin registry contains names whose implementations do not exist, including `bytearray`, `bytes`, `hash`, `isinstance`, `issubclass`, and many iterator/introspection/IO builtins. They resolve to `UnimplementedFeatureError`; they are placeholders, not supported builtins. |
| D3 | [KNOWN DIVERGENCE] | `IntrinsicSupport.typeName` reports `function` for builtin `PyFunction` values; CPython distinguishes `builtin_function_or_method`. Its Java fallback is `java_type(<class>)`. |
| D4 | [KNOWN DIVERGENCE] | Equality dispatch is left-operand-driven for non-canonical values: for example, `None == unknown` can return false while `unknown == None` raises `NotCanonicalException`. Review cross-type equality asymmetry. |
| D5 | [KNOWN DIVERGENCE] | Very large tuple concatenation/repetition can fail through JVM array/resource errors such as `OutOfMemoryError`, rather than a Python `MemoryError` mapping. |
| D6 | [PARTIAL] | Diagnostics in function bodies are delayed because function compilation and some semantic checks happen on first call. |
| D7 | [NOT IMPLEMENTED] | Java-to-Python normalization is absent; entry points null-check but do not normalize non-canonical Java values. |
| D8 | [PARTIAL] | Function call sites have no global-call fast path or global-value `SwitchPoint` integration. Global variable reads themselves are optimized with switch points. |
| D9 | [KNOWN DIVERGENCE] | `float ** float` uses `StrictMath.pow` after CPython-like special cases; last-bit results can differ from the platform libm used by CPython. |
| D10 | [KNOWN DIVERGENCE] | String `repr` printability follows the JDK's Unicode tables, not a separately pinned CPython Unicode database. |
| D11 | [ACCEPTED OPTIMIZATION] | Zero-capture `def` sites return a shared constant `PyFunction`; this is an accepted exception to CPython function-allocation identity. |
| D12 | [ACCEPTED DIVERGENCE] | NaN hashes to zero rather than using identity-derived CPython hashing. |
| D13 | [PARTIAL] | Slicing a `PyRange` with more than `2**63 - 1` elements raises UFE because slice resolution is currently limited to a signed-long sequence length. |
| D14 | [KNOWN DIVERGENCE] | Non-canonical values can raise `NotCanonicalException`, which is a Java runtime exception separate from both Python exceptions and `UnimplementedFeatureError`. Hashing's old `PyTypeError` fallback has been removed. |
| D15 | [DOC/COMMENT DEFECT] | `Builtins._range_` still has a comment claiming ranges are materialized as lists and that no range type/iterator exists. `PyList` Javadoc says the class implements `java.util.List`, while the class instead exposes an adapter view. Update both comments. |
| D16 | [TEST DEFECT] | The negative string-repeat assertion in `ArithmeticIntrinsicsTest.augmentedImmutableTypes` is tautological and should assert the empty string directly. |

Items from the older plan that are now resolved/obsolete:

- The old hashing-fallback issue stating that `HashingIntrinsics.$hash$` defaults to
  `PyTypeError` is obsolete: the default now throws `NotCanonicalException`.
- The old string-indexing issue is resolved: string indexing/slicing is implemented, and
  `$toIndex$` receives the container name rather than hard-coding `str`.
- The old “range is a list” and “no `PyRange`/`PySlice`/`PyType`” descriptions are obsolete.
- The old missing-iterator/truthiness-test item is obsolete: `PyIteratorTest` and
  `TruthinessIntrinsicsTest` now exist. A dedicated `IteratorIntrinsicsTest` is still absent.

## 15. Work remaining / decisions

1. **Fix `float()` first.** Add `TypeIntrinsics.$float$` (with tested CPython-compatible
   conversions for the intended subset), or remove/disable the registered `float` constructor
   until it exists. Add an end-to-end call test, not only a `PyType` metadata test.
2. **Make builtin availability explicit.** Separate implemented builtins from placeholders,
   and ensure tests assert the intended failure mode for each placeholder. Prioritize or defer
   `hash`, `isinstance`, `issubclass`, `bytearray`, and `bytes` deliberately.
3. **Decide the `bytearray` scope.** Either implement a dedicated mutable bytearray representation
   and its operations, or keep it explicitly unsupported. Do not describe it as a list-backed
   implementation while no such runtime value exists.
4. **Unify non-canonical/runtime failure policy.** Decide when to use a Python exception,
   `UnimplementedFeatureError`, or `NotCanonicalException`; map size/allocation failures to a
   documented policy, including tuple allocation failures.
5. **Complete sequence edge cases.** Decide whether range slicing must support lengths beyond
   the signed-long resolution limit, and add boundary tests for all sliceable types.
6. **Choose the object model scope.** If container/string methods, attributes, descriptors,
   or user-defined classes are in scope, implement attribute/method dispatch and the needed
   runtime model. Otherwise keep those constructs explicitly unsupported.
7. **Consider Java interop normalization** if `Engine`/runtime values are intended to accept
   arbitrary Java callers. Define the canonical accepted Java types and conversion rules first.
8. **Decide whether eager function validation is required.** If rejected source must fail before
   any program output, add a preflight phase that validates all function bodies before execution.
9. **Strengthen intrinsic verification.** Add generic-versus-specialized equivalence tests for
   all intrinsics; extend `IntrinsicShapeTest` to item/range/type operations; retain explicit
   checks for tuple concat/repeat; add a dedicated iterator-intrinsic test if useful.
10. **Improve runtime edge-case coverage.** Add repr-cycle tests for lists/dicts, source-level
    range/slice/type edge cases, builtin placeholder tests, and friendly JVM method/slot-limit
    diagnostics. Fix the tautological arithmetic test assertion.
11. **Make conformance reproducible.** Wire `ConformanceTestIT` into Maven Failsafe (or rename
    and intentionally run it under Surefire), invoke a pinned CPython build to regenerate
    goldens, checksum the archive, select platform-specific archives, and compare exception
    outcomes as well as stdout.
12. **Improve performance attribution.** Add a JMH baseline measuring a plain generic
    `invokedynamic` intrinsic call before attributing gains to specialization.
13. **Add a CLI only if it is a project goal.** The current public execution surface is the
    Java `Engine` API; the shaded jar is a JMH runner.
14. **Clean up source documentation.** Correct the stale `PyList` and `range` comments and
    remove/clarify any obsolete comments or helpers left by the earlier list-backed range and
    bytearray implementation paths.
15. **Revisit global-call optimization separately.** Global reads already use switch points;
    any global function-call fast path should be designed and benchmarked as a separate change.
