// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.optimize.info;

import static com.android.tools.r8.ir.optimize.info.DefaultMethodOptimizationInfo.UNKNOWN_TYPE;

import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.DexEncodedMethod.ClassInlinerEligibility;
import com.android.tools.r8.graph.DexEncodedMethod.TrivialInitializer;
import com.android.tools.r8.graph.DexString;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.ir.analysis.type.TypeLatticeElement;
import com.android.tools.r8.ir.optimize.info.ParameterUsagesInfo.ParameterUsage;
import java.util.BitSet;
import java.util.Set;

public class UpdatableMethodOptimizationInfo implements MethodOptimizationInfo {

  private boolean cannotBeKept = false;
  private boolean classInitializerMayBePostponed = false;
  private boolean hasBeenInlinedIntoSingleCallSite = false;
  private Set<DexType> initializedClassesOnNormalExit =
      DefaultMethodOptimizationInfo.UNKNOWN_INITIALIZED_CLASSES_ON_NORMAL_EXIT;
  private int returnedArgument = DefaultMethodOptimizationInfo.UNKNOWN_RETURNED_ARGUMENT;
  private boolean mayHaveSideEffects = DefaultMethodOptimizationInfo.UNKNOWN_MAY_HAVE_SIDE_EFFECTS;
  private boolean returnValueOnlyDependsOnArguments =
      DefaultMethodOptimizationInfo.UNKNOWN_RETURN_VALUE_ONLY_DEPENDS_ON_ARGUMENTS;
  private boolean neverReturnsNull = DefaultMethodOptimizationInfo.UNKNOWN_NEVER_RETURNS_NULL;
  private boolean neverReturnsNormally =
      DefaultMethodOptimizationInfo.UNKNOWN_NEVER_RETURNS_NORMALLY;
  private boolean returnsConstantNumber = DefaultMethodOptimizationInfo.UNKNOWN_RETURNS_CONSTANT;
  private long returnedConstantNumber =
      DefaultMethodOptimizationInfo.UNKNOWN_RETURNED_CONSTANT_NUMBER;
  private boolean returnsConstantString = DefaultMethodOptimizationInfo.UNKNOWN_RETURNS_CONSTANT;
  private DexString returnedConstantString =
      DefaultMethodOptimizationInfo.UNKNOWN_RETURNED_CONSTANT_STRING;
  private TypeLatticeElement returnsObjectOfType = UNKNOWN_TYPE;
  private InlinePreference inlining = InlinePreference.Default;
  private boolean useIdentifierNameString =
      DefaultMethodOptimizationInfo.DOES_NOT_USE_IDNETIFIER_NAME_STRING;
  private boolean checksNullReceiverBeforeAnySideEffect =
      DefaultMethodOptimizationInfo.UNKNOWN_CHECKS_NULL_RECEIVER_BEFORE_ANY_SIDE_EFFECT;
  private boolean triggersClassInitBeforeAnySideEffect =
      DefaultMethodOptimizationInfo.UNKNOWN_TRIGGERS_CLASS_INIT_BEFORE_ANY_SIDE_EFFECT;
  // Stores information about instance methods and constructors for
  // class inliner, null value indicates that the method is not eligible.
  private ClassInlinerEligibility classInlinerEligibility =
      DefaultMethodOptimizationInfo.UNKNOWN_CLASS_INLINER_ELIGIBILITY;
  private TrivialInitializer trivialInitializerInfo =
      DefaultMethodOptimizationInfo.UNKNOWN_TRIVIAL_INITIALIZER;
  private boolean initializerEnablingJavaAssertions =
      DefaultMethodOptimizationInfo.UNKNOWN_INITIALIZER_ENABLING_JAVA_ASSERTIONS;
  private ParameterUsagesInfo parametersUsages =
      DefaultMethodOptimizationInfo.UNKNOWN_PARAMETER_USAGE_INFO;
  // Stores information about nullability hint per parameter. If set, that means, the method
  // somehow (e.g., null check, such as arg != null, or using checkParameterIsNotNull) ensures
  // the corresponding parameter is not null, or throws NPE before any other side effects.
  // This info is used by {@link UninstantiatedTypeOptimization#rewriteInvoke} that replaces an
  // invocation with null throwing code if an always-null argument is passed. Also used by Inliner
  // to give a credit to null-safe code, e.g., Kotlin's null safe argument.
  // Note that this bit set takes into account the receiver for instance methods.
  private BitSet nonNullParamOrThrow = null;
  // Stores information about nullability facts per parameter. If set, that means, the method
  // somehow (e.g., null check, such as arg != null, or NPE-throwing instructions such as array
  // access or another invocation) ensures the corresponding parameter is not null, and that is
  // guaranteed until the normal exits. That is, if the invocation of this method is finished
  // normally, the recorded parameter is definitely not null. These facts are used to propagate
  // non-null information through {@link NonNullTracker}.
  // Note that this bit set takes into account the receiver for instance methods.
  private BitSet nonNullParamOnNormalExits = null;
  private boolean reachabilitySensitive = false;
  private boolean returnValueHasBeenPropagated = false;

