# Orvet4J — Implementation Status and Forward Plan

## 0. Status vocabulary and semantic target

Use these labels consistently:

- **[IMPLEMENTED]** — the source and runtime paths exist and have focused tests.
- **[PARTIAL]** — only a defined subset is supported; its boundaries are stated.
- **[NOT IMPLEMENTED]** — the feature is currently unavailable.
- **[KNOWN DIVERGENCE]** — observable behavior differs from the reference implementation.
- **[DEFECT]** — the implementation or its tests appear internally inconsistent or incorrect.

CPython 3.14 is the semantic reference for the supported subset. Parsing a construct does
not imply that it can be compiled or executed. Unit tests cover selected runtime and compiler
behavior; `ConformanceTestIT` runs under Maven Failsafe against the checked-in goldens, while
corpus regeneration requires the separate `update-corpus` profile.

### 0.1 Constant-origin values

Some values created from JVM constants or immutable constant-like values may be shared or
canonicalized. This includes `ConstantDynamic` literals, strings, booleans, `None`, the
empty tuple, and the `PyType` objects.

NaN hashing is another explicit difference: `HashingIntrinsics` derives a NaN hash from the
raw double bit pattern (`Double.doubleToRawLongBits`), while CPython 3.10+ hashes NaN by
object identity. NaN equality remains false; dictionary lookup uses identity-or-equality, so
a NaN key stored as the same boxed object is still found.

### 0.2 Architectural principles

1. Python values are represented by ordinary JVM objects; there is no universal `PyValue`
   hierarchy.
2. Generic runtime intrinsic methods are the readable source of truth. Specialized intrinsic
   bodies are generated from them and should preserve their behavior.
3. `invokedynamic` is used for dynamic operators, comparisons, item operations, iteration,
   global access, function definition, function calls, and method calls.
4. Lists and dictionaries are mutable, owner-thread-confined runtime objects. Tuples, ranges,
   and slices are immutable value objects.
5. Python equality and hashing are implemented by runtime intrinsics, not by substituting Java
   `equals`/`hashCode` for Python semantics.
6. Unsupported syntax is rejected with located syntax/semantic diagnostics where the relevant
   compiler path detects it. Some unsupported AST forms are rejected later by bytecode generation.
7. Runtime failures do not yet follow one uniform policy: Python exception classes,
   `UnimplementedFeatureError`, `NotCanonicalException`, and some raw JVM failures are present.

## 1. Architecture [IMPLEMENTED, with lazy function-body compilation]

