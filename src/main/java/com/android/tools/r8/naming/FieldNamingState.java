// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.naming;


import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexEncodedField;
import com.android.tools.r8.graph.DexField;
import com.android.tools.r8.graph.DexString;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.naming.FieldNamingState.InternalState;
import java.util.IdentityHashMap;
import java.util.Map;

public class FieldNamingState extends FieldNamingStateBase<InternalState> implements Cloneable {

  private final ReservedFieldNamingState reservedNames;
  private final MemberNamingStrategy strategy;

  public FieldNamingState(AppView<?> appView, MemberNamingStrategy strategy) {
    this(appView, strategy, new ReservedFieldNamingState(appView));
  }

  public FieldNamingState(
      AppView<?> appView, MemberNamingStrategy strategy, ReservedFieldNamingState reservedNames) {
    this(appView, strategy, reservedNames, new IdentityHashMap<>());
  }

  private FieldNamingState(
      AppView<?> appView,
      MemberNamingStrategy strategy,
      ReservedFieldNamingState reservedNames,
      Map<DexType, InternalState> internalStates) {
    super(appView, internalStates);
    this.reservedNames = reservedNames;
    this.strategy = strategy;
  }

  public FieldNamingState createChildState(ReservedFieldNamingState reservedNames) {
    FieldNamingState childState =
        new FieldNamingState(appView, strategy, reservedNames, internalStates);
    childState.includeReservations(this.reservedNames);
    return childState;
  }

  public DexString getOrCreateNameFor(DexField field) {
    DexEncodedField encodedField = appView.appInfo().resolveField(field);
    if (encodedField != null) {
      DexClass clazz = appView.definitionFor(encodedField.field.holder);
      if (clazz == null || clazz.isLibraryClass()) {
        return field.name;
      }
      if (!appView.options().getProguardConfiguration().hasApplyMappingFile()
          && appView.rootSet().mayNotBeMinified(encodedField.field, appView)) {
        return field.name;
      }
    }
    return getOrCreateInternalState(field).createNewName(field);
  }

  public void includeReservations(ReservedFieldNamingState reservedNames) {
    this.reservedNames.includeReservations(reservedNames);
  }

  @Override
  public InternalState createInternalState() {
    return new InternalState();
  }

  @Override
  public FieldNamingState clone() {
    Map<DexType, InternalState> internalStatesClone = new IdentityHashMap<>();
    for (Map.Entry<DexType, InternalState> entry : internalStates.entrySet()) {
      internalStatesClone.put(entry.getKey(), entry.getValue().clone());
    }
    return new FieldNamingState(appView, strategy, reservedNames, internalStatesClone);
  }

  class InternalState implements InternalNamingState, Cloneable {

    private int dictionaryIndex;
    private int nextNameIndex;

    public InternalState() {
      this(1, 0);
    }

    public InternalState(int nextNameIndex, int dictionaryIndex) {
      this.dictionaryIndex = dictionaryIndex;
      this.nextNameIndex = nextNameIndex;
    }

    public DexString createNewName(DexField field) {
      DexString name;
      do {
        name = strategy.next(field, this);
      } while (reservedNames.isReserved(name, field.type)
          && !strategy.breakOnNotAvailable(field, name));
      return name;
    }

    @Override
    public InternalState clone() {
      return new InternalState(nextNameIndex, dictionaryIndex);
    }

    @Override
    public int getDictionaryIndex() {
      return dictionaryIndex;
    }

    @Override
    public int incrementDictionaryIndex() {
      return dictionaryIndex++;
    }

    @Override
    public int incrementNameIndex(boolean isDirectMethodCall) {
      assert !isDirectMethodCall;
      return nextNameIndex++;
    }
  }
}