  UpdatableMethodOptimizationInfo() {
    // Intentionally left empty, just use the default values.
  }

  private UpdatableMethodOptimizationInfo(UpdatableMethodOptimizationInfo template) {
    cannotBeKept = template.cannotBeKept;
    returnedArgument = template.returnedArgument;
    neverReturnsNull = template.neverReturnsNull;
    neverReturnsNormally = template.neverReturnsNormally;
    returnsConstantNumber = template.returnsConstantNumber;
    returnedConstantNumber = template.returnedConstantNumber;
    returnsConstantString = template.returnsConstantString;
    returnedConstantString = template.returnedConstantString;
    inlining = template.inlining;
    useIdentifierNameString = template.useIdentifierNameString;
    checksNullReceiverBeforeAnySideEffect = template.checksNullReceiverBeforeAnySideEffect;
    triggersClassInitBeforeAnySideEffect = template.triggersClassInitBeforeAnySideEffect;
    classInlinerEligibility = template.classInlinerEligibility;
    trivialInitializerInfo = template.trivialInitializerInfo;
    initializerEnablingJavaAssertions = template.initializerEnablingJavaAssertions;
    parametersUsages = template.parametersUsages;
    nonNullParamOrThrow = template.nonNullParamOrThrow;
    nonNullParamOnNormalExits = template.nonNullParamOnNormalExits;
    reachabilitySensitive = template.reachabilitySensitive;
  }

  @Override
  public boolean cannotBeKept() {
    return cannotBeKept;
  }

  public void markCannotBeKept() {
    cannotBeKept = true;
  }

  @Override
  public boolean classInitializerMayBePostponed() {
    return classInitializerMayBePostponed;
  }

  public void markClassInitializerMayBePostponed() {
    classInitializerMayBePostponed = true;
  }

  @Override
  public TypeLatticeElement getDynamicReturnType() {
    return returnsObjectOfType;
  }

  @Override
  public Set<DexType> getInitializedClassesOnNormalExit() {
    return initializedClassesOnNormalExit;
  }

  @Override
  public TrivialInitializer getTrivialInitializerInfo() {
    return trivialInitializerInfo;
  }

  @Override
  public ParameterUsage getParameterUsages(int parameter) {
    return parametersUsages == null ? null : parametersUsages.getParameterUsage(parameter);
  }

  @Override
  public BitSet getNonNullParamOrThrow() {
    return nonNullParamOrThrow;
  }

  @Override
  public BitSet getNonNullParamOnNormalExits() {
    return nonNullParamOnNormalExits;
  }

  @Override
  public boolean hasBeenInlinedIntoSingleCallSite() {
    return hasBeenInlinedIntoSingleCallSite;
  }

  public void markInlinedIntoSingleCallSite() {
    hasBeenInlinedIntoSingleCallSite = true;
  }

  @Override
  public boolean isReachabilitySensitive() {
    return reachabilitySensitive;
  }

  @Override
  public boolean returnsArgument() {
    return returnedArgument != -1;
  }

  @Override
  public int getReturnedArgument() {
    assert returnsArgument();
    return returnedArgument;
  }

  @Override
  public boolean neverReturnsNull() {
    return neverReturnsNull;
  }

  @Override
  public boolean neverReturnsNormally() {
    return neverReturnsNormally;
  }

  @Override
  public boolean returnsConstant() {
    assert !(returnsConstantNumber && returnsConstantString);
    return returnsConstantNumber || returnsConstantString;
  }

  @Override
  public boolean returnsConstantNumber() {
    return returnsConstantNumber;
  }

  @Override
  public boolean returnsConstantString() {
    return returnsConstantString;
  }

  @Override
  public ClassInlinerEligibility getClassInlinerEligibility() {
    return classInlinerEligibility;
  }

  @Override
  public long getReturnedConstantNumber() {
    assert returnsConstant();
    return returnedConstantNumber;
  }

  @Override
  public DexString getReturnedConstantString() {
    assert returnsConstant();
    return returnedConstantString;
  }

  @Override
  public boolean isInitializerEnablingJavaAssertions() {
    return initializerEnablingJavaAssertions;
  }

  @Override
  public boolean useIdentifierNameString() {
    return useIdentifierNameString;
  }

  @Override
  public boolean forceInline() {
    return inlining == InlinePreference.ForceInline;
  }

  @Override
  public boolean neverInline() {
    return inlining == InlinePreference.NeverInline;
  }

