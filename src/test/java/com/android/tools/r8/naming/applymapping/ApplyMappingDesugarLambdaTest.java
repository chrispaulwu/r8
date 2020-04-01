package com.android.tools.r8.naming.applymapping;

import static com.android.tools.r8.Collectors.toSingle;
import static com.android.tools.r8.utils.codeinspector.Matchers.isRenamed;
import static junit.framework.TestCase.assertEquals;
import static org.hamcrest.CoreMatchers.anyOf;
import static org.hamcrest.CoreMatchers.containsString;
import static org.hamcrest.MatcherAssert.assertThat;

import com.android.tools.r8.CompilationFailedException;
import com.android.tools.r8.R8TestCompileResult;
import com.android.tools.r8.TestBase;
import com.android.tools.r8.TestParameters;
import com.android.tools.r8.TestParametersCollection;
import com.android.tools.r8.utils.FileUtils;
import com.android.tools.r8.utils.codeinspector.FoundClassSubject;
import java.io.IOException;
import java.nio.file.Path;
import java.util.concurrent.ExecutionException;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

// This is a reproduction of b/152715309.
@RunWith(Parameterized.class)
public class ApplyMappingDesugarLambdaTest extends TestBase {

  private final TestParameters parameters;
  private final String EXPECTED = "FOO";

  @Parameters(name = "{0}")
  public static TestParametersCollection data() {
    return getTestParameters().withDexRuntimes().withAllApiLevels().build();
  }

  public ApplyMappingDesugarLambdaTest(TestParameters parameters) {
    this.parameters = parameters;
  }

  @Test
  public void testR8() throws CompilationFailedException, IOException, ExecutionException {
    // Create a dictionary to control the naming.
    Path dictionary = temp.getRoot().toPath().resolve("dictionary.txt");
    FileUtils.writeTextFile(dictionary, "e");

    final String finalName = "com.android.tools.r8.naming.applymapping.e";

    R8TestCompileResult libraryResult =
        testForR8(parameters.getBackend())
            .addProgramClasses(A.class)
            .addKeepClassAndMembersRulesWithAllowObfuscation(A.class)
            .setMinApi(parameters.getApiLevel())
            .addKeepRules(
                "-keeppackagenames", "-classobfuscationdictionary " + dictionary.toString())
            .compile()
            .inspect(
                inspector -> {
                  assertThat(inspector.clazz(A.class), isRenamed());
                  assertEquals(finalName, inspector.clazz(A.class).getFinalName());
                });

    Path libraryPath = libraryResult.writeToZip();

    // Ensure that the library works as supposed.
    testForD8()
        .addProgramClasses(I.class, Main.class)
        .addClasspathFiles(libraryPath)
        .setMinApi(parameters.getApiLevel())
        .run(parameters.getRuntime(), Main.class, EXPECTED)
        .assertSuccessWithOutputLines(EXPECTED);

    testForR8(parameters.getBackend())
        .addClasspathClasses(A.class)
        .addProgramClasses(I.class, Main.class)
        .addKeepMainRule(Main.class)
        .addKeepClassAndMembersRules(I.class)
        .setMinApi(parameters.getApiLevel())
        .addApplyMapping(libraryResult.getProguardMap())
        .addOptionsModification(internalOptions -> internalOptions.enableClassInlining = false)
        .addKeepRules("-classobfuscationdictionary " + dictionary.toString())
        .compile()
        .inspect(
            inspector -> {
              // Assert that there is a lambda class created.
              assertEquals(3, inspector.allClasses().size());
              FoundClassSubject lambdaClass =
                  inspector.allClasses().stream()
                      .filter(FoundClassSubject::isSynthetic)
                      .collect(toSingle());
              // TODO(b/152715309): This should not be the case.
              assertEquals(finalName, lambdaClass.getFinalName());
            })
        .addRunClasspathFiles(libraryPath)
        .run(parameters.getRuntime(), Main.class, "Foo")
        // TODO(b/152715309): This test should run.
        .assertFailureWithErrorThatMatches(
            anyOf(
                containsString("java.lang.IllegalAccessError:"),
                containsString("java.lang.NoSuchMethodError: No direct method <init>")));
  }

  public static class A {

    A(int bar) {
      System.out.println(bar);
    }
  }

  @FunctionalInterface
  public interface I {

    void doStuff();
  }

  public static class Main {

    public static void main(String[] args) {
      processI(() -> System.out.println(args[0]));
    }

    public static void processI(I i) {
      i.doStuff();
    }
  }
}