```text
Python source
  └─ ANTLR lexer/parser
       └─ ASTBuilder
            └─ AST: Script / Block / Stmt / Expr / Target
                 └─ Engine.compile / BytecodeGenerator
                      └─ generated Script.__main__()
                           └─ RT.defineClass / FunctionClassLoader
                                └─ invokedynamic bootstraps in RT
                                     ├─ global access: GlobalSupport
                                     ├─ function and method calls: FunctionSupport
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
| `rt` | `RT`, `ModuleContext`, `Dictionary`, function/global/method/specialization support, runtime values, iterators and builtins |
| `rt.intrinsic` | `@Intrinsic` entry points and helpers for arithmetic, comparisons, containers, items, iteration, ranges, strings, truthiness and types |
| `rt.error` | Python exception classes and `UnimplementedFeatureError` |
| `perf` | JMH benchmarks and benchmark Python programs |

### 1.2 Compilation and generated code

- `Engine.compile` generates a `Script` class with a static `__main__` method; `Engine.run`
  is the Java API for running source or an AST.
- `RT.defineClass` creates a private `FunctionClassLoader` for a generated class. The loader
  carries its `ModuleContext` and the `Dictionary` mapping function-definition sites to AST nodes.
- Module-level code and the script class are generated eagerly. Function bodies are generated
  on their first call and cached in shared `PyFunction.Code` state.
- Dynamic operations are linked through bootstraps in `RT`; list/dict display construction and
  some tuple construction are emitted as direct JVM operations.
- Function-call sites use a bounded polymorphic inline cache and degrade to an array-based
  megamorphic path. Method-call sites resolve supported receiver methods through intrinsic
  classes and cache the result per receiver class/method signature.
- Return-type specialization exists only for builtins: a builtin `PyFunction` may carry
  an intrinsic class and expected return type so its calls link directly to the intrinsic method.

## 2. Runtime values and representation [IMPLEMENTED / PARTIAL]

| Python concept | Current representation | Status and notes |
|---|---|---|
| `None` | `PyNone.INSTANCE` | [IMPLEMENTED] singleton |
| `bool` | `Boolean` | [IMPLEMENTED] |
| `int` | `Long` when it fits, otherwise `BigInteger` | [IMPLEMENTED] normalized arithmetic values |
| `float` | `Double` | [IMPLEMENTED] arithmetic and a supported `float()` conversion subset |
| `str` | `String` | [PARTIAL] code-point-aware length, iteration, indexing, slicing, conversions and selected methods |
| `tuple` | sealed `PyTuple` (`PyTuple1..4`, `PyTupleN`) | [IMPLEMENTED] core immutable operations, including slicing |
| `list` | final `PyList<E>` | [IMPLEMENTED] core mutable operations and selected Python methods |
| `dict` | final `PyDict<K,V>` | [IMPLEMENTED] core mapping operations and selected Python methods |
| Python function | `PyFunction` / shared `PyFunction.Code` | [IMPLEMENTED] lazy compilation, stored default values, and constant no-capture definition sites |
| `range` | sealed `PyRange`: `PyRangeSmall` or `PyRangeBig` | [PARTIAL] dedicated immutable sequence; see §4.4 |
| `slice` | immutable `PySlice` | [IMPLEMENTED] start/stop/step values resolved against sequence length |
| type object | `PyType` enum | [PARTIAL] built-in type objects only; no user-defined classes or general metaclass model |
| iterators | nested `PyIterator` implementations | [IMPLEMENTED] internal iteration for tuple, string, list, dict and range |

### 2.1 Representation invariants

- `BigInteger` arithmetic results are normalized to `Long` whenever the value fits.
- `PyTuple` uses fixed records for one to four elements and `PyTupleN` for the empty tuple
  and larger tuples. The empty tuple is shared.
- `PyRangeSmall` stores start/stop/step/length as `long`; `PyRangeBig` stores them as
  `BigInteger`. The representation is selected to avoid overflow in span/length arithmetic.
- `PySlice` stores start/stop/step as runtime values; omitted components are represented by
  `PyNone.INSTANCE`.
- Runtime values and container contents reject Java `null`. `null` is reserved for internal
  states such as an unbound global, absent dictionary lookup result, or exhausted iterator.
- `PyDict.get` returns Java `null` for an absent key; stored values cannot be Java `null`.
- `PyList` and `PyDict` record the creating thread and reject access from another thread with
  `PyRuntimeError`. They are not synchronized concurrent collections; mutable instances are
  confined to their owner thread.
- `ModuleContext` global cells are a separate concurrency mechanism: the global map is a
  `ConcurrentHashMap`, and cell values have volatile visibility with synchronized switch-point
  invalidation.

### 2.2 Java interop views

- `PyList` does not implement `java.util.List`; it exposes an `asJavaList()` adapter/view.
- `PyDict` does not implement `java.util.Map`; it exposes an `asJavaMap()` adapter/view.
- `PyTuple` exposes `asJavaList()`.
- Java equality on storage classes is not the Python equality protocol. Python-visible
  equality is routed through `ComparisonIntrinsics`.
- [NOT IMPLEMENTED] There is no general Java-to-Python normalization boundary. Global
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
  capturing `def` site is a factory that captures values for each execution. Default-value
  expressions are evaluated once per `def` execution, after the captured values, and stored in
  `PyFunction.defaults`.

## 4. Collections and sequence operations

### 4.1 Lists [IMPLEMENTED core operations; selected methods]

`PyList` is array-backed, grows by approximately 1.5×, and caps storage at
`Integer.MAX_VALUE - 8`. Oversize list storage raises `UnimplementedFeatureError`.

Python-facing operations include length, integer indexing with negative indices, assignment
and deletion, slicing, slice assignment/deletion, concatenation, repetition, in-place `+=`
and `*=`, membership, equality/ordering, truthiness, and string/repr rendering.

The method-call path currently exposes these list methods through `ListIntrinsics`:

- `append`, `clear`, `copy`, `count`, `extend`, `index` (one to three arguments), `insert`,
  `pop` (zero or one argument), `remove`, `reverse`, and `sort`.

`sort` takes no arguments and orders elements with the Python less-than comparison. The rest of
the standard list method surface is not exposed. The list iterator reads the current list size
at each `next`, so mutations can affect an ongoing iteration.

### 4.2 Dictionaries [IMPLEMENTED core mapping behavior; selected methods]

`PyDict` stores entries in insertion order using parallel key/value/hash arrays and an
open-addressing index. The index uses perturbation probing and tombstones; sparse entry
storage can be compacted.

Implemented mapping operations include length, lookup, assignment, deletion, membership,
equality, truthiness, repr, and key iteration. Replacing the value for an equal key retains the
original key object and position. Deleting and reinserting appends the key at the end.

Dictionary iteration allows value replacement but detects key-set/size changes and raises
`PyRuntimeError`; after failure, the iterator remains failed. Dicts are unhashable; list/dict
keys are rejected by hashing.

The method-call path currently exposes `clear`, `copy`, `get`, `pop`, `popitem`, `setdefault`,
and `update` through `DictIntrinsics`, and `dict.fromkeys` through the dictionary type object.
`items`, `keys`, `values`, and other standard mapping methods are not exposed. `update` accepts
a dict or an iterable of two-element pairs; the `**kwargs` form is unavailable because method
calls reject keyword arguments.

### 4.3 Tuples [IMPLEMENTED core operations]

Tuple storage and core operations are implemented: size, integer indexing, slicing,
concatenation, repetition, equality, hashing, ordering, membership, iteration, and repr.
Tuple displays are accepted and compiled. Tuple unpacking is not implemented.

Tuple operations may reuse immutable tuple objects (empty tuple, concatenation with an empty
operand, and repetition by one). Very large tuple concatenation/repetition is still subject to
JVM array/resource limits; allocation failure is not consistently translated to a Python
`MemoryError`-like exception.

### 4.4 Ranges [PARTIAL: dedicated runtime type]

`range(...)` creates an immutable `PyRange`. `PyRangeSmall` and `PyRangeBig`
represent ranges whose arithmetic fits in `long` or requires `BigInteger`.
`RangeIntrinsics`provides:

- construction for one, two, or three integer-like (`bool`/integer) arguments and zero-step
  validation;
- length (raising `PyOverflowError` when it does not fit the supported signed sequence-size
  range), emptiness, indexing, membership, sequence equality and hashing;
- lazy iteration through dedicated small-range and big-range iterators;
- slicing that returns another range for supported lengths.

Known limitations: slicing a range whose length exceeds `2**63 - 1` raises
`UnimplementedFeatureError`. Construction currently accepts canonical integer/bool
representations rather than a general Python `__index__` protocol. The existence of `PyRange`
does not imply full CPython range behavior in every boundary case.

### 4.5 Slices [IMPLEMENTED for supported sequence operations]

Slice syntax is represented in the parser/AST and bytecode generator as a `PySlice`.
`ItemIntrinsics` uses it for string, list, tuple, and range subscripting. List slice assignment
and deletion are also implemented. Slice-bound normalization handles omitted bounds, negative
bounds, positive/negative steps, and zero-step `PyValueError`. Very large range slicing retains
the limitation in §4.4.

### 4.6 Hashing [IMPLEMENTED for canonical values]

- Lists and dictionaries raise `PyTypeError` as unhashable types.
- Equal numeric values across bool/int/float representations have compatible hashes.
- Integer and float hashes follow the 64-bit CPython algorithm for supported values.
- Range hashing uses a canonical tuple derived from the sequence; slice hashing uses its
  `(start, stop, step)` tuple representation.
- `None`, functions, and `PyType` objects hash by identity.
- String and tuple hash values are implementation details; tests should not
  assert exact values unless specifically testing the algorithm.
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
| `HashingIntrinsics` | hashes for supported canonical values and explicit NaN policy |
| `TupleIntrinsics` | tuple factories, size/get/concat/repeat/membership/slicing |
| `ListIntrinsics` | list construction, size/get/set/delete/concat/repeat/in-place operations/membership/slicing |
| `DictIntrinsics` | dict construction, size/get/set/delete/membership/equality and selected dict methods |
| `ItemIntrinsics` | `len`, generic get/set/delete item, index conversion; string/range/list/tuple/dict subscripting |
| `RangeIntrinsics` | range construction, size/empty/get/membership/slicing/iteration/equality support |
| `TypeIntrinsics` | `type`, `bool`, `int`, and `float` conversions |
| `ContainmentIntrinsics` | generic containment dispatch |
| `TruthinessIntrinsics` | truthiness and logical not |
| `IteratorIntrinsics` | iterator creation and next-element dispatch |
| `StringIntrinsics` | `str`/`repr` conversion, numeric formatting, and the string methods of §5.4, with Latin-1 fast paths |

`len` is linked to `ItemIntrinsics.$len$`; `type`, `bool`, `int`, and `float` use
`TypeIntrinsics`. `IntrinsicSupport` holds shared non-intrinsic helpers (canonicality checks,
value normalization, type names) that the specializer never rewrites.

### 5.2 Augmented assignment [IMPLEMENTED]

Arithmetic/bitwise operators have augmented-assignment intrinsic paths. Most immutable-value
paths delegate to the corresponding binary operation. List `+=` and `*=` mutate the existing
list and return it.

- Name target: load, evaluate right operand, apply augmented intrinsic, store.
- Subscript target: evaluate container and index once, read old item, evaluate right operand,
  apply operation, then write the result back.

### 5.3 Builtin registry and current implementation [PARTIAL]

`Builtins.CodeInitialization` declares constructor/type functions and a registry of builtin
names. `Builtins.BUILTINS` is a shared mutable `HashMap`; `ModuleContext` initializes each new
global cell with the builtin of the same name when one exists. Assigning a module global
shadows that initial builtin value. There is no separate per-read builtin fallback.

The implemented builtin operations currently include:

- plain functions: `abs`, `all`, `any`, `ascii`, `bin`, `callable`, `chr`, `divmod`, `hash`,
  `hex`, `id`, `len`, `max`, `min`, `oct`, `ord`, `pow`, `print`, `repr`, `round`, and `sum`;
- type objects usable as constructors: `bool`, `dict`, `float`, `int`, `list`, `range`,
  `slice`, `str`, `tuple`, and `type`.

This list describes implemented entry points, not full CPython argument/keyword semantics:

- Builtin resolution is lazy: a builtin `PyFunction` looks up its implementation (a `$name$`
  intrinsic or a `_<name>_` static helper) on first call, so a registered but unimplemented
  builtin fails with `UnimplementedFeatureError` only when called. `hash`, `len`, `repr`, `str`,
  `type`, `bool`, `int`, and `float` are specialized to their intrinsic entry points.
- `float` is implemented by `TypeIntrinsics.$float$` for zero or one argument. It handles
  booleans, integers, doubles and strings, including selected Unicode decimal digits and
  special values; unsupported input types and excessive arity raise Python exceptions.
- `int` supports zero, one, or two arguments; the two-argument form `int(string, base)`
  implements base 0 and bases 2–36, including prefix, underscore and Unicode-digit handling.
  `bool` supports zero or one argument. `str` takes exactly one positional argument; the
  `list`, `tuple`, and `dict` constructors accept zero or one argument. These constructors
  have limited argument handling, not the complete CPython constructor surface.
- `range` returns `PyRange`; `slice` returns `PySlice`.
- `print(*args)` converts values with `$str$`, joins with one space, writes an explicit newline,
  and returns `None`.
- `id` is only an approximation: the current implementation combines Java identity hash values
  and does not promise CPython's object-address/uniqueness semantics.
- User-defined functions support keyword arguments, default values and a trailing `**kwargs`
  parameter. Builtin keyword support is not generally implemented: fixed-arity builtins reject
  keywords with `PyTypeError`; variadic builtins reject keyword calls with
  `UnimplementedFeatureError`.

The registry also contains names for builtins that are not implemented. Explicit placeholder
entries include the iterator/introspection/IO builtins (`aiter`, `anext`, `enumerate`, `filter`,
`iter`, `map`, `next`, `reversed`, `zip`, `delattr`, `dir`, `getattr`, `globals`, `hasattr`,
`locals`, `setattr`, `vars`, `breakpoint`, `compile`, `eval`, `exec`, `format`, `help`,
`input`, `open`, `__import__`) and type/value placeholders (`bytearray`, `bytes`, `complex`,
`frozenset`, `frozendict`, `memoryview`, `object`, `set`, `classmethod`, `property`,
`staticmethod`, `super`). `isinstance` and `issubclass` are registered with the implemented
builtins but have no implementation yet, so they fail the same way. An unknown name still
raises `PyNameError`.

### 5.4 Method calls and attribute access [PARTIAL]

There is no general Python object attribute model, descriptor protocol, or arbitrary attribute
lookup/assignment. However, `obj.name(args)` is no longer universally rejected: the AST,
bytecode generator, `RT.bsmMethodCall`, and `MethodSupport.MethodCallSite` implement a
narrow intrinsic-backed method dispatch path.

Current receiver/method coverage is:

- `str`: `lower`, `upper`, `swapcase`, `capitalize`, `title`, `casefold`, `find`, `rfind`,
  `index`, `rindex`, `partition`, `rpartition`, `split`, `rsplit`, `splitlines`, `join`,
  `strip`, `lstrip`, `rstrip`, `replace`, `count`, `startswith`, `endswith`, `removeprefix`,
  `removesuffix`, `zfill`, `isascii`, `isalpha`, `isdecimal`, `isdigit`, `isalnum`, `isspace`,
  `islower`, and `isupper`.
- `list`: `append`, `clear`, `copy`, `count`, `extend`, `index`, `insert`, `pop`, `remove`,
  `reverse`, and `sort`.
- `dict`: `clear`, `copy`, `get`, `pop`, `popitem`, `setdefault`, and `update`.
- The dictionary type object supports `fromkeys`.

Method lookup resolves a `_<name>_` static helper in the receiver's intrinsic class, trying the
exact signature first and a varargs fallback second; the result is cached per receiver intrinsic
class and method signature (failed lookups are not cached). `lower` implements CPython's
Final_Sigma casing rule, and `startswith`/`endswith` accept a tuple of prefixes/suffixes like
CPython. Method calls with keyword arguments are rejected at the bootstrap with
`UnimplementedFeatureError`. Ordinary attribute reads/writes and the rest of each type's method
surface remain unsupported.

## 6. Language subset

### 6.1 Parsed and generated constructs

| Construct | Front end / AST | Bytecode/runtime status |
|---|---|---|
| Expression statements, `pass`, `return`, `break`, `continue` | accepted | [IMPLEMENTED] |
| Name assignment and chained assignment | accepted | [IMPLEMENTED] |
| Subscript assignment and deletion | accepted | [IMPLEMENTED] for supported containers |
| Augmented assignment to names/subscripts | accepted | [IMPLEMENTED] |
| `if` / `elif` / `else` | accepted | [IMPLEMENTED] |
| `while` and `for`, including loop `else` | accepted | [IMPLEMENTED]; `for` supports string, tuple, list, dict and range iterables |
| `def`, nested defs, closures, positional args (defaults, one `*rest` and one `**kwargs)` | accepted | [IMPLEMENTED] |
| Calls with positional arguments | accepted | [IMPLEMENTED] |
| Calls with keyword arguments | accepted | [IMPLEMENTED] for user functions, `**` call-site unpacking is rejected; builtin limits are in §5.3 |
| Arithmetic, bitwise, comparisons, comparison chains | accepted | [IMPLEMENTED] for supported value types |
| `and`, `or`, `not`, conditional expressions | accepted | [IMPLEMENTED] |
| `in` / `not in` | accepted | [IMPLEMENTED] for supported strings, tuples, lists, dicts and ranges |
| List and dictionary displays | accepted | [IMPLEMENTED] |
| Tuple displays | accepted | [IMPLEMENTED] |
| Subscript loads `a[i]` | accepted | [IMPLEMENTED] for list/dict/tuple/range/string |
| Slice expressions and `a[start:stop:step]` | accepted | [IMPLEMENTED] as described in §4.5 |
| List slice assignment/deletion | accepted | [IMPLEMENTED] |
| `obj.name(args)` | parsed as `Expr.MethodCall` | [PARTIAL] only the intrinsic-backed methods listed in §5.4; no keyword arguments |
| Tuple-unpacking assignment / `for` target | parsed into tuple target | [NOT IMPLEMENTED] rejected by code generation |
| General attribute reads/writes | rejected by front end | [NOT IMPLEMENTED] |
| `%` string formatting | parsed as `%` binary operator | [IMPLEMENTED] printf-style: `s r a c d i u f F e E g G x X o %%`, flags `-+ 0#`, width, precision, `*` width/precision and `%(key)s` mapping keys restricted to `PyDict` |

