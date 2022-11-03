// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.naming;

import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexString;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.naming.MethodNamingState.InternalNewNameState;
import com.android.tools.r8.utils.MethodSignatureEquivalence;
import com.google.common.base.Equivalence.Wrapper;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Function;

class MethodNamingState<KeyType> extends MethodNamingStateBase<KeyType, InternalNewNameState> {

  private final MethodReservationState<?> reservationState;
  private final MethodNamingState<KeyType> parentNamingState;
  private final MemberNamingStrategy namingStrategy;

  private MethodNamingState(
      MethodNamingState<KeyType> parentNamingState,
      Function<DexMethod, KeyType> keyTransform,
      MemberNamingStrategy strategy,
      MethodReservationState<?> reservationState) {
    super(keyTransform);
    this.parentNamingState = parentNamingState;
    this.namingStrategy = strategy;
    this.reservationState = reservationState;
  }

  static <KeyType> MethodNamingState<KeyType> createRoot(
      Function<DexMethod, KeyType> keyTransform,
      MemberNamingStrategy namingStrategy,
      MethodReservationState<?> reservationState) {
    return new MethodNamingState<>(null, keyTransform, namingStrategy, reservationState);
  }

  MethodNamingState<KeyType> createChild(MethodReservationState<?> frontierReservationState) {
    return new MethodNamingState<>(
        this, this.keyTransform, this.namingStrategy, frontierReservationState);
  }

  DexString newOrReservedNameFor(DexEncodedMethod method, MethodNameMinifier.State minifierState, DexClass holder) {
    return newOrReservedNameFor(method, minifierState, this::isAvailable, holder);
  }

  DexString newOrReservedNameFor(DexEncodedMethod method, MethodNameMinifier.State minifierState) {
    return newOrReservedNameFor(method, minifierState, this::isAvailable);
  }

  DexString newOrReservedNameFor(
          DexEncodedMethod method, MethodNameMinifier.State minifierState, BiPredicate<DexString, DexMethod> isAvailable) {
    return newOrReservedNameFor(method, minifierState, isAvailable, null);
  }

  DexString newOrReservedNameFor(
          DexEncodedMethod method, MethodNameMinifier.State minifierState, BiPredicate<DexString, DexMethod> isAvailable, DexClass holder) {

//    if (method.getReference().holder.toSourceString().contains("FinderBulletUIC")) {
//      System.out.printf("----------------- method: %s, stack:%s\n", method.getReference().toSourceString(), StringUtils.stacktraceAsString(new Throwable()));
//    }

    DexString newName = getAssignedName(method.getReference());
    if (newName != null) {
      return newName;
    }
//    if (Objects.equals(method.getName().toString(), "checkShowPromote")) {
//      System.out.printf("Find 3: %s \n", method.toSourceString());
//    }
    Set<DexString> reservedNamesFor = reservationState.getReservedNamesFor(method.getReference());
    // Reservations with applymapping can cause multiple reserved names added to the frontier. In
    // that case, the strategy will return the correct one.
    if (reservedNamesFor != null && reservedNamesFor.size() == 1) {
      DexString candidate = reservedNamesFor.iterator().next();
      if (isAvailable(candidate, method.getReference())) {
        return candidate;
      }
    } else if (holder != null && reservedNamesFor != null && reservedNamesFor.size() > 1) {
      for (DexString candidate : reservedNamesFor) {
        if (isAvailableForInterface(candidate, holder, method, minifierState) && isAvailable(candidate, method.getReference())) {
           System.out.printf("isAvailableForInterface, candidate: %s, holder: %s, method: %s%n", candidate.toString(), method.getReference().toSourceString(), holder.getSimpleName());
           return candidate;
        }
      }
    }
    return nextName(method, isAvailable);
  }

  boolean isAvailableForInterface(DexString candidate, DexClass holder, DexEncodedMethod method, MethodNameMinifier.State minifierState) {
    return holder.getInterfaces().stream().anyMatch(iface -> isMatched(iface, candidate, method, minifierState));
  }

  private boolean isMatched(DexType iface, DexString candidate, DexEncodedMethod method, MethodNameMinifier.State minifierState) {
    MethodReservationState<?> state = minifierState.getReservationState(iface);
    if (state != null) {
      Set<DexString> candidates = state.getReservedNamesFor(method.getReference());
      return candidates != null && candidates.contains(candidate);
    }
    return false;
  }

  DexString nextName(DexEncodedMethod method, BiPredicate<DexString, DexMethod> isAvailable) {
    InternalNewNameState internalState = getOrCreateInternalState(method.getReference());
    DexString newName = namingStrategy.next(method, internalState, isAvailable);
    assert newName != null;
    return newName;
  }

  void addRenaming(DexString newName, DexEncodedMethod method) {
    InternalNewNameState internalState = getOrCreateInternalState(method.getReference());
    internalState.addRenaming(newName, method.getReference());
  }

