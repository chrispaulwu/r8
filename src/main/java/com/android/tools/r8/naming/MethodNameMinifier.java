// Copyright (c) 2017, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.naming;

import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexString;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.MethodAccessInfoCollection;
import com.android.tools.r8.graph.MethodResolutionResult;
import com.android.tools.r8.graph.SubtypingInfo;
import com.android.tools.r8.graph.TopDownClassHierarchyTraversal;
import com.android.tools.r8.shaking.AppInfoWithLiveness;
import com.android.tools.r8.utils.ConsumerUtils;
import com.android.tools.r8.utils.InternalOptions;
import com.android.tools.r8.utils.ThreadUtils;
import com.android.tools.r8.utils.Timing;
import com.google.common.collect.BiMap;
import com.google.common.collect.HashBiMap;
import com.google.common.collect.ImmutableMap;

import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.function.Function;

/**
 * A pass to rename methods using common, short names.
 *
 * <p>To assign names, we model the scopes of methods names and overloading/shadowing based on the
 * subtyping tree of classes. Such a naming scope is encoded by {@link MethodReservationState} and
 * {@link MethodNamingState}. {@link MethodReservationState} keeps track of reserved names in the
 * library and pulls reservations on classpath and program path to the library frontier. It keeps
 * track of names that are are reserved (due to keep annotations or otherwise) where {@link
 * MethodNamingState} keeps track of all renamed names to ensure freshness.
 *
 * <p>As in the Dalvik VM method dispatch takes argument and return types of methods into account,
 * we can further reuse names if the prototypes of two methods differ. For this, we store the above
 * state separately for each proto using a map from protos to {@link
 * MethodReservationState.InternalReservationState} objects. These internal state objects are also
 * linked.
 *
 * <p>Name assignment happens in 4 stages. In the first stage, we record all names that are used by
 * library classes or are flagged using a keep rule as reserved. This step also allocates the {@link
 * MethodNamingState} objects for library classes. We can fully allocate these objects as we never
 * perform naming for library classes. For non-library classes, we only allocate a state for the
 * highest non-library class, i.e., we allocate states for every direct subtype of a library class.
 * The states at the boundary between library and program classes are referred to as the frontier
 * states in the code.
 *
 * <p>When reserving names in program classes, we reserve them in the state of the corresponding
 * frontier class. This is to ensure that the names are not used for renaming in any supertype.
 * Thus, they will still be available in the subtype where they are reserved. Note that name
 * reservation only blocks names from being used for minification. We assume that the input program
 * is correctly named.
 *
 * <p>In stage 2, we reserve names that stem from interfaces. These are not propagated to
 * subinterfaces or implementing classes. Instead, stage 3 makes sure to query related states when
 * making naming decisions.
 *
 * <p>In stage 3, we compute minified names for all interface methods. We do this first to reduce
 * assignment conflicts. Interfaces do not build a tree-like inheritance structure we can exploit.
 * Thus, we have to infer the structure on the fly. For this, we compute a sets of reachable
 * interfaces. i.e., interfaces that are related via subtyping. Based on these sets, we then find,
 * for each method signature, the classes and interfaces this method signature is defined in. For
 * classes, as we still use frontier states at this point, we do not have to consider subtype
 * relations. For interfaces, we reserve the name in all reachable interfaces and thus ensure
 * availability.
 *
 * <p>Name assignment in this phase is a search over all impacted naming states. Using the naming
 * state of the interface this method first originated from, we propose names until we find a
 * matching one. We use the naming state of the interface to not impact name availability in naming
 * states of classes. Hence, skipping over names during interface naming does not impact their
 * availability in the next phase.
 *
 * <p>In stage 4, we assign names to methods by traversing the subtype tree, now allocating separate
 * naming states for each class starting from the frontier. In the first swoop, we allocate all
 * non-private methods, updating naming states accordingly.
 *
 * <p>Finally, the computed renamings are returned as a map from {@link DexMethod} to {@link
 * DexString}. The MethodNameMinifier object should not be retained to ensure all intermediate state
 * is freed.
 *
 * <p>TODO(b/130338621): Currently, we do not minify members of annotation interfaces, as this would
 * require parsing and minification of the string arguments to annotations.
 */
class MethodNameMinifier {

  // A class that provides access to the minification state. An instance of this class is passed
  // from the method name minifier to the interface method name minifier.
  class State {

    void putRenaming(DexEncodedMethod key, DexString newName) {
      if (newName != key.getName()) {
        renaming.put(key.getReference(), newName);
      }
    }

    MethodReservationState<?> getReservationState(DexType type) {
      return reservationStates.get(type);
    }