### 6.2 Front-end rejections

The AST builder rejects unsupported syntax with source location. Current unsupported categories
include:

- `is` / `is not`;
- set displays; list/dict/set comprehensions and generator expressions;
- `lambda`, assignment expressions (`:=`), star expressions/unpacking, `**` unpacking in calls
  and `{**...}` in dict displays;
- duplicate keyword names, named keyword-only parameters (including bare `*`),
  positional-only `/`, annotations, type parameters/type aliases and decorators;
- classes, `with`, `try`, `raise`, `assert`, imports, `global`, `nonlocal`, `match`;
- async/await/yield constructs;
- general attribute access and assignment (method-call syntax is a separate supported AST form
  with the restricted dispatch described in §5.4);
- `del name`, attribute deletion, matrix multiplication, ellipsis, f-strings/t-strings, bytes,
  imaginary literals, unsupported escapes and lone-surrogate escapes.

Some limitations are represented in the AST and rejected later by bytecode generation rather
than by `ASTBuilder`.

### 6.3 Strings [PARTIAL]

Implemented: concatenation, repetition, equality, ordering by Unicode code point, containment,
iteration by code point, length by code point, indexing, slicing, `str`/`repr` conversion,
integer/float formatting, and the string methods listed in §5.4. String item access probes the
string encoding (`isLatin1`): Latin-1 strings index in constant time, and `strip`, `count`, `startswith`, `endswith`, `replace`, and `capitalize` have Latin-1
fast paths; non-Latin-1 indexing walks code points. String slicing has a dedicated `$slice$`
implementation that returns the original string for full-range slices and uses `substring` for
contiguous slices. `lower` implements CPython's Final_Sigma rule instead of the JDK default
casing. `%` formatting and the remaining string methods are not implemented. The `repr`
printability decision uses JDK Unicode tables rather than a separately pinned CPython Unicode
database.

