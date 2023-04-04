// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.graph;

import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.graph.AbstractAccessContexts.ConcreteAccessContexts;
import com.android.tools.r8.graph.lens.GraphLens;
import com.android.tools.r8.utils.collections.ProgramMethodSet;
import com.google.common.collect.Sets;
import java.util.IdentityHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Holds whole program information about the usage of a given field.
 *
 * <p>The information is generated by the {@link com.android.tools.r8.shaking.Enqueuer}.
 */
public class FieldAccessInfoImpl implements FieldAccessInfo {

  public static final FieldAccessInfoImpl MISSING_FIELD_ACCESS_INFO = new FieldAccessInfoImpl(null);

  public static final int FLAG_IS_READ_FROM_ANNOTATION = 1 << 0;
  public static final int FLAG_IS_READ_FROM_METHOD_HANDLE = 1 << 1;
  public static final int FLAG_IS_WRITTEN_FROM_METHOD_HANDLE = 1 << 2;
  public static final int FLAG_HAS_REFLECTIVE_READ = 1 << 3;
  public static final int FLAG_HAS_REFLECTIVE_WRITE = 1 << 4;
  public static final int FLAG_IS_READ_FROM_RECORD_INVOKE_DYNAMIC = 1 << 5;

  // A direct reference to the definition of the field.
  private DexField field;

  // If this field is accessed from a method handle or has a reflective access.
  private int flags;

  // Maps every direct and indirect reference in a read-context to the set of methods in which that
  // reference appears.
  private AbstractAccessContexts readsWithContexts = AbstractAccessContexts.empty();

  // Maps every direct and indirect reference in a write-context to the set of methods in which that
  // reference appears.
  private AbstractAccessContexts writesWithContexts = AbstractAccessContexts.empty();

  public FieldAccessInfoImpl(DexField field) {
    this.field = field;
  }

  void destroyAccessContexts() {
    readsWithContexts = AbstractAccessContexts.unknown();
    writesWithContexts = AbstractAccessContexts.unknown();
  }

  void flattenAccessContexts() {
    flattenAccessContexts(readsWithContexts);
    flattenAccessContexts(writesWithContexts);
  }

  private void flattenAccessContexts(AbstractAccessContexts accessesWithContexts) {
    accessesWithContexts.flattenAccessContexts(field);
  }

  @Override
  public FieldAccessInfoImpl asMutable() {
    return this;
  }

  @Override
  public DexField getField() {
    return field;
  }

  @Override
  public AbstractAccessContexts getReadsWithContexts() {
    return readsWithContexts;
  }

  public void setReadsWithContexts(AbstractAccessContexts readsWithContexts) {
    this.readsWithContexts = readsWithContexts;
  }

  @Override
  public AbstractAccessContexts getWritesWithContexts() {
    return writesWithContexts;
  }

  public void setWritesWithContexts(AbstractAccessContexts writesWithContexts) {
    this.writesWithContexts = writesWithContexts;
  }

  @Override
  public int getNumberOfReadContexts() {
    return readsWithContexts.getNumberOfAccessContexts();
  }

  @Override
  public int getNumberOfWriteContexts() {
    return writesWithContexts.getNumberOfAccessContexts();
  }

  @Override
  public ProgramMethod getUniqueReadContext() {
    return readsWithContexts.isConcrete()
        ? readsWithContexts.asConcrete().getUniqueAccessContext()
        : null;
  }

  @Override
  public boolean hasKnownReadContexts() {
    return !readsWithContexts.isTop();
  }

  @Override
  public boolean hasKnownWriteContexts() {
    return !writesWithContexts.isTop();
  }

  @Override
  public void forEachIndirectAccess(Consumer<DexField> consumer) {
    // There can be indirect reads and writes of the same field reference, so we need to keep track
    // of the previously-seen indirect accesses to avoid reporting duplicates.
    Set<DexField> visited = Sets.newIdentityHashSet();
    forEachIndirectAccess(consumer, readsWithContexts, visited);
    forEachIndirectAccess(consumer, writesWithContexts, visited);
  }