    MethodNamingState<?> getNamingState(DexType type) {
      return getOrAllocateMethodNamingStates(type);
    }

    void allocateReservationStateAndReserve(DexType type, DexType frontier) {
      MethodNameMinifier.this.allocateReservationStateAndReserve(
          type, frontier, rootReservationState);
    }

    DexType getFrontier(DexType type) {
      return frontiers.getOrDefault(type, type);
    }

    DexString getReservedName(DexEncodedMethod method, DexClass holder) {
      return strategy.getReservedName(method, holder);
    }
  }

  private final AppView<AppInfoWithLiveness> appView;
  private final MemberNamingStrategy strategy;

  private final Map<DexMethod, DexString> renaming = new IdentityHashMap<>();
  private final Map<DexMethod, DexString> keepRenaming = new IdentityHashMap<>();

  private final State minifierState = new State();

  // The use of a bidirectional map allows us to map a naming state to the type it represents,
  // which is useful for debugging.
  private final BiMap<DexType, MethodReservationState<?>> reservationStates = HashBiMap.create();
  private final Map<DexType, MethodNamingState<?>> namingStates = new IdentityHashMap<>(); //这里保存的是最终的命名状态
  private final Map<DexType, DexType> frontiers = new IdentityHashMap<>();

  private final MethodNamingState<?> rootNamingState;
  private final MethodReservationState<?> rootReservationState;

  MethodNameMinifier(
      AppView<AppInfoWithLiveness> appView,
      MemberNamingStrategy strategy) {
    this.appView = appView;
    this.strategy = strategy;
    rootReservationState = MethodReservationState.createRoot(getReservationKeyTransform());
    reservationStates.put(null, rootReservationState);
    rootNamingState =
        MethodNamingState.createRoot(getNamingKeyTransform(), strategy, rootReservationState);
    namingStates.put(null, rootNamingState);
  }

  private Function<DexMethod, ?> getReservationKeyTransform() {
    if (appView.options().getProguardConfiguration().isOverloadAggressively()
        && appView.options().isGeneratingClassFiles()) {
      // Use the full proto as key, hence reuse names based on full signature.
      return method -> method.proto;
    } else {
      // Only use the parameters as key, hence do not reuse names on return type.
      return method -> method.proto.parameters;
    }
  }

  private Function<DexMethod, ?> getNamingKeyTransform() {
    return appView.options().isGeneratingClassFiles()
        ? getReservationKeyTransform()
        : method -> null;
  }

  static class MethodRenaming {

    final Map<DexMethod, DexString> renaming;
    final Map<DexMethod, DexString> keepRenaming;


    private MethodRenaming(Map<DexMethod, DexString> renaming, Map<DexMethod, DexString> keepRenaming) {
      this.renaming = renaming;
      this.keepRenaming = keepRenaming;
    }

    public static MethodRenaming empty() {
      return new MethodRenaming(ImmutableMap.of(), ImmutableMap.of());
    }
  }

  MethodRenaming computeRenaming(
      Iterable<DexClass> interfaces,
      SubtypingInfo subtypingInfo,
      ExecutorService executorService,
      Timing timing)
      throws ExecutionException {
    // Phase 1: Reserve all the names that need to be kept and allocate linked state in the
    //          library part.
    timing.begin("Phase 1");
    reserveNamesInClasses();
    timing.end();
    // Phase 2: Reserve all the names that are required for interfaces, and then assign names to
    //          interface methods. These are assigned by finding a name that is free in all naming
    //          states that may hold an implementation.
    timing.begin("Phase 2");
    InterfaceMethodNameMinifier interfaceMethodNameMinifier =
        new InterfaceMethodNameMinifier(appView, minifierState, subtypingInfo);
    timing.end();
    timing.begin("Phase 3");
    interfaceMethodNameMinifier.assignNamesToInterfaceMethods(timing, interfaces);
    timing.end();
    // Phase 4: Assign names top-down by traversing the subtype hierarchy.
    timing.begin("Phase 4");
    assignNamesToClassesMethods();
    renameMethodsInUnrelatedClasspathClasses();
    timing.end();
    timing.begin("Phase 5: non-rebound references");
    renameNonReboundReferences(executorService);
    timing.end();

    return new MethodRenaming(renaming, keepRenaming);
  }