## 7. Functions, calls, globals and scopes

### 7.1 Function representation and calling convention [IMPLEMENTED]

User function entry point:

```text
static Object body(Object capture0..captureN, PyFunction self,
                   Object arg0..argM [, PyTuple rest] [, PyDict kwargs])
```

Builtin entry point:

```text
static Object builtin(PyFunction self, Object fixedArgs..., Object[] rest)
```

- Captures are leading parameters, followed by `self` and explicit parameters (defaulted
  parameters included).
- A function's own recursive name is resolved through `self`, not captured.
- `*rest` in user code is packed into `PyTuple`; a trailing `**kwargs` is collected into a fresh
  `PyDict` per call; builtin variadic arguments use `Object[]`.
- Default values are not body parameters: they are evaluated once per `def` execution, stored in
  `PyFunction.defaults`, and bound by the call-site adapters when a call omits them.
- A body is compiled lazily on first call. The compiled entry and adapters are cached in shared
  `PyFunction.Code` state.

### 7.2 Function call sites [IMPLEMENTED]

- Calls use `invokedynamic` and `RT.bsmCall`.
- `FunctionCallSite` keeps a bounded polymorphic guard chain with `MAX_DEPTH = 4`; after that
  it switches to a megamorphic array-based path.
- Every callee starts on a function-identity guard. A site is promoted to a shared-`Code` guard
  that reads captures and defaults from the callee when a second function object with the same
  `Code` reaches it, or when the capture count is too large for method-handle adaptation.
