// Copyright (c) 2022, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.
package com.android.tools.r8.desugar.desugaredlibrary;

import com.android.tools.r8.TestParameters;
import com.android.tools.r8.utils.BooleanUtils;
import com.android.tools.r8.utils.StringUtils;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

// This is a reproduction for b/202188674.
@RunWith(Parameterized.class)
public class TelemetryMapInterfaceTest extends DesugaredLibraryTestBase {

  private static final String EXPECTED_OUTPUT =
      StringUtils.lines("k=key1;v=value1", "k=key1;v=value1", "k=key1;v=value1", "k=key1;v=value1");
  private final TestParameters parameters;
  private final boolean shrinkDesugaredLibrary;

  @Parameters(name = "{0}, shrinkDesugaredLibrary: {1}")
  public static List<Object[]> data() {
    return buildParameters(
        getTestParameters().withDexRuntimes().withAllApiLevels().build(), BooleanUtils.values());
  }

  public TelemetryMapInterfaceTest(TestParameters parameters, boolean b) {
    this.parameters = parameters;
    this.shrinkDesugaredLibrary = b;
  }

  @Test
  public void testMap() throws Throwable {
    KeepRuleConsumer keepRuleConsumer = createKeepRuleConsumer(parameters);
    testForD8()
        .addLibraryFiles(getLibraryFile())
        .addInnerClasses(getClass())
        .setMinApi(parameters.getApiLevel())
        .enableCoreLibraryDesugaring(parameters.getApiLevel(), keepRuleConsumer)
        .compile()
        .addDesugaredCoreLibraryRunClassPath(
            this::buildDesugaredLibrary,
            parameters.getApiLevel(),
            keepRuleConsumer.get(),
            shrinkDesugaredLibrary)
        .run(parameters.getRuntime(), Main.class)
        .assertSuccessWithOutput(EXPECTED_OUTPUT);
  }

  static class Main {

    public static void main(String[] args) {
      Concrete concrete = new Concrete();
      concrete.put("key1", "value1");
      concrete.forEach((k, v) -> System.out.println("k=" + k + ";v=" + v));
      MapInterface mapInterface = concrete;
      mapInterface.forEach((k, v) -> System.out.println("k=" + k + ";v=" + v));
      HashMap<String, Object> hashMap = concrete;
      hashMap.forEach((k, v) -> System.out.println("k=" + k + ";v=" + v));
      Map<String, Object> map = concrete;
      map.forEach((k, v) -> System.out.println("k=" + k + ";v=" + v));
    }
  }

  interface MapInterface {

    void forEach(BiConsumer<? super String, ? super Object> consumer);
  }

  static class Concrete extends HashMap<String, Object> implements MapInterface {}
}
