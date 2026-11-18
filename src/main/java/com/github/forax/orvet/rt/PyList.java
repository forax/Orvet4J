package com.github.forax.orvet.rt;

import com.github.forax.orvet.rt.error.PyRuntimeError;
import com.github.forax.orvet.rt.error.UnimplementedFeatureError;

import java.util.AbstractList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.RandomAccess;

/// Mutable Python-list storage.
///
/// This class deliberately implement java.util.List to offer better interoperability.
/// Python-visible behavior belongs in ListIntrinsics and the other runtime intrinsics.
/// Elements are canonical runtime values: Java null is never stored (use PyNone.INSTANCE).
public final class PyList<E> {
  private static final int MAX_ARRAY_SIZE = Integer.MAX_VALUE - 8;
  private static final Object[] EMPTY_ARRAY = new Object[0];

  private final Thread owner;
  private E[] elements;
  private int size;

  /// Creates an empty list.
  @SuppressWarnings("unchecked")
  public PyList() {
    this.elements = (E[]) EMPTY_ARRAY;
    this.owner = Thread.currentThread();
    super();
  }

  /// Creates an empty list with the given initial capacity.
  @SuppressWarnings("unchecked")
  public PyList(int initialCapacity) {
    if (initialCapacity < 0) {
      throw new IllegalArgumentException("negative initial capacity: " + initialCapacity);
    }
    if (initialCapacity > MAX_ARRAY_SIZE) {
      throw tooLarge();
    }
    this.elements = (E[]) new Object[initialCapacity];
    this.owner = Thread.currentThread();
    super();
  }

  /// Creates a list by copying the supplied elements.
  @SafeVarargs
  @SuppressWarnings("unchecked")
  public PyList(E... items) {
    Objects.requireNonNull(items);
    if (items.length > MAX_ARRAY_SIZE) {
      throw tooLarge();
    }
    for (var item : items) {
      Objects.requireNonNull(item);
    }
    this.elements = (E[]) Arrays.copyOf(items, items.length, Object[].class);
    this.size = items.length;
    this.owner = Thread.currentThread();
    super();
  }

  /// Current number of elements.
  public int size() {
    checkOwner();
    return size;
  }

  /// Whether this list is empty.
  public boolean isEmpty() {
    checkOwner();
    return size == 0;
  }

  /// Raw zero-based Java access. Python negative-index handling is implemented by ListIntrinsics.
  public E get(int index) {
    Objects.checkIndex(index, size);
    checkOwner();
    return elements[index];
  }

  /// Raw zero-based Java update. Python negative-index handling is implemented by ListIntrinsics.
  public E set(int index, E value) {
    Objects.checkIndex(index, size);
    Objects.requireNonNull(value);
    checkOwner();
    var oldValue = elements[index];
    elements[index] = value;
    return oldValue;
  }

  /// Appends one canonical runtime value.
  public void append(E value) {
    Objects.requireNonNull(value);
    checkOwner();
    ensureCapacity(size + 1);
    elements[size++] = value;
  }

  /// Appends a snapshot of another list's current contents. Safe when {@code other == this}.
  public void appendAll(PyList<? extends E> other) {
    Objects.requireNonNull(other);
    checkOwner();
    var count = other.size;
    if (count == 0) {
      return;
    }
    var newCapacity = (long) size + count;
    if (newCapacity > MAX_ARRAY_SIZE) {
      throw tooLarge();
    }
    var newSize = (int) newCapacity;
    ensureCapacity(newSize);
    // System.arraycopy has defined overlap behavior, so this also handles self-extension.
    System.arraycopy(other.elements, 0, elements, size, count);
    size = newSize;
  }

  /// Inserts at a raw Java index in [0, size]. Python's clamping rules belong in the method intrinsic.
  public void insert(int index, E value) {
    if (index < 0 || index > size) {
      throw new IndexOutOfBoundsException("index: " + index + ", size: " + size);
    }
    Objects.requireNonNull(value);
    checkOwner();
    ensureCapacity(size + 1);
    System.arraycopy(elements, index, elements, index + 1, size - index);
    elements[index] = value;
    size++;
  }

  /// Removes and returns an element at a raw zero-based Java index.
  public E remove(int index) {
    Objects.checkIndex(index, size);
    checkOwner();
    var removed = elements[index];
    var moved = size - index - 1;
    if (moved > 0) {
      System.arraycopy(elements, index + 1, elements, index, moved);
    }
    elements[--size] = null;
    return removed;
  }

  /// Releases the backing array.
  @SuppressWarnings("unchecked")
  public void clear() {
    checkOwner();
    elements = (E[]) EMPTY_ARRAY;
    size = 0;
  }

  /// Returns a defensive copy of the live elements.
  public Object[] toArray() {
    checkOwner();
    return Arrays.copyOf(elements, size);
  }

  /// Repeats this list in place.
  public void repeatInPlace(long count) {
    if (size != 0 && count > MAX_ARRAY_SIZE / (long) size) {
      throw tooLarge();
    }
    checkOwner();
    if (count <= 0) {
      clear();
      return;
    }
    if (count == 1 || size == 0) {
      return;
    }
    var originalSize = size;
    var newSize = (int) (originalSize * count);
    ensureCapacity(newSize);
    for (var repetition = 1; repetition < (int) count; repetition++) {
      System.arraycopy(elements, 0, elements, repetition * originalSize, originalSize);
    }
    size = newSize;
  }

  /// Replaces [from, to) with the given items (which must not alias this list's storage).
  @SafeVarargs
  public final void splice(int from, int to, E... items) {
    Objects.checkFromToIndex(from, to, size);
    for (var item : items) {
      Objects.requireNonNull(item);
    }
    checkOwner();
    var newSize = (long) size - (to - from) + items.length;
    if (newSize > MAX_ARRAY_SIZE) {
      throw tooLarge();
    }
    ensureCapacity((int) newSize);
    System.arraycopy(elements, to, elements, from + items.length, size - to);
    System.arraycopy(items, 0, elements, from, items.length);
    if (newSize < size) {
      Arrays.fill(elements, (int) newSize, size, null);
    }
    size = (int) newSize;
  }


  // -- Java API

  public List<E> asJavaList() {
    final class JavaList extends AbstractList<E> implements RandomAccess {
      @Override
      public int size() {
        checkOwner();
        return size;
      }

      @Override
      public E get(int index) {
        Objects.checkIndex(index, size);
        checkOwner();
        return elements[index];
      }
    }
    return new JavaList();
  }


  // -- helpers

  private void checkOwner() {
    if (Thread.currentThread() != owner) {
      throw new PyRuntimeError( "PyList is not owned by the current thread");
    }
  }

  private void ensureCapacity(int minimumCapacity) {
    if (minimumCapacity <= elements.length) {
      return;
    }
    if (minimumCapacity > MAX_ARRAY_SIZE) {
      throw tooLarge();
    }

    var oldCapacity = elements.length;
    var grownCapacity = oldCapacity + (oldCapacity >> 1) + 1L;
    var newCapacity = (int) Math.min(MAX_ARRAY_SIZE, Math.max(minimumCapacity, grownCapacity));
    elements = Arrays.copyOf(elements, newCapacity);
  }

  private static UnimplementedFeatureError tooLarge() {
    // The runtime does not yet translate JVM allocation limits into Python MemoryError.
    return new UnimplementedFeatureError("list larger than the maximum Java array size");
  }
}