  @Override
  public boolean checksNullReceiverBeforeAnySideEffect() {
    return checksNullReceiverBeforeAnySideEffect;
  }

  @Override
  public boolean triggersClassInitBeforeAnySideEffect() {
    return triggersClassInitBeforeAnySideEffect;
  }

  @Override
  public boolean mayHaveSideEffects() {
    return mayHaveSideEffects;
  }

  @Override
  public boolean returnValueOnlyDependsOnArguments() {
    return returnValueOnlyDependsOnArguments;
  }

  public void setParameterUsages(ParameterUsagesInfo parametersUsages) {
    this.parametersUsages = parametersUsages;
  }

  public void setNonNullParamOrThrow(BitSet facts) {
    this.nonNullParamOrThrow = facts;
  }

  public void setNonNullParamOnNormalExits(BitSet facts) {
    this.nonNullParamOnNormalExits = facts;
  }

  public void setReachabilitySensitive(boolean reachabilitySensitive) {
    this.reachabilitySensitive = reachabilitySensitive;
  }

  public void setClassInlinerEligibility(ClassInlinerEligibility eligibility) {
    this.classInlinerEligibility = eligibility;
  }

  public void setTrivialInitializer(TrivialInitializer info) {
    this.trivialInitializerInfo = info;
  }

  public void setInitializerEnablingJavaAssertions() {
    this.initializerEnablingJavaAssertions = true;
  }

  public void markInitializesClassesOnNormalExit(Set<DexType> initializedClassesOnNormalExit) {
    this.initializedClassesOnNormalExit = initializedClassesOnNormalExit;
  }

  public void markReturnsArgument(int argument) {
    assert argument >= 0;
    assert returnedArgument == -1 || returnedArgument == argument;
    returnedArgument = argument;
  }

  public void markMayNotHaveSideEffects() {
    mayHaveSideEffects = false;
  }

  public void markReturnValueOnlyDependsOnArguments() {
    returnValueOnlyDependsOnArguments = true;
  }

  public void markNeverReturnsNull() {
    neverReturnsNull = true;
  }

  public void markNeverReturnsNormally() {
    neverReturnsNormally = true;
  }

  public void markReturnsConstantNumber(long value) {
    assert !returnsConstantString;
    assert !returnsConstantNumber || returnedConstantNumber == value
        : "return constant number changed from " + returnedConstantNumber + " to " + value;
    returnsConstantNumber = true;
    returnedConstantNumber = value;
  }

  public void markReturnsConstantString(DexString value) {
    assert !returnsConstantNumber;
    assert !returnsConstantString || returnedConstantString == value
        : "return constant string changed from " + returnedConstantString + " to " + value;
    returnsConstantString = true;
    returnedConstantString = value;
  }

  public void markReturnsObjectOfType(AppView<?> appView, TypeLatticeElement type) {
    assert type != null;
    // We may get more precise type information if the method is reprocessed (e.g., due to
    // optimization info collected from all call sites), and hence the `returnsObjectOfType` is
    // allowed to become more precise.
    assert returnsObjectOfType == UNKNOWN_TYPE || type.lessThanOrEqual(returnsObjectOfType, appView)
        : "return type changed from " + returnsObjectOfType + " to " + type;
    returnsObjectOfType = type;
  }

  public void markForceInline() {
    // For concurrent scenarios we should allow the flag to be already set
    assert inlining == InlinePreference.Default || inlining == InlinePreference.ForceInline;
    inlining = InlinePreference.ForceInline;
  }

  public void unsetForceInline() {
    // For concurrent scenarios we should allow the flag to be already unset
    assert inlining == InlinePreference.Default || inlining == InlinePreference.ForceInline;
    inlining = InlinePreference.Default;
  }

  public void markNeverInline() {
    // For concurrent scenarios we should allow the flag to be already set
    assert inlining == InlinePreference.Default || inlining == InlinePreference.NeverInline;
    inlining = InlinePreference.NeverInline;
  }

  public void markUseIdentifierNameString() {
    useIdentifierNameString = true;
  }

  public void markCheckNullReceiverBeforeAnySideEffect(boolean mark) {
    checksNullReceiverBeforeAnySideEffect = mark;
  }

  public void markTriggerClassInitBeforeAnySideEffect(boolean mark) {
    triggersClassInitBeforeAnySideEffect = mark;
  }

  public void markAsPropagated() {
    returnValueHasBeenPropagated = true;
  }

  @Override
  public boolean returnValueHasBeenPropagated() {
    return returnValueHasBeenPropagated;
  }

  @Override
  public UpdatableMethodOptimizationInfo mutableCopy() {
    assert this != DefaultMethodOptimizationInfo.DEFAULT_INSTANCE;
    return new UpdatableMethodOptimizationInfo(this);
  }
}