  private void forEachIndirectAccess(
      Consumer<DexField> consumer,
      AbstractAccessContexts accessesWithContexts,
      Set<DexField> visited) {
    if (accessesWithContexts.isBottom()) {
      return;
    }
    if (accessesWithContexts.isConcrete()) {
      accessesWithContexts
          .asConcrete()
          .forEachAccess(consumer, access -> access != field && visited.add(access));
      return;
    }
    throw new Unreachable("Should never be iterating the indirect accesses when they are unknown");
  }

  @Override
  public void forEachIndirectAccessWithContexts(BiConsumer<DexField, ProgramMethodSet> consumer) {
    Map<DexField, ProgramMethodSet> indirectAccessesWithContexts = new IdentityHashMap<>();
    addAccessesWithContextsToMap(
        readsWithContexts, access -> access != field, indirectAccessesWithContexts);
    addAccessesWithContextsToMap(
        writesWithContexts, access -> access != field, indirectAccessesWithContexts);
    indirectAccessesWithContexts.forEach(consumer);
  }

  private static void addAccessesWithContextsToMap(
      AbstractAccessContexts accessesWithContexts,
      Predicate<DexField> predicate,
      Map<DexField, ProgramMethodSet> out) {
    if (accessesWithContexts.isBottom()) {
      return;
    }
    if (accessesWithContexts.isConcrete()) {
      extendAccessesWithContexts(
          accessesWithContexts.asConcrete().getAccessesWithContexts(), predicate, out);
      return;
    }
    throw new Unreachable("Should never be iterating the indirect accesses when they are unknown");
  }

  private static void extendAccessesWithContexts(
      Map<DexField, ProgramMethodSet> accessesWithContexts,
      Predicate<DexField> predicate,
      Map<DexField, ProgramMethodSet> out) {
    accessesWithContexts.forEach(
        (access, contexts) -> {
          if (predicate.test(access)) {
            out.computeIfAbsent(access, ignore -> ProgramMethodSet.create()).addAll(contexts);
          }
        });
  }

  @Override
  public void forEachAccessContext(Consumer<ProgramMethod> consumer) {
    forEachReadContext(consumer);
    forEachWriteContext(consumer);
  }

  @Override
  public void forEachReadContext(Consumer<ProgramMethod> consumer) {
    readsWithContexts.forEachAccessContext(consumer);
  }

  @Override
  public void forEachWriteContext(Consumer<ProgramMethod> consumer) {
    writesWithContexts.forEachAccessContext(consumer);
  }

  @Override
  public boolean hasReflectiveAccess() {
    return hasReflectiveRead() || hasReflectiveWrite();
  }

  @Override
  public boolean hasReflectiveRead() {
    return (flags & FLAG_HAS_REFLECTIVE_READ) != 0;
  }

  public void setHasReflectiveRead() {
    flags |= FLAG_HAS_REFLECTIVE_READ;
  }

  @Override
  public boolean hasReflectiveWrite() {
    return (flags & FLAG_HAS_REFLECTIVE_WRITE) != 0;
  }

  public void setHasReflectiveWrite() {
    flags |= FLAG_HAS_REFLECTIVE_WRITE;
  }

  /** Returns true if this field is read by the program. */
  @Override
  public boolean isRead() {
    return isReadDirectly() || isReadIndirectly();
  }

  private boolean isReadDirectly() {
    return !readsWithContexts.isEmpty();
  }

  private boolean isReadIndirectly() {
    return hasReflectiveRead()
        || isReadFromAnnotation()
        || isReadFromMethodHandle()
        || isReadFromRecordInvokeDynamic();
  }

  @Override
  public boolean isReadFromAnnotation() {
    return (flags & FLAG_IS_READ_FROM_ANNOTATION) != 0;
  }

  public void setReadFromAnnotation() {
    flags |= FLAG_IS_READ_FROM_ANNOTATION;
  }

  @Override
  public boolean isReadFromMethodHandle() {
    return (flags & FLAG_IS_READ_FROM_METHOD_HANDLE) != 0;
  }

  public void setReadFromMethodHandle() {
    flags |= FLAG_IS_READ_FROM_METHOD_HANDLE;
  }

  @Override
  public boolean isReadFromRecordInvokeDynamic() {
    return (flags & FLAG_IS_READ_FROM_RECORD_INVOKE_DYNAMIC) != 0;
  }

