// Copyright (c) 2016, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.code;

import com.android.tools.r8.errors.InternalCompilerError;
import com.android.tools.r8.errors.Unreachable;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.ir.analysis.type.TypeLatticeElement;

public enum ValueType {
  OBJECT,
  INT,
  FLOAT,
  INT_OR_FLOAT,
  INT_OR_FLOAT_OR_NULL,
  LONG,
  DOUBLE,
  LONG_OR_DOUBLE;

  public boolean isObject() {
    return this == OBJECT;
  }

  public boolean isSingle() {
    return this == INT || this == FLOAT || this == INT_OR_FLOAT;
  }

  public boolean isWide() {
    return this == LONG || this == DOUBLE || this == LONG_OR_DOUBLE;
  }

  public boolean isObjectOrSingle() {
    return !isWide();
  }

  public boolean isPreciseType() {
    return this != ValueType.INT_OR_FLOAT
        && this != ValueType.LONG_OR_DOUBLE
        && this != ValueType.INT_OR_FLOAT_OR_NULL;
  }

  public ValueType meet(ValueType other) {
    if (this == other) {
      return this;
    }
    if (other == INT_OR_FLOAT_OR_NULL) {
      return other.meet(this);
    }
    if (isPreciseType() && other.isPreciseType()) {
      // Precise types must be identical, hitting the first check above.
      return null;
    }
    switch (this) {
      case OBJECT:
        {
          if (other.isObject()) {
            return this;
          }
          break;
        }
      case INT:
      case FLOAT:
        {
          if (other.isSingle()) {
            return this;
          }
          break;
        }
      case LONG:
      case DOUBLE:
        {
          if (other.isWide()) {
            return this;
          }
          break;
        }
      case INT_OR_FLOAT_OR_NULL:
        {
          if (other.isObjectOrSingle()) {
            return other;
          }
          break;
        }
      case INT_OR_FLOAT:
        {
          if (other.isSingle()) {
            return other;
          }
          break;
        }
      case LONG_OR_DOUBLE:
        {
          if (other.isWide()) {
            return other;
          }
          break;
        }
      default:
        throw new Unreachable("Unexpected value-type in meet: " + this);
    }
    return null;
  }

  public boolean verifyCompatible(ValueType other) {
    assert meet(other) != null;
    return true;
  }

  public int requiredRegisters() {
    return isWide() ? 2 : 1;
  }

  public static ValueType fromMemberType(MemberType type) {
    switch (type) {
      case BOOLEAN:
      case BYTE:
      case CHAR:
      case SHORT:
      case INT:
        return ValueType.INT;
      case FLOAT:
        return ValueType.FLOAT;
      case INT_OR_FLOAT:
        return ValueType.INT_OR_FLOAT;
      case LONG:
        return ValueType.LONG;
      case DOUBLE:
        return ValueType.DOUBLE;
      case LONG_OR_DOUBLE:
        return ValueType.LONG_OR_DOUBLE;
      case OBJECT:
        return ValueType.OBJECT;
      default:
        throw new Unreachable("Unexpected member type: " + type);
    }
  }

  public static ValueType fromTypeDescriptorChar(char descriptor) {
    switch (descriptor) {
      case 'L':
      case '[':
        return ValueType.OBJECT;
      case 'Z':
      case 'B':
      case 'S':
      case 'C':
      case 'I':
        return ValueType.INT;
      case 'F':
        return ValueType.FLOAT;
      case 'J':
        return ValueType.LONG;
      case 'D':
        return ValueType.DOUBLE;
      case 'V':
        throw new InternalCompilerError("No value type for void type.");
      default:
        throw new Unreachable("Invalid descriptor char '" + descriptor + "'");
    }
  }

  public static ValueType fromDexType(DexType type) {
    return fromTypeDescriptorChar((char) type.descriptor.content[0]);
  }

  public static ValueType fromNumericType(NumericType type) {
    switch (type) {
      case BYTE:
      case CHAR:
      case SHORT:
      case INT:
        return ValueType.INT;
      case FLOAT:
        return ValueType.FLOAT;
      case LONG:
        return ValueType.LONG;
      case DOUBLE:
        return ValueType.DOUBLE;
      default:
        throw new Unreachable("Invalid numeric type '" + type + "'");
    }
  }

  public static ValueType fromTypeLattice(TypeLatticeElement typeLatticeElement) {
    if (typeLatticeElement.isBottom()) {
      return INT_OR_FLOAT_OR_NULL;
    }
    if (typeLatticeElement.isReference()) {
      return OBJECT;
    }
    if (typeLatticeElement.isInt()) {
      return INT;
    }
    if (typeLatticeElement.isFloat()) {
      return FLOAT;
    }
    if (typeLatticeElement.isLong()) {
      return LONG;
    }
    if (typeLatticeElement.isDouble()) {
      return DOUBLE;
    }
    if (typeLatticeElement.isSingle()) {
      return INT_OR_FLOAT;
    }
    if (typeLatticeElement.isWide()) {
      return LONG_OR_DOUBLE;
    }
    throw new Unreachable("Invalid type lattice '" + typeLatticeElement + "'");
  }

  public TypeLatticeElement toTypeLattice() {
    switch (this) {
      case OBJECT:
        return TypeLatticeElement.REFERENCE;
      case INT:
        return TypeLatticeElement.INT;
      case FLOAT:
        return TypeLatticeElement.FLOAT;
      case INT_OR_FLOAT:
        return TypeLatticeElement.SINGLE;
      case LONG:
        return TypeLatticeElement.LONG;
      case DOUBLE:
        return TypeLatticeElement.DOUBLE;
      case LONG_OR_DOUBLE:
        return TypeLatticeElement.WIDE;
      case INT_OR_FLOAT_OR_NULL:
      default:
        return TypeLatticeElement.BOTTOM;
    }
  }
}