- The call site validates arity, adapts `*rest`, reorders keyword arguments, and fills defaults
  for user-defined functions; keyword-rest calls receive a fresh `PyDict` built by
  `KeywordRestAdapter`.
- Identity-guarded sites embed the constant defaults of their callee; `Code`-guarded sites read
  defaults dynamically from `self`, so calls with defaults do not force the megamorphic path.
- Arguments are evaluated before the arity check. Failed calls and exceptions do not
  permanently poison a site.
- `PyFunction.call(Object...)` uses the megamorphic path.
- `PyFunction.createDirectMethodHandle(MethodType)` validates arity, binds captures and self,
  adapts rest arguments and returns a direct handle.
- There is no specialized global-call fast path or integration of global-value switch points
  into `FunctionCallSite`.

### 7.3 Method-call sites [PARTIAL]

`RT.bsmMethodCall` creates a `MethodSupport.MethodCallSite` (dispatch lives in its own
`MethodSupport` class). The site resolves a method name to a `_<name>_` static helper in the
receiver's intrinsic class for supported receiver families, caches successful resolutions by
receiver intrinsic class and method signature, and installs guarded call-site targets. Missing
methods raise `PyAttributeError`; failed lookups are not cached. Keyword arguments are rejected
before method lookup. This is a small method-dispatch mechanism, not a general
descriptor/bound-method implementation.

### 7.4 Definition identity [IMPLEMENTED]

Each `def` has shared `PyFunction.Code`. It uses a factory and creates a new function
object with the captured values and evaluated default values on each execution.

### 7.5 Globals [IMPLEMENTED]

A `ModuleContext` owns global cells in a `ConcurrentHashMap`. A cell starts with the builtin
value for that name, if one exists, or with an unbound (`null`) value. A module assignment
updates the cell and shadows the builtin.

`GlobalSupport` optimizes reads with a `SwitchPoint`-guarded constant value. Rebinding
invalidates the switch point; after two invalidations, the site falls back to a volatile cell
read. Stores update the global cell and invalidate an existing switch point. This optimization
applies to global reads, not to function-call-site dispatch.

### 7.6 Local variables and closures [IMPLEMENTED]

`ScopeAnalyzer` performs four passes over an AST-identity-keyed function-scope tree:

1. collect locals, parameters, assignments and child scopes;
2. resolve free variables to captures or module globals;
3. enforce effectively-final capture rules;
4. check definite assignment over control flow.

Assignments bind a name for the whole function (no block scoping). Captures propagate through
intermediate functions. Captured locals and captured parameters cannot be reassigned; captured
writes in loops are rejected. Mutual recursion between nested functions is rejected; self
recursion works through `self`. Captured object mutation is allowed.

`rest` and `keywordRest` parameters behave like ordinary parameters: a `**kwargs` name is a
local assigned at entry, and a captured `**kwargs` cannot be reassigned. Default expressions
are resolved and definite-assignment-checked in the enclosing function's scope, so a default
can read enclosing locals and parameters.

### 7.7 Compilation and diagnostic timing [PARTIAL]

- The script class and module-level code are generated eagerly.
- Function bodies are generated on first call; nested scopes are registered when their enclosing
  function is generated.
- Module-level scope analysis resolves the first three passes when needed; definite assignment
  and bytecode generation happen at first call of a function body.
- Diagnostics in never-called function bodies may never be reported, and diagnostics in called
  functions may appear after the script has already produced output.

An eager source-validation/preflight phase is not implemented.

## 8. Runtime exceptions and unsupported runtime operations [PARTIAL]

Implemented Python exception classes include `PyArithmeticError`, `PyOverflowError`,
`PyZeroDivisionError`, `PyLookupError`, `PyIndexError`, `PyKeyError`, `PyAttributeError`,
`PyNameError`, `PyRuntimeError`, `PyTypeError`, and `PyValueError` (not every class is currently
raised by supported source constructs). They form a sealed hierarchy rooted at
`PyBaseException` (`PyException` groups the core errors; `PyArithmeticError` permits the
overflow/zero-division errors and `PyLookupError` permits the index/key errors), but only the
leaf classes are thrown by runtime code.

`UnimplementedFeatureError` is a Java-side signal for valid operations the runtime does not
implement. Examples include `%` formatting, missing builtin implementations, selected huge
operations and some range-slicing sizes. `NotCanonicalException` is another Java-side signal
used when non-canonical Java values reach operations such as hashing, comparison, type lookup,
or conversion. Some allocation/resource failures can still escape as raw JVM errors.

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
  `PyOverflowError`; right shifts with very large positive counts are handled directly by sign
  extension (`-1` for negative values, `0` otherwise). Some large exponents and sequence sizes
  exceed JVM-supported limits and raise UFE, `PyOverflowError`, or (for tuples) `OutOfMemoryError`,
  depending on the path; string repetition distinguishes a Python `PyOverflowError` from the
  JVM array-limit `UnimplementedFeatureError`.
- Decimal integer literal parsing and integer-to-string conversion use the 4300-digit limit;
  conversion rejects values whose bit length can already exceed the limit before materializing
  the decimal string.
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
- rewrites calls to other intrinsic methods into `invokedynamic` linked by `RT.bsmSpecialize`;
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

- Function body parameter slots (captures + `self` + fixed parameters + optional rest +
  optional `**kwargs`) are limited to 253; excess raises `SemanticErrorException`. The def-site
  value count (captures + default values) passed to the definition bootstrap is not validated
  yet.
- A call with more than 252 arguments (callee excluded) is rejected by bytecode generation.
- String constants longer than the class-file modified-UTF-8 constant limit are emitted in
  chunks (`LDC_CHUNK = 20_000` characters).
- Decimal integer literals longer than 4300 digits are rejected by the front end.
- Integer string conversion uses the same 4300-digit CPython default.
- Function generation translates `MethodTooLargeException` into a source-located
  `SemanticErrorException` (`method bytecode limit`). The script `__main__` generation path
  does not currently provide the same translation.
- Local-variable slots are allocated monotonically and never reused; exceeding the JVM slot
  limit is not translated into a friendly source diagnostic.
- Source-dependent diagnostics are not guaranteed to be eager because function bodies are
  compiled lazily (§7.7).

## 12. Build and toolchain [IMPLEMENTED; conformance corpus generation is inactive]

- Maven project/artifact: `com.github.forax.orvet4j:orvet4j:0.1`.
- Maven compiler plugin 3.16.0, `<release>25</release>`.
- ANTLR 4.13.2; ASM and ASM-util 9.11.1; JUnit Jupiter 5.14.3.
- JMH core and annotation processor 1.37 are dependencies.
- Shade plugin 3.6.2; the shaded JAR manifest main class is `com.github.forax.orvet.Main`.
  The launcher accepts a Python script path, `--help`/`-h`, or `--jmh` followed by JMH options.
- Surefire 3.6.1 runs unit tests. Maven Failsafe 3.6.1 is bound to `integration-test`/`verify`
  and runs `ConformanceTestIT`.
- The `update-corpus` profile clones MicroPython `tests/basics` and `tests/float` at `v1.24.1`
  and downloads python-build-standalone CPython 3.14.10 release `20261110`.
- Four OS-activated profiles (`python-linux-x86_64`, `python-linux-aarch64`,
  `python-macos-x86_64`, `python-macos-aarch64`) select the CPython archive; the base property
  is macOS aarch64.
- The profile's download, extraction, and golden-file generation executions are active: the
  pinned CPython build runs `generate_golden.py` to produce the committed `.golden` files.
