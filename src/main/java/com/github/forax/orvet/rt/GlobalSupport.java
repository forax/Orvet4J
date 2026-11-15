package com.github.forax.orvet.rt;

import com.github.forax.orvet.rt.error.PyNameError;

import java.lang.invoke.CallSite;
import java.lang.invoke.ConstantCallSite;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.invoke.MutableCallSite;
import java.lang.invoke.SwitchPoint;
import java.util.Objects;

/// Implements the invokedynamic bootstrap methods for global variable access.
///
/// Each global name of a module is backed by one [GlobalCell], owned by the [ModuleContext].
/// A cell is created on first access. It starts with the builtin of the same name if there is one
/// or is unbound (value `null`) otherwise.
///
/// Only reads are optimized. A store is a plain call to [GlobalCell#set] which
/// invalidates the cell's [SwitchPoint] if it exists.
///
/// Life cycle of a read call site (`()Object`):
///  1. The site is created with the `fallback` as its target.
///  2. On a fallback call, the site takes a (value, SwitchPoint) snapshot of the cell and installs
///     `switchPoint.guardWithTest(constant(value), fallback)`. If the cell is unbound, the guarded
///     target raises `NameError` instead of returning a constant.
///  3. A store invalidates the SwitchPoint, so the guard fails and the next read goes through the
///     fallback again, which re-folds the new value.
///  4. A site folds at most [GetGlobalCallSite#MAX_INVALIDATIONS] times.
///     The next fallback installs definitively a plain volatile read of the cell.
///
final class GlobalSupport {
  private GlobalSupport() {
    throw new AssertionError();
  }

  private static final MethodHandle CELL_SET, NAME_ERROR;
  static final MethodHandle CELL_GET;
  static {
    var lookup = MethodHandles.lookup();
    try {
      CELL_GET = lookup.findVirtual(GlobalCell.class, "get",
          MethodType.methodType(Object.class));
      CELL_SET = lookup.findVirtual(GlobalCell.class, "set",
          MethodType.methodType(void.class, Object.class));
      NAME_ERROR = lookup.findStatic(GlobalCell.class, "nameError",
          MethodType.methodType(Object.class, String.class));
    } catch (NoSuchMethodException | IllegalAccessException e) {
      throw new AssertionError(e);
    }
  }

  /// Mutable storage for a single global variable, shared by all call sites
  /// accessing that variable within a module.
  ///
  /// The value is volatile so that generic reads always observe the current binding.
  /// The SwitchPoint represents the validity of constant values embedded in the call sites.
  ///
  /// Resolving a cell creates a SwitchPoint if necessary and returns a snapshot
  /// of the current value together with that SwitchPoint. Updating a cell
  /// invalidates the existing SwitchPoint before publishing the new value,
  /// ensuring that previously linked call sites cannot continue using their
  /// embedded constants.
  static final class GlobalCell {
    /// The result of asking for the cell value, the switchPoint is never null
    record ResolvedCell(Object value, SwitchPoint switchPoint) {}

    private final String name;
    private volatile Object value;

    /// The validity of constant values embedded in the call sites.
    /// Invalidating a SwitchPoint prevents its guarded target from being selected,
    /// but does not eagerly replace call-site targets, so obsolete values can remain
    /// strongly reachable until the corresponding call sites are invoked and relinked.
    ///
    /// This field should be accessed holding a lock on this
    private SwitchPoint switchPoint;

    GlobalCell(String name, Object value) {
      this.name = name;
      this.value = value;
      super();
    }

    /// Slow path: always correct, whatever the state of the cell.
    public Object get() {
      var value = this.value;  // volatile read
      if (value != null) {
        return value;
      }
      throw (RuntimeException) nameError(name);
    }

    private static Object nameError(String name) {
      throw new PyNameError("name '" + name + "' is not defined");
    }

    /// Set the new value, invalidating the call sites that were previously constant folded
    public void set(Object newValue) {
      Objects.requireNonNull(newValue);
      synchronized (this) {
        if (this.value == newValue) {
          return;   // do nothing
        }
        // invalidate switch point if it exists
        var switchPoint = this.switchPoint;
        if (switchPoint != null) {
          SwitchPoint.invalidateAll(new SwitchPoint[] { switchPoint });
          this.switchPoint = null;
        }
        this.value = newValue;  // volatile write
      }
    }