  public void setReadFromRecordInvokeDynamic() {
    flags |= FLAG_IS_READ_FROM_RECORD_INVOKE_DYNAMIC;
  }

  public void clearReadFromRecordInvokeDynamic() {
    flags &= ~FLAG_IS_READ_FROM_RECORD_INVOKE_DYNAMIC;
  }

  /**
   * Returns true if this field is only read by methods for which {@param predicate} returns true.
   */
  @Override
  public boolean isReadOnlyInMethodSatisfying(Predicate<ProgramMethod> predicate) {
    return readsWithContexts.isAccessedOnlyInMethodSatisfying(predicate) && !isReadIndirectly();
  }

  /** Returns true if this field is written by the program. */
  @Override
  public boolean isWritten() {
    return isWrittenDirectly() || isWrittenIndirectly();
  }

  private boolean isWrittenDirectly() {
    return !writesWithContexts.isEmpty();
  }

  private boolean isWrittenIndirectly() {
    return hasReflectiveWrite() || isWrittenFromMethodHandle();
  }

  @Override
  public boolean isWrittenFromMethodHandle() {
    return (flags & FLAG_IS_WRITTEN_FROM_METHOD_HANDLE) != 0;
  }

  public void setWrittenFromMethodHandle() {
    flags |= FLAG_IS_WRITTEN_FROM_METHOD_HANDLE;
  }

  /**
   * Returns true if this field is written by a method for which {@param predicate} returns true.
   */
  @Override
  public boolean isWrittenInMethodSatisfying(Predicate<ProgramMethod> predicate) {
    return writesWithContexts.isAccessedInMethodSatisfying(predicate);
  }

  /**
   * Returns true if this field is only written by methods for which {@param predicate} returns
   * true.
   */
  @Override
  public boolean isWrittenOnlyInMethodSatisfying(Predicate<ProgramMethod> predicate) {
    return writesWithContexts.isAccessedOnlyInMethodSatisfying(predicate) && !isWrittenIndirectly();
  }

  /**
   * Returns true if this field is written by a method in the program other than {@param method}.
   */
  @Override
  public boolean isWrittenOutside(DexEncodedMethod method) {
    return writesWithContexts.isAccessedOutside(method) || isWrittenIndirectly();
  }

  public boolean recordRead(DexField access, ProgramMethod context) {
    if (readsWithContexts.isBottom()) {
      readsWithContexts = new ConcreteAccessContexts();
    }
    if (readsWithContexts.isConcrete()) {
      return readsWithContexts.asConcrete().recordAccess(access, context);
    }
    return false;
  }

  public boolean recordWrite(DexField access, ProgramMethod context) {
    if (writesWithContexts.isBottom()) {
      writesWithContexts = new ConcreteAccessContexts();
    }
    if (writesWithContexts.isConcrete()) {
      return writesWithContexts.asConcrete().recordAccess(access, context);
    }
    return false;
  }

  public void clearReads() {
    assert !hasReflectiveAccess();
    assert !isReadFromAnnotation();
    assert !isReadFromMethodHandle();
    readsWithContexts = AbstractAccessContexts.empty();
    clearReadFromRecordInvokeDynamic();
  }

  public void clearWrites() {
    writesWithContexts = AbstractAccessContexts.empty();
  }

  public FieldAccessInfoImpl rewrittenWithLens(DexDefinitionSupplier definitions, GraphLens lens) {
    FieldAccessInfoImpl rewritten = new FieldAccessInfoImpl(lens.lookupField(field));
    rewritten.flags = flags;
    rewritten.readsWithContexts = readsWithContexts.rewrittenWithLens(definitions, lens);
    rewritten.writesWithContexts = writesWithContexts.rewrittenWithLens(definitions, lens);
    return rewritten;
  }

  public FieldAccessInfoImpl join(FieldAccessInfoImpl impl) {
    FieldAccessInfoImpl merged = new FieldAccessInfoImpl(field);
    merged.flags = flags | impl.flags;
    merged.readsWithContexts = readsWithContexts.join(impl.readsWithContexts);
    merged.writesWithContexts = writesWithContexts.join(impl.writesWithContexts);
    return merged;
  }
}