- No SHA-256 verification of the downloaded CPython archive is configured.
- The project exposes both the Java `Engine` API and a small command-line launcher. The JAR
  entry point is Orvet's `Main`; JMH is selected through `--jmh`. Failures print `orvet:
  <message>` to stderr and exit with status 1.

## 13. Tests and verification

### 13.1 Existing test classes

- AST/compiler: `ASTBuilderTest`, `ScopeAnalyzerTest`, `BytecodeGeneratorTest`,
  `ConformanceTestIT`, `SpecializerTest`, `SpecializerFixtures`.
- Runtime/functions/globals: `FunctionSupportTest`, `GlobalCallTest`, `MethodSupportTest`,
  `PyFunctionTest`, `BuiltinsTest`, `GlobalSwitchPointTest`, `RTTest`.
- Values/storage: `PyListTest`, `PyDictTest`, `PyTupleTest`, `PyNoneTest`, `PyIteratorTest`,
  `PyRangeTest`, `PySliceTest`, `PyTypeTest`.
- Intrinsics: `ArithmeticIntrinsicsTest`, `ComparisonIntrinsicsTest`,
  `HashingIntrinsicsTest`, `StringIntrinsicsTest`, `TupleIntrinsicsTest`,
  `ListIntrinsicsTest`, `DictIntrinsicsTest`, `ItemIntrinsicsTest`,
  `ContainmentIntrinsicsTest`, `RangeIntrinsicsTest`, `TypeIntrinsicsTest`,
  `TruthinessIntrinsicsTest`, `IntrinsicShapeTest`.

There is no dedicated `IteratorIntrinsicsTest`; iterator behavior is covered by
`PyIteratorTest` and `IntrinsicShapeTest`.

### 13.2 Notable coverage already present

- `BuiltinsTest` covers the implemented builtin families, including range/slice/type and
  float conversions; it does not imply that every registered builtin name is implemented.
- `TypeIntrinsicsTest` directly exercises `float()` conversions and error cases, `int`
  (including the two-argument `int(string, base)` form), `bool`, and `type` behavior.
- `PyRangeTest` and `RangeIntrinsicsTest` cover range representation boundaries, construction,
  length/indexing, membership, equality and slicing, including large integer cases.
- `PySliceTest` covers slice value behavior; `ItemIntrinsicsTest` covers sequence item operations.
- `PyIteratorTest` covers iterator storage/behavior; `IntrinsicShapeTest` checks iterator and
  truthiness intrinsic bytecode shapes.
- `GlobalCallTest` covers direct/recursive/builtin calls, keyword calls to user functions,
  closures assigned to globals, global-function rebinding, undefined/non-callable globals and
  local shadowing.
- `FunctionSupportTest` covers polymorphic/megamorphic calls, closures, rest arguments,
  keyword arguments for user functions, builtin keyword errors, rebinding,
  evaluation-before-arity-check, and concurrent call/compile behavior.
- `GlobalSwitchPointTest` checks global rebinding, invalidation and builtin shadowing.
- `MethodSupportTest` covers method-call bootstrap linking, arity-based overload selection,
  call-site sharing across receiver classes, identity-guarded `PyType` receivers
  (`dict.fromkeys`), missing-method/unsupported-receiver `PyAttributeError`s, and
  keyword-argument rejection.
- `IntrinsicShapeTest` checks representative switch shapes for hashing, tuple, list,
  containment, iterator, truthiness, comparison, arithmetic and string intrinsics.
- JMH benchmark classes exist: `IsLatin1Benchmark`, `LookupBenchmark`,
  `RecursiveFunctionBenchmark`, and `SumLoopBenchmark`.

### 13.3 Remaining verification gaps

1. `IntrinsicShapeTest` does not cover `ItemIntrinsics`, `RangeIntrinsics`, or `TypeIntrinsics`.
   Its tuple concat/repeat positive-switch assertions remain commented out.
2. Generic-versus-specialized equivalence is not systematically tested for every intrinsic.
   Add cross-checks for all intrinsic methods, especially item/range/type operations.
3. List/dict recursive-cycle repr does not have a dedicated test.
4. `MethodSupportTest` now covers the dispatch basics: linking, arity-based overload selection,
   receiver guards (class and identity), missing-method errors, and keyword-argument rejection.
   Remaining: negative-lookup (missing-method) caching, cache concurrency, the varargs-signature
   fallback path, and per-method edge cases for newly exposed methods.
5. No test verifies the lazy diagnostic timing policy or friendly handling of JVM local-slot
   limits. Add a script-level method-too-large test as well as the existing function diagnostic
   coverage.
6. `ConformanceTestIT` runs under the configured Maven Failsafe plugin, but it reads
   pre-existing `.golden` files and compares stdout only; it does not launch pinned CPython via
   `ProcessBuilder` or compare uncaught exception type/message. From a fresh checkout only the
   14 committed `beginner` pairs run; the `micropython/basics` and `micropython/float` folders
   it references are produced by the `update-corpus` profile.
7. The conformance runner filters syntax/unsupported-syntax failures during test-pair creation
   and catches `UnsupportedSyntaxException` / `UnimplementedFeatureError` during execution, but
   does not classify all `NotCanonicalException` cases as skips. Registered-but-unimplemented
   builtins fail with UFE; unknown names fail with `PyNameError`.
8. The `update-corpus` profile now downloads the CPython archive, extracts it, and regenerates
   the goldens with the pinned build (§12). No SHA-256 or cross-platform archive verification is
   present.
9. JMH benchmarks exist, but there is no isolated baseline whose sole measurement is a plain
   `invokedynamic` call to a generic intrinsic, useful for attributing specialization gains.
10. Some tests use Java record/storage equality for tuple assertions. That is acceptable in
    tests, but runtime code must continue to use `ComparisonIntrinsics` / `HashingIntrinsics`.
11. Add targeted tests for `id()` semantics or document its approximation as an explicit
    divergence; its current implementation is not a CPython-compatible object identity value.

## 14. Known deviations, defects and documentation issues

| ID | Status | Current state |
|---|---|---|
| D1 | [KNOWN DIVERGENCE] | `id()` exists but synthesizes a value from Java identity hash codes. It does not guarantee CPython `id()` semantics or uniqueness. |
| D2 | [PARTIAL] | The builtin registry contains placeholders whose implementations do not exist (iterator/introspection/IO builtins, `bytes`/`bytearray`, sets, and more). `isinstance` and `issubclass` are registered with the implemented builtins but have no implementation, resolve to `UnimplementedFeatureError`; they are not supported builtins. |
| D3 | [KNOWN DIVERGENCE] | Equality dispatch is left-operand-driven for non-canonical values: for example, `None == unknown` can return false while `unknown == None` raises `NotCanonicalException`. Review cross-type equality asymmetry. |
| D4 | [KNOWN DIVERGENCE] | Very large tuple concatenation/repetition can fail through JVM array/resource errors such as `OutOfMemoryError`, rather than a Python `MemoryError` mapping. |
| D5 | [KNOWN DIVERGENCE] | Diagnostics in function bodies are delayed because function compilation and some semantic checks happen on first call. |
| D6 | [NOT IMPLEMENTED] | Java-to-Python normalization is absent; entry points null-check but do not normalize non-canonical Java values. |
| D7 | [KNOWN DIVERGENCE] | `float ** float` uses `StrictMath.pow` after CPython-like special cases; last-bit results can differ from the platform libm used by CPython. |
| D8 | [KNOWN DIVERGENCE] | String `repr` printability follows the JDK's Unicode tables, not a separately pinned CPython Unicode database. |
| D9 | [PARTIAL] | Slicing a `PyRange` with more than `2**63 - 1` elements raises UFE because slice resolution is currently limited to a signed-long sequence length. |
| D10 | [KNOWN DIVERGENCE] | Non-canonical values can raise `NotCanonicalException`, a Java runtime exception separate from both Python exceptions and `UnimplementedFeatureError`. |
| D11 | [KNOWN DIVERGENCE] | `hash(nan)` is derived from the raw double bit pattern; CPython 3.10+ hashes NaN by object identity, so hash stability across NaN objects differs. NaN equality remains false and dict lookup stays identity-or-equality. |

## 15. Work remaining / decisions

Prioritize correctness and testability before adding a large new language surface:

1. **Make builtin availability explicit.** Keep implemented builtins distinct from placeholders
   in documentation and tests (`hash` is now implemented; `isinstance`/`issubclass` are
   registered as implemented without implementations). Decide whether to implement
   `isinstance`, `issubclass`, `bytearray`, `bytes`, and iterator/introspection builtins, or
   leave them explicitly unsupported.
2. **Harden method dispatch.** `MethodSupportTest` covers the dispatch basics; extend it with
   negative-lookup caching, cache concurrency, and the varargs-signature fallback path, and keep
   per-method edge cases covered as methods are added. Decide whether this small intrinsic-backed
   mechanism is the intended long-term scope or the first step toward a general
   attribute/descriptor model.
3. **Unify runtime failure policy.** Decide when to use a Python exception, `UnimplementedFeatureError`,
   or `NotCanonicalException`; map size/allocation failures to a documented policy, including
   tuple allocation failures.
4. **Complete sequence edge cases.** Decide whether range slicing must support lengths beyond
   the signed-long resolution limit, and add boundary tests for all sliceable types.
5. **Choose the object-model scope.** Decide explicitly whether user-defined classes, general
   attribute access, descriptors, and full container/string method surfaces are project goals.
   Until then, keep unsupported constructs explicitly documented.
6. **Consider Java interop normalization** if `Engine`/runtime values are intended to accept
   arbitrary Java callers. Define the canonical accepted Java types and conversion rules first.
7. **Decide whether eager function validation is required.** If rejected source must fail before
   any program output, add a preflight phase that validates all function bodies before execution.
8. **Strengthen intrinsic verification.** Add generic-versus-specialized equivalence tests for all
   intrinsics; extend `IntrinsicShapeTest` to item/range/type operations and restore explicit
   checks for tuple concat/repeat where the expected shape is stable.
9. **Improve runtime edge-case coverage.** Add repr-cycle tests for lists/dicts, source-level
   range/slice/type edge cases, placeholder-builtin tests, and friendly JVM local-slot /
   script-method-limit diagnostics. Fix the tautological arithmetic assertion.
10. **Make conformance reproducible.** Failsafe wiring, platform-specific archives, and
    pinned-CPython golden generation are done; remaining work is checksumming the downloaded
    archive and comparing exception outcomes (not only stdout) in the conformance test itself.
11. **Improve performance attribution.** Add a JMH baseline measuring a plain generic
    `invokedynamic` intrinsic call before attributing gains to specialization.
12. **Revisit global-call optimization separately.** Global reads already use switch points;
    any global function-call fast path should be designed and benchmarked as a separate change.
13. **Clean up source and user documentation.** The stale `PyList`/`range` comments are fixed;
    remaining: README's builtin/method lists and its claim that defaults, keyword-only arguments
    and `**kwargs` are unsupported, the `orvet-0.1.jar` name in README examples, the
    `ConformanceTestIT` header comment about Failsafe, and the `BytecodeGenerator` class comment
    that omits the `[, PyDict kwargs]` body parameter.
14. **Decide the `id()` contract.** Either implement a stable identity abstraction with explicit
    lifetime/uniqueness guarantees, or document `id()` as a deliberately approximate builtin.
15. **Continue the parameter-surface work.** Defaults and a trailing `**kwargs` are implemented;
    named keyword-only parameters (bare `*`), positional-only `/`, and `**` call-site unpacking
    are still rejected. Decide whether to extend the kwargs work in that direction, and whether
    method calls should gain keyword support (`MethodSupport` already carries the bootstrap
    plumbing and a varargs-signature fallback prepared for `str.format`).