    /// Returns the tuple (cell value, switch point)
    /// The value can be null, the switch point is never null
    ResolvedCell resolveCell() {
      synchronized (this) {
        var value = this.value;  // volatile read
        var switchPoint = this.switchPoint;
        if (switchPoint == null) {
          this.switchPoint = switchPoint = new SwitchPoint();
        }
        return new ResolvedCell(value, switchPoint);
      }
    }
  }

  /// A mutable call site implementing speculative constant-folding for global variable reads.
  private static final class GetGlobalCallSite extends MutableCallSite {

    // [Unlinked / CallSite Init]
    //          |
    //          v
    //   fallback()  (counter = 0 -> 1)
    //          |
    //          +-- Value bound?   --> Target: SwitchPoint.gwt(MHs.constant(value), fallback)
    //          +-- Value unbound? --> Target: SwitchPoint.gwt(NAME_ERROR.bindTo(name), fallback)
    //          |
    //          v  (Global updated via setGlobal() -> SwitchPoint invalidated)
    //   fallback()  (counter = 1 -> 2)
    //          |
    //          +-- Re-binds to new value / new SwitchPoint (same as above)
    //          |
    //          v  (Global updated again -> SwitchPoint invalidated)
    //   fallback()  (counter >= MAX_COUNTER)
    //          |
    //          +-- Target: CELL_GET.bindTo(cell)  [Megamorphic / Volatile fast-path]

    private static final MethodType TYPE;
    private static final MethodHandle FALLBACK;
    static {
      var lookup = MethodHandles.lookup();
      TYPE = MethodType.methodType(Object.class);
      try {
        FALLBACK = lookup.findVirtual(GetGlobalCallSite.class, "fallback",
            MethodType.methodType(Object.class));
      } catch (NoSuchMethodException | IllegalAccessException e) {
        throw new AssertionError(e);
      }
    }

    /// Maximum number of fallback invocations before giving up constant folding.
    private static final int MAX_INVALIDATIONS = 2;

    private final MethodHandle fallback;
    private final GlobalCell cell;

    /// Approximate number of fallback invocations, used only to decide when to stop
    /// attempting constant folding. Deliberately neither volatile nor atomic,
    /// it's just a perf counter.
    private int counter;

    private GetGlobalCallSite(GlobalCell cell) {
      this.cell = cell;
      super(TYPE);
      var fallback = FALLBACK.bindTo(this);
      this.fallback = fallback;
      setTarget(fallback);
    }

    private Object fallback() {
      // 1. The site was relinked too often? Stop folding and read (volatile) the cell directly
      var counter = this.counter;
      if (counter >= MAX_INVALIDATIONS) {
        setTarget(CELL_GET.bindTo(cell));
        return cell.get();
      }
      this.counter = counter + 1;

      // 2. Get the value and its corresponding switch point
      var resolvedCell = cell.resolveCell();
      var value = resolvedCell.value;

      // 3. Install the switch point guard
      MethodHandle mh;
      if (value == null) {
        mh = NAME_ERROR.bindTo(cell.name);
      } else {
        mh = MethodHandles.constant(Object.class, value);
      }
      var target = resolvedCell.switchPoint().guardWithTest(mh, fallback);
      setTarget(target);

      // 4. Return the value
      if (value == null) {
        throw (RuntimeException) GlobalCell.nameError(cell.name);
      }
      return value;
    }
  }

  /// A read of a global, `()Object`. The cell belongs to the module that loaded the calling class.
  static CallSite getGlobal(ModuleContext context, String name) {
    var cell = context.cell(name);
    return new GetGlobalCallSite(cell);
  }

  /// A store to a global, `(Object)void`. Never relinked: the cell does the invalidation.
  static CallSite setGlobal(ModuleContext context, String name) {
    var cell = context.cell(name);
    return new ConstantCallSite(CELL_SET.bindTo(cell));
  }
}
