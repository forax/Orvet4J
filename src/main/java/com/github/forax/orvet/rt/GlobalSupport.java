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
/// Each global name is associated with a GlobalCell stored in the ModuleContext.
/// Only read to that global are op^timized.
///
/// A read call site initially  installs a target guarded by the cell's SwitchPoint.
/// If the cell has a value, the target returns the resolved value as a constant.
/// Otherwise, it raises a NameError.
///
/// When the cell is modified, the SwitchPoint is invalidated, call sites
/// previously linked are lazily re-resolved to the new value.
///
/// After too many fallback invocations, a call site stops attempting
/// constant folding and permanently switches to the generic
/// GlobalCell.get() method.
final class GlobalSupport {
  public GlobalSupport() {
    throw new AssertionError();
  }

  private static final MethodHandle CELL_GET, CELL_SET, NAME_ERROR;
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
    /// This field should be accessed only holding a lock on this
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
        // invalidate switch point if it exists
        var switchPoint = this.switchPoint;
        if (switchPoint != null) {
          SwitchPoint.invalidateAll(new SwitchPoint[] { switchPoint });
          this.switchPoint = null;
        }
        this.value = newValue;  // volatile write
      }
    }

    /// Returns the cell value (can be null) and a new switch point if the value
    /// was never accessed
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

  private static final class GetGlobalCallSite extends MutableCallSite {
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
    private static final int MAX_COUNTER = 2;

    private final MethodHandle fallback;
    private final GlobalCell cell;
    private int counter;

    private GetGlobalCallSite(GlobalCell cell) {
      this.cell = cell;
      super(TYPE);
      var fallback = FALLBACK.bindTo(this);
      this.fallback = fallback;
      setTarget(fallback);
    }

    private Object fallback() {
      // 1. too many optimization? change to volatile read
      var counter = this.counter;
      if (counter >= MAX_COUNTER) {
        setTarget(CELL_GET.bindTo(cell));
        return cell.get();
      }
      this.counter = counter + 1;

      // 2. get the value and its corresponding switch point
      var resolvedCell = cell.resolveCell();
      var value = resolvedCell.value;

      // 3. install the switch point guard
      MethodHandle mh;
      if (value == null) {
        mh = NAME_ERROR.bindTo(cell.name);
      } else {
        mh = MethodHandles.constant(Object.class, value);
      }
      var target = resolvedCell.switchPoint().guardWithTest(mh, fallback);
      setTarget(target);

      // 4. return the value
      if (value == null) {
        throw (RuntimeException) GlobalCell.nameError(cell.name);
      }
      return value;
    }
  }

  /// A read of a global.
  static CallSite getGlobal(ModuleContext context, String name) {
    var cell = context.cell(name);
    return new GetGlobalCallSite(cell);
  }

  /// A store to a global.
  static CallSite setGlobal(ModuleContext context, String name) {
    var cell = context.cell(name);
    return new ConstantCallSite(CELL_SET.bindTo(cell));
  }
}
