// Copyright (c) 2022, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.desugar.backports;

import com.android.tools.r8.CompilationMode;
import com.android.tools.r8.LibraryDesugaringTestConfiguration;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.desugar.desugaredlibrary.DesugaredLibraryTestBase;
import com.android.tools.r8.utils.BooleanUtils;
import com.android.tools.r8.utils.StringUtils;
import java.util.List;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameter;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class ThreadLocalBackportWithDesugaredLibraryTest extends DesugaredLibraryTestBase {

  @Parameter(0)
  public TestParameters parameters;

  @Parameter(1)
  public boolean shrinkDesugaredLibrary;

  @Parameter(2)
  public boolean traceReferencesKeepRules;

  @Parameters(name = "{0}, shrinkDesugaredLibrary: {1}, traceReferencesKeepRules {2}")
  public static List<Object[]> data() {
    return buildParameters(
        getTestParameters().withAllRuntimes().withAllApiLevelsAlsoForCf().build(),
        BooleanUtils.values(),
        BooleanUtils.values());
  }

  private static final String EXPECTED_OUTPUT = StringUtils.lines("Hello, world!");

  @Test
  public void testTimeD8() throws Exception {
    Assume.assumeTrue(parameters.getRuntime().isDex());
    Assume.assumeTrue(shrinkDesugaredLibrary || !traceReferencesKeepRules);

    testForD8()
        .addInnerClasses(getClass())
        .setMinApi(parameters.getApiLevel())
        .addLibraryFiles(getLibraryFile())
        .enableLibraryDesugaring(
            LibraryDesugaringTestConfiguration.builder()
                .setMinApi(parameters.getApiLevel())
                .withKeepRuleConsumer()
                .setMode(shrinkDesugaredLibrary ? CompilationMode.RELEASE : CompilationMode.DEBUG)
                .build())
        .compile()
        .run(parameters.getRuntime(), TestClass.class)
        .assertSuccessWithOutput(EXPECTED_OUTPUT);
  }

  @Test
  public void testTimeR8() throws Exception {
    Assume.assumeTrue(parameters.getRuntime().isDex());
    Assume.assumeTrue(shrinkDesugaredLibrary || !traceReferencesKeepRules);

    testForR8(parameters.getBackend())
        .addInnerClasses(getClass())
        .addKeepMainRule(TestClass.class)
        .setMinApi(parameters.getApiLevel())
        .addLibraryFiles(getLibraryFile())
        .enableLibraryDesugaring(
            LibraryDesugaringTestConfiguration.builder()
                .setMinApi(parameters.getApiLevel())
                .withKeepRuleConsumer()
                .setMode(shrinkDesugaredLibrary ? CompilationMode.RELEASE : CompilationMode.DEBUG)
                .build())
        .run(parameters.getRuntime(), TestClass.class)
        .assertSuccessWithOutput(EXPECTED_OUTPUT);
  }

  static class TestClass {

    public static void main(String[] args) {
      ThreadLocal<String> threadLocal = ThreadLocal.withInitial(() -> "Hello, world!");
      System.out.println(threadLocal.get());
    }
  }
}