  boolean isAvailable(DexString candidate, DexMethod method) {
    Set<Wrapper<DexMethod>> usedBy = getUsedBy(candidate, method);
    if (usedBy != null && usedBy.contains(MethodSignatureEquivalence.get().wrap(method))) {
//      if (method.toSourceString().contains("FinderLiveVisitorGameTogetherWidget")) {
//        System.out.printf("Find 0: %s \n", method.toSourceString());
//        for (Wrapper<DexMethod> methodWrapper : usedBy) {
//          DexMethod usedDexMethod = methodWrapper.get();
//          assert usedDexMethod != null;
//          System.out.printf("----------------- usedBy: %s\n", usedDexMethod.toSourceString());
//        }
//      }
      return true;
    }
    boolean isReserved = reservationState.isReserved(candidate, method);
    if (!isReserved && usedBy == null) {
//      if (method.toSourceString().contains("FinderLiveVisitorGameTogetherWidget")) {
//         System.out.printf("Find 1: %s \n", method.toSourceString());
//      }
      return true;
    }
    // We now have a reserved name. We therefore have to check if the reservation is
    // equal to candidate, otherwise the candidate is not available.
    Set<DexString> methodReservedNames = reservationState.getReservedNamesFor(method);
    boolean containsReserved = methodReservedNames != null && methodReservedNames.contains(candidate);

//    if (method.toSourceString().contains("FinderLiveVisitorGameTogetherWidget")) {
//      System.out.printf("Find 2: %s \n", method.toSourceString());
//      System.out.printf("----------------- containsReserved: %b\n", containsReserved);
//      if (usedBy != null) {
//        for (Wrapper<DexMethod> methodWrapper : usedBy) {
//          DexMethod usedDexMethod = methodWrapper.get();
//          assert usedDexMethod != null;
//          System.out.printf("----------------- usedBy: %s\n", usedDexMethod.toSourceString());
//        }
//      }
//    }
    if (containsReserved && usedBy != null) {
      for (Wrapper<DexMethod> methodWrapper : usedBy) {
        DexMethod usedDexMethod = methodWrapper.get();
        assert usedDexMethod != null;
        if (method.proto.equals(usedDexMethod.proto)) {
          System.out.print("Find containsReserved: \n");
          System.out.printf("------- usedBy: %s\n", usedDexMethod.toSourceString());
          System.out.printf("------- method: %s\n", method.toSourceString());
          return false;
        }
      }
    }
    return containsReserved;
  }

  private Set<Wrapper<DexMethod>> getUsedBy(DexString name, DexMethod method) {
    InternalNewNameState internalState = getInternalState(method);
    Set<Wrapper<DexMethod>> nameUsedBy = null;
    if (internalState != null) {
      nameUsedBy = internalState.getUsedBy(name);
    }
    if (nameUsedBy == null && parentNamingState != null) {
      return parentNamingState.getUsedBy(name, method);
    }
    return nameUsedBy;
  }

  private DexString getAssignedName(DexMethod method) {
    DexString assignedName = null;
    InternalNewNameState internalState = getInternalState(method);
    if (internalState != null) {
      assignedName = internalState.getAssignedName(method);
    }
    if (assignedName == null && parentNamingState != null) {
      assignedName = parentNamingState.getAssignedName(method);
    }
    return assignedName;
  }

  @Override
  InternalNewNameState createInternalState(DexMethod method) {
    InternalNewNameState parentInternalState = null;
    if (this.parentNamingState != null) {
      parentInternalState = parentNamingState.getOrCreateInternalState(method);
    }
    return new InternalNewNameState(parentInternalState);
  }

  static class InternalNewNameState implements InternalNamingState {

    private final InternalNewNameState parentInternalState;
    private Map<Wrapper<DexMethod>, DexString> originalToRenamedNames = new HashMap<>();
    private Map<DexString, Set<Wrapper<DexMethod>>> usedBy = new HashMap<>();

    private static final int INITIAL_NAME_COUNT = 1;
    private static final int INITIAL_DICTIONARY_INDEX = 0;

    private int nameCount;
    private int dictionaryIndex;

    private InternalNewNameState(InternalNewNameState parentInternalState) {
      this.parentInternalState = parentInternalState;
      this.dictionaryIndex =
          parentInternalState == null
              ? INITIAL_DICTIONARY_INDEX
              : parentInternalState.dictionaryIndex;
      this.nameCount =
          parentInternalState == null ? INITIAL_NAME_COUNT : parentInternalState.nameCount;
    }

    @Override
    public int getDictionaryIndex() {
      return dictionaryIndex;
    }

    @Override
    public int incrementDictionaryIndex() {
      return dictionaryIndex++;
    }

    Set<Wrapper<DexMethod>> getUsedBy(DexString name) {
      return usedBy.get(name);
    }

    DexString getAssignedName(DexMethod method) {
      return originalToRenamedNames.get(MethodSignatureEquivalence.get().wrap(method));
    }

    void addRenaming(DexString newName, DexMethod method) {
      final Wrapper<DexMethod> wrappedMethod = MethodSignatureEquivalence.get().wrap(method);
      originalToRenamedNames.put(wrappedMethod, newName);
      usedBy.computeIfAbsent(newName, ignore -> new HashSet<>()).add(wrappedMethod);
    }

    private boolean checkParentPublicNameCountIsLessThanOrEqual() {
      int maxParentCount = 0;
      InternalNewNameState tmp = parentInternalState;
      while (tmp != null) {
        maxParentCount = Math.max(tmp.nameCount, maxParentCount);
        tmp = tmp.parentInternalState;
      }
      assert maxParentCount <= nameCount;
      return true;
    }

    @Override
    public int incrementNameIndex() {
      assert checkParentPublicNameCountIsLessThanOrEqual();
      return nameCount++;
    }
  }
}
