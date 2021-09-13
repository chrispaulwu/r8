// Copyright (c) 2021, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.optimize.argumentpropagation;

import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexProgramClass;
import com.android.tools.r8.graph.MethodCollection;
import com.android.tools.r8.graph.RewrittenPrototypeDescription.ArgumentInfoCollection;
import com.android.tools.r8.shaking.AppInfoWithLiveness;
import com.android.tools.r8.utils.ThreadUtils;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;

/**
 * Takes as input a mapping from old method signatures to new method signatures (with parameters
 * removed), and rewrites all method definitions in the application to their new method signatures.
 */
public class ArgumentPropagatorApplicationFixer {

  private final AppView<AppInfoWithLiveness> appView;
  private final ArgumentPropagatorGraphLens graphLens;

  public ArgumentPropagatorApplicationFixer(
      AppView<AppInfoWithLiveness> appView, ArgumentPropagatorGraphLens graphLens) {
    this.appView = appView;
    this.graphLens = graphLens;
  }

  public void fixupApplication(
      Set<DexProgramClass> affectedClasses, ExecutorService executorService)
      throws ExecutionException {
    // If the graph lens is null, argument propagation did not lead to any parameter removals. In
    // this case there is no needed to fixup the program.
    if (graphLens == null) {
      assert affectedClasses.isEmpty();
      return;
    }

    assert !affectedClasses.isEmpty();

    ThreadUtils.processItems(affectedClasses, this::fixupClass, executorService);
    appView.rewriteWithLens(graphLens);
  }

  private void fixupClass(DexProgramClass clazz) {
    MethodCollection methodCollection = clazz.getMethodCollection();
    methodCollection.replaceMethods(
        method -> {
          DexMethod methodReferenceBeforeParameterRemoval = method.getReference();
          DexMethod methodReferenceAfterParameterRemoval =
              graphLens.internalGetNextMethodSignature(methodReferenceBeforeParameterRemoval);
          if (methodReferenceAfterParameterRemoval == methodReferenceBeforeParameterRemoval) {
            return method;
          }

          return method.toTypeSubstitutedMethod(
              methodReferenceAfterParameterRemoval,
              builder -> {
                ArgumentInfoCollection removedParameters =
                    graphLens.getRemovedParameters(methodReferenceAfterParameterRemoval);
                builder
                    .apply(removedParameters.createParameterAnnotationsRemover(method))
                    .fixupOptimizationInfo(
                        appView, removedParameters.createMethodOptimizationInfoFixer());
              });
        });
  }
}