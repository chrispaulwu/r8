// Copyright (c) 2019, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.shaking;

import com.android.tools.r8.experimental.graphinfo.GraphConsumer;
import com.android.tools.r8.graph.AppInfoWithClassHierarchy;
import com.android.tools.r8.graph.AppView;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.SubtypingInfo;
import com.android.tools.r8.shaking.Enqueuer.Mode;
import java.util.Set;

public class EnqueuerFactory {

  public static Enqueuer createForInitialTreeShaking(
      AppView<? extends AppInfoWithClassHierarchy> appView,
      SubtypingInfo subtypingInfo) {
    return new Enqueuer(
        appView, subtypingInfo, null, Mode.INITIAL_TREE_SHAKING, MainDexTracingResult.NONE);
  }

  public static Enqueuer createForFinalTreeShaking(
      AppView<? extends AppInfoWithClassHierarchy> appView,
      SubtypingInfo subtypingInfo,
      GraphConsumer keptGraphConsumer,
      Set<DexType> initialPrunedTypes) {
    Enqueuer enqueuer =
        new Enqueuer(
            appView,
            subtypingInfo,
            keptGraphConsumer,
            Mode.FINAL_TREE_SHAKING,
            MainDexTracingResult.NONE);
    appView.withProtoShrinker(
        shrinker -> enqueuer.setInitialDeadProtoTypes(shrinker.getDeadProtoTypes()));
    enqueuer.setInitialPrunedTypes(initialPrunedTypes);
    return enqueuer;
  }

  public static Enqueuer createForInitialMainDexTracing(
      AppView<? extends AppInfoWithClassHierarchy> appView, SubtypingInfo subtypingInfo) {
    return new Enqueuer(
        appView, subtypingInfo, null, Mode.INITIAL_MAIN_DEX_TRACING, MainDexTracingResult.NONE);
  }

  public static Enqueuer createForFinalMainDexTracing(
      AppView<? extends AppInfoWithClassHierarchy> appView,
      SubtypingInfo subtypingInfo,
      GraphConsumer keptGraphConsumer,
      MainDexTracingResult previousMainDexTracingResult) {
    return new Enqueuer(
        appView,
        subtypingInfo,
        keptGraphConsumer,
        Mode.FINAL_MAIN_DEX_TRACING,
        previousMainDexTracingResult);
  }

  public static Enqueuer createForWhyAreYouKeeping(
      AppView<? extends AppInfoWithClassHierarchy> appView,
      SubtypingInfo subtypingInfo,
      GraphConsumer keptGraphConsumer) {
    return new Enqueuer(
        appView,
        subtypingInfo,
        keptGraphConsumer,
        Mode.WHY_ARE_YOU_KEEPING,
        MainDexTracingResult.NONE);
  }
}