  private void assignNamesToClassesMethods() {
    TopDownClassHierarchyTraversal.forAllClasses(appView)
        .excludeInterfaces()
        .visit(
            appView.appInfo().classes(),
            clazz -> {
              DexType type = clazz.type;
              MethodReservationState<?> reservationState =
                  reservationStates.get(frontiers.getOrDefault(type, type));
              assert reservationState != null
                  : "Could not find reservation state for " + type.toString();
              MethodNamingState<?> namingState =
                  namingStates.computeIfAbsent(
                      type,
                      ignore ->
                          namingStates
                              .getOrDefault(clazz.superType, rootNamingState)
                              .createChild(reservationState));
              DexClass holder = appView.definitionFor(type);
              if (holder != null && strategy.allowMemberRenaming(holder)) {
                for (DexEncodedMethod method : holder.allMethodsSorted()) {
//                  if (holder.toSourceString().contains("com.tencent.mm.loader.builder.RequestBuilder")) {
//                    System.out.printf("----------------- method: %s, stack:%s\n", method.getReference().toSourceString(), StringUtils.stacktraceAsString(new Throwable()));
//                  }
                  assignNameToMethod(holder, method, namingState, minifierState);
                }
              }
            });
  }

  private void renameMethodsInUnrelatedClasspathClasses() {
    if (appView.options().getProguardConfiguration().hasApplyMappingFile()) {
      appView
          .appInfo()
          .forEachReferencedClasspathClass(
              clazz -> {
                for (DexEncodedMethod method : clazz.methods()) {
                  DexString reservedName = strategy.getReservedName(method, clazz);
                  if (reservedName != null && reservedName != method.getReference().name) {
                    renaming.put(method.getReference(), reservedName);
                  }
                }
              });
    }
  }

  private void assignNameToMethod(
      DexClass holder, DexEncodedMethod method, MethodNamingState<?> state, State minifierState) {
    if (method.isInitializer()) {
      return;
    }
    // The strategy may have an explicit naming for this member which we query first. It may be that
    // the strategy will return the identity name, for which we have to look into a previous
    // renaming tracked by the state.
    DexString newName = strategy.getReservedName(method, holder);
    if (newName == null || newName == method.getName()) {
      newName = state.newOrReservedNameFor(method, minifierState, holder);
    } else {
      DexString assignedName = state.getAssignedName(method.getReference());
      if (assignedName != null && newName != assignedName && state.isAvailable(assignedName, method.getReference())) {
        System.out.printf("Found no same assigned and reserved name, method: %s, newName: %s, assignedName:%s\n", method.getReference().toSourceString(), newName, assignedName);
        newName = assignedName;
      }
    }
    if (method.getName() != newName) {
      renaming.put(method.getReference(), newName);
    } else if (!appView.appInfo().isMinificationAllowed(method.getReference())) {
      keepRenaming.put(method.getReference(), newName);
    }
    state.addRenaming(newName, method);
//    if (holder.toSourceString().contains("com.tencent.thumbplayer.tplayer.plugins.TPPluginManager")) {
//      System.out.printf("----------------- method: %s, newName: %s, stack:%s\n", method.getReference().toSourceString(), newName, StringUtils.stacktraceAsString(new Throwable()));
//    }
  }

  private void reserveNamesInClasses() {
    // Ensure reservation state for java.lang.Object is always created, even if the type is missing.
    allocateReservationStateAndReserve(
        appView.dexItemFactory().objectType,
        appView.dexItemFactory().objectType,
        rootReservationState);
    TopDownClassHierarchyTraversal.forAllClasses(appView)
        .visit(
            appView.appInfo().classes(),
            clazz -> {
              DexType type = clazz.type;
              DexType frontier = frontiers.getOrDefault(clazz.superType, type); // frontier 即就是 SuperType
              if (frontier != type || clazz.isProgramClass()) {
                DexType existingValue = frontiers.put(clazz.type, frontier); // clazzType -> superClazzType
                assert existingValue == null;
              }
              // If this is not a program class (or effectively a library class as it is missing)
              // move the frontier forward. This will ensure all reservations are put on the library
              // or classpath frontier for the program path.
              allocateReservationStateAndReserve(
                  type,
                  frontier,
                  reservationStates.getOrDefault(clazz.superType, rootReservationState));
            });
  }

  private void allocateReservationStateAndReserve(
      DexType type, DexType frontier, MethodReservationState<?> parent) {
    MethodReservationState<?> state =
        reservationStates.computeIfAbsent(frontier, ignore -> parent.createChild()); // 如果能从super中找到state，则get，反之创建
    DexClass holder = appView.definitionFor(type);
    if (holder != null) {
      for (DexEncodedMethod method : shuffleMethods(holder.methods(), appView.options())) {
        DexString reservedName = strategy.getReservedName(method, holder); // 如果当前的method 是 library，keep, 或者apply mapping中，则保留名字
        if (reservedName != null) {
//          if (method.getReference().toSourceString().contains("FinderLiveVisitorGameTogetherWidget") ||
//                  method.getReference().toSourceString().contains("FinderLiveAnchorGameTogetherWidget")) {
//            System.out.print("Find reservedName: \n");
//            System.out.printf("------- reservedName: %s\n", reservedName.toSourceString());
//            System.out.printf("------- method: %s\n", method.toSourceString());
//          }
          state.reserveName(reservedName, method);
        }
      }
    }
  }

  private MethodNamingState<?> getOrAllocateMethodNamingStates(DexType type) {
    MethodNamingState<?> namingState = namingStates.get(type);
    if (namingState == null) {
      MethodNamingState<?> parentState;
      if (type == appView.dexItemFactory().objectType) {
        parentState = rootNamingState;
      } else {
        DexClass holder = appView.definitionFor(type);
        if (holder == null) {
          parentState = getOrAllocateMethodNamingStates(appView.dexItemFactory().objectType);
        } else {
          parentState = getOrAllocateMethodNamingStates(holder.superType);
        }
      }
      // There can be gaps in the reservation states if a library class extends a program class.
      // See b/150325706 for more information.
      MethodReservationState<?> reservationState = findReservationStateInHierarchy(type);
      assert reservationState != null : "Could not find reservation state for " + type.toString();
      namingState = parentState.createChild(reservationState);
      namingStates.put(type, namingState);
    }
    return namingState;
  }

  private MethodReservationState<?> findReservationStateInHierarchy(DexType type) {
    MethodReservationState<?> reservationState = reservationStates.get(type);
    if (reservationState != null) {
      return reservationState;
    }
    if (appView.definitionFor(type) == null) {
      // Reservation states for missing definitions is always object.
      return reservationStates.get(appView.dexItemFactory().objectType);
    }
    // If we cannot find the reservation state, which is a result from a library class extending
    // a program class. The gap is tracked in the frontier state.
    assert frontiers.containsKey(type);
    DexType frontierType = frontiers.get(type);
    reservationState = reservationStates.get(frontierType);
    assert reservationState != null
        : "Could not find reservation state for frontier type " + frontierType.toString();
    return reservationState;
  }

  private void renameNonReboundReferences(ExecutorService executorService)
      throws ExecutionException {
    Map<DexMethod, DexString> nonReboundRenamings = new ConcurrentHashMap<>();
    MethodAccessInfoCollection methodAccessInfoCollection =
        appView.appInfo().getMethodAccessInfoCollection();
    ThreadUtils.processItems(
        methodAccessInfoCollection::forEachMethodReference,
        method -> renameNonReboundMethodReference(method, nonReboundRenamings),
        executorService);
    renaming.putAll(nonReboundRenamings);
  }

  private void renameNonReboundMethodReference(
      DexMethod method, Map<DexMethod, DexString> nonReboundRenamings) {
    if (method.getHolderType().isArrayType()) {
      return;
    }

    DexClass holder = appView.contextIndependentDefinitionFor(method.getHolderType());
    if (holder == null) {
      return;
    }

    MethodResolutionResult resolutionResult =
        appView.appInfo().resolveMethodOnLegacy(holder, method);
    if (resolutionResult.isSingleResolution()) {
      DexEncodedMethod resolvedMethod = resolutionResult.getSingleTarget();
      if (resolvedMethod.getReference() == method) {
        return;
      }

      DexString newName = renaming.get(resolvedMethod.getReference());
      if (newName != null) {
        assert newName != resolvedMethod.getName();
        nonReboundRenamings.put(method, newName);
      }
      return;
    }

    // If resolution fails, the method must be renamed consistently with the targets that give rise
    // to the failure.
    assert resolutionResult.isFailedResolution();

    List<DexEncodedMethod> targets = new ArrayList<>();
    resolutionResult
        .asFailedResolution()
        .forEachFailureDependency(ConsumerUtils.emptyConsumer(), targets::add);
    if (!targets.isEmpty()) {
      DexString newName = renaming.get(targets.get(0).getReference());
      assert targets.stream().allMatch(target -> renaming.get(target.getReference()) == newName);
      if (newName != null) {
        assert newName != targets.get(0).getName();
        nonReboundRenamings.put(method, newName);
      }
    }
  }

  // Shuffles the given methods if assertions are enabled and deterministic debugging is disabled.
  // Used to ensure that the generated output is deterministic.
  private static Iterable<DexEncodedMethod> shuffleMethods(
      Iterable<DexEncodedMethod> methods, InternalOptions options) {
    return options.testing.irOrdering.order(methods);
  }
}
