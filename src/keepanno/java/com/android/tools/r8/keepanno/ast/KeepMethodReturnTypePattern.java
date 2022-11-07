// Copyright (c) 2022, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.keepanno.ast;

import java.util.function.Function;
import java.util.function.Supplier;

public abstract class KeepMethodReturnTypePattern {

  private static final SomeType ANY_TYPE_INSTANCE = new SomeType(KeepTypePattern.any());

  public static KeepMethodReturnTypePattern any() {
    return ANY_TYPE_INSTANCE;
  }

  public static KeepMethodReturnTypePattern voidType() {
    return VoidType.getInstance();
  }

  public abstract <T> T match(Supplier<T> onVoid, Function<KeepTypePattern, T> onType);

  public boolean isAny() {
    return match(() -> false, KeepTypePattern::isAny);
  }

  private static class VoidType extends KeepMethodReturnTypePattern {
    private static final VoidType INSTANCE = new VoidType();

    public static VoidType getInstance() {
      return INSTANCE;
    }

    @Override
    public <T> T match(Supplier<T> onVoid, Function<KeepTypePattern, T> onType) {
      return onVoid.get();
    }

    @Override
    public boolean equals(Object obj) {
      return this == obj;
    }

    @Override
    public int hashCode() {
      return System.identityHashCode(this);
    }

    @Override
    public String toString() {
      return "void";
    }
  }

  private static class SomeType extends KeepMethodReturnTypePattern {

    private final KeepTypePattern typePattern;

    private SomeType(KeepTypePattern typePattern) {
      assert typePattern != null;
      this.typePattern = typePattern;
    }

    @Override
    public <T> T match(Supplier<T> onVoid, Function<KeepTypePattern, T> onType) {
      return onType.apply(typePattern);
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      SomeType someType = (SomeType) o;
      return typePattern.equals(someType.typePattern);
    }

    @Override
    public int hashCode() {
      return typePattern.hashCode();
    }

    @Override
    public String toString() {
      return typePattern.toString();
    }
  }
}