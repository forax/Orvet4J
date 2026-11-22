# Orvet4J

Orvet4J (orvet for short) is an experimental implementation of a subset of Python that
runs on the Java Virtual Machine (JVM).

Orvet compiles Python source code into JVM bytecode.

The goal is to support a useful subset of Python while taking advantage of JVM optimizations,
including `invokedynamic` and specialized runtime operations.

A French *orvet* is a legless lizard that is often mistaken for a snake.


## How it works

The execution pipeline is:

```text
Python source
    |
    v
ANTLR lexer and parser
    |
    v
Abstract Syntax Tree
    |
    v
Scope analysis and bytecode generation (ASM)
    |
    v
JVM bytecode
    |
    v
Execution on the JVM
```

Orvet uses JVM objects to represent Python values. Dynamic operations such as arithmetic, comparisons,
global variable access and function calls are linked through `invokedynamic` call sites.
Function bodies are compiled lazily, when they are first called.


## Python language support

Orvet targets the semantics of CPython 3.14 for its supported subset. It is not a complete Python implementation,
and successful parsing does not necessarily mean that a construct is executable.


### Supported features

| Category | Supported functionality |
|---|---|
| Basic statements | Assignments, chained assignments, expression statements, `pass`, `return` |
| Control flow | `if` / `elif` / `else`, `while`, `for`, `break`, `continue`, loop `else` |
| Functions | Function definitions, nested functions, recursion, closures |
| Function arguments | Positional arguments, one `*args` parameter, keyword arguments for user-defined functions |
| Numeric types | Integers, arbitrary-precision integer arithmetic, floating-point values and mixed integer/float arithmetic |
| Operators | Arithmetic, floor division, modulo, exponentiation, bitwise operators, shifts and augmented assignments |
| Comparisons | Equality, ordering, chained comparisons and membership |
| Boolean expressions | `and`, `or`, `not`, conditional expressions and truth-value testing |
| Strings | Literals, concatenation, repetition, comparison, containment, iteration, indexing and slicing |
| Collections | Lists, dictionaries and tuples |
| Sequences | Indexing, slicing, membership, concatenation and repetition where supported |
| Other types | `None`, `range` and `slice` |
| Iteration | Iteration over strings, lists, tuples, dictionaries and ranges |

Some implementation details are worth noting:

- Integers use JVM `long` values when possible and promote to `BigInteger` when necessary.
- Ranges have their own immutable runtime representation and support lazy iteration.
- Lists and dictionaries are mutable; everything else is immutable.
- String indexing, iteration and length operate on Unicode code points.
- Closures are supported but captured local variables cannot be reassigned.
- The runtime is designed for concurrency (no GIL); mutable objects are confined to their owner thread,
  and global bindings have volatile semantics.


### Implemented builtins

The current implementation includes the following builtins:

`abs`, `all`, `any`, `ascii`, `bin`, `bool`, `callable`, `chr`, `dict`, `divmod`, `float`, `hex`,
`id`, `int`, `len`, `list`, `max`, `min`, `oct`, `ord`, `pow`, `print`, `range`, `repr`, `round`,
`slice`, `str`, `sum`, `tuple` and `type`.

This does not mean that every CPython constructor or builtin behavior is fully implemented.

### Not yet supported, but maybe in the future

The following Python features are currently outside the supported subset:

- Classes and user-defined object types.
- String, list and dictionary methods not included in the supported method-call list below.
- Set operations and set literals.
- List, dictionary and set comprehensions.
- Imports and Python module loading.
- `try` / `except` / `finally`, `raise`, `assert` and `with`.
- F-strings, bytes literals and `%` string formatting.
- Tuple unpacking.
- Default arguments, keyword-only arguments and `**kwargs`.
- `is` and `is not` comparisons.

### Supported method calls

Method calls use a limited dispatch path.
The following methods are supported:

- `str`: `capitalize`, `find`, `index`, `isalpha`, `isascii`, `isdecimal`, `join`, `lower`,
  `partition`, `removeprefix`, `removesuffix`, `rfind`, `rindex`, `rpartition`, `swapcase`,
  `upper` and `zfill`.
- `list`: `clear`, `copy`, `count`, `extend`, `index`, `pop`, `remove` and `sort`.
- `dict`: `clear`, `copy`, `fromkeys`, get`, `pop`, `popitem` and `update`.

Methods not listed above and ordinary attribute reads or writes
remain unsupported. Method calls with keyword arguments are also unsupported.

See [implementation-plan.md](implementation-plan.md) for more detailed implementation status,
limitations and known semantic differences.


## Using Orvet

Orvet exposes both a command-line launcher and a Java API.

After building the project, run a Python script with:

```bash
java -jar target/orvet-0.1.jar hello.py
```

Use `--help` (or `-h`) to display the launcher usage.
The launcher also accepts `--jmh` followed by JMH options to run benchmarks, for example:

```bash
java -jar target/orvet-0.1.jar --jmh SumLoopBenchmark
```

A Python script can also be executed using `Engine` and `ModuleContext`:

```java
import com.github.forax.orvet.engine.Engine;
import com.github.forax.orvet.rt.ModuleContext;

public class Example {
  static void main() {
    var context = new ModuleContext();

    Engine.run(context, """
        def loop():
            i = 0
            total = 0
            while i < 10_000:
                total = total + i
                i = i + 1
            return total

        result = loop()
        """);

    IO.println(context.getGlobal("result"));
  }
}
```

Output:

```text
49995000
```

`ModuleContext` exposes module-level variables through `getGlobal` and `setGlobal`,
allowing Java code to exchange values with executed Python scripts.


## Performance: the while-loop benchmark

Orvet includes a JMH benchmark that compares a simple summation loop implemented in several ways.

The Python function calculates the sum of integers from 0 to 9,999 (see above).

### Recorded results

The following results are recorded in `SumLoopBenchmark.java` for Java 28 preview.

Java 28 `--enable preview`
```text
Benchmark                              Mode  Cnt   Score   Error  Units
SumLoopBenchmark.java_boxed_Long       avgt    5   6.516 ± 0.272  us/op
SumLoopBenchmark.java_primitive_long   avgt    5   3.041 ± 0.007  us/op
SumLoopBenchmark.python_int            avgt    5   6.494 ± 0.095  us/op
```

Java 27
```text
Benchmark                              Mode  Cnt    Score   Error  Units
SumLoopBenchmark.java_boxed_Long       avgt    5   20.646 ± 0.226  us/op
SumLoopBenchmark.java_primitive_long   avgt    5    3.039 ± 0.004  us/op
SumLoopBenchmark.python_int            avgt    5   22.833 ± 0.079  us/op
```

Python 3.14.8
```text
Benchmark                              Mode  Cnt    Score   Error  Units
                                       avgt    5  223.183 ± 0.711  us/op
```

The JMH benchmark is [SumLoopBenchmark.java](src/main/java/com/github/forax/orvet/perf/SumLoopBenchmark.java)

And you can run it with
```bash
java -jar target/orvet-0.1.jar --jmh SumLoopBenchmark
```

## Building and testing

Orvet is a Maven project and targets Java 25+.

Run the unit tests:

```bash
mvn test
```

Build the project:

```bash
mvn package
```

## Project status

Orvet is a work in progress. Its supported Python subset is evolving, and semantic compatibility
with CPython is not guaranteed outside the explicitly implemented features.

The project includes tests for parsing, scope analysis, bytecode generation, runtime operations,
builtins and specialized intrinsics.
