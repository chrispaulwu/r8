// Copyright (c) 2023, the R8 project authors. Please see the AUTHORS file
// for details. All rights reserved. Use of this source code is governed by a
// BSD-style license that can be found in the LICENSE file.

package com.android.tools.r8.ir.desugar.desugaredlibrary.lint;

import com.android.tools.r8.graph.CfCode.LocalVariableInfo;
import com.android.tools.r8.graph.ClassAccessFlags;
import com.android.tools.r8.graph.DexClass;
import com.android.tools.r8.graph.DexEncodedField;
import com.android.tools.r8.graph.DexEncodedMethod;
import com.android.tools.r8.graph.DexMethod;
import com.android.tools.r8.graph.DexProto;
import com.android.tools.r8.graph.DexType;
import com.android.tools.r8.graph.FieldAccessFlags;
import com.android.tools.r8.graph.MethodAccessFlags;
import com.android.tools.r8.ir.desugar.desugaredlibrary.lint.SupportedMethodsWithAnnotations.ClassAnnotation;
import com.android.tools.r8.ir.desugar.desugaredlibrary.lint.SupportedMethodsWithAnnotations.MethodAnnotation;
import com.android.tools.r8.utils.AndroidApiLevel;
import java.io.PrintStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.StreamSupport;

public class GenerateHtmlDoc extends AbstractGenerateFiles {

  private static final String HTML_SPLIT = "<br>&nbsp;";
  private static final int MAX_LINE_CHARACTERS = 53;
  private static final String SUP_1 = "<sup>1</sup>";
  private static final String SUP_2 = "<sup>2</sup>";
  private static final String SUP_3 = "<sup>3</sup>";
  private static final String SUP_4 = "<sup>4</sup>";

  public GenerateHtmlDoc(
      String desugarConfigurationPath, String desugarImplementationPath, String outputDirectory)
      throws Exception {
    super(desugarConfigurationPath, desugarImplementationPath, outputDirectory);
  }

  private static class StringBuilderWithIndent {

    String NL = System.lineSeparator();
    StringBuilder builder = new StringBuilder();
    String indent = "";

    StringBuilderWithIndent() {}

    StringBuilderWithIndent indent(String indent) {
      this.indent = indent;
      return this;
    }

    StringBuilderWithIndent appendLineStart(String lineStart) {
      builder.append(indent);
      builder.append(lineStart);
      return this;
    }

    StringBuilderWithIndent append(String string) {
      builder.append(string);
      return this;
    }

    StringBuilderWithIndent appendLineEnd(String lineEnd) {
      builder.append(lineEnd);
      builder.append(NL);
      return this;
    }

    StringBuilderWithIndent appendLine(String line) {
      builder.append(indent);
      builder.append(line);
      builder.append(NL);
      return this;
    }

    StringBuilderWithIndent emptyLine() {
      builder.append(NL);
      return this;
    }

    @Override
    public String toString() {
      return builder.toString();
    }
  }

  private abstract static class SourceBuilder<B extends GenerateHtmlDoc.SourceBuilder> {

    protected final DexClass clazz;
    protected List<DexEncodedField> fields = new ArrayList<>();
    protected Map<DexEncodedMethod, MethodAnnotation> constructors =
        new TreeMap<>(Comparator.comparing(DexEncodedMethod::getReference));
    protected Map<DexEncodedMethod, MethodAnnotation> methods =
        new TreeMap<>(Comparator.comparing(DexEncodedMethod::getReference));

    String className;
    String packageName;

    private SourceBuilder(DexClass clazz) {
      this.clazz = clazz;
      this.className = clazz.type.toSourceString();
      int index = this.className.lastIndexOf('.');
      this.packageName = index > 0 ? this.className.substring(0, index) : "";
    }

    public abstract B self();

    private B addField(DexEncodedField field) {
      fields.add(field);
      return self();
    }

    private B addMethod(DexEncodedMethod method, MethodAnnotation methodAnnotation) {
      assert !method.isClassInitializer();
      if (method.isInitializer()) {
        constructors.put(method, methodAnnotation);
      } else {
        methods.put(method, methodAnnotation);
      }
      return self();
    }

    // If we are in a.b.c, then anything starting with a.b should not be fully qualified.
    protected String typeInPackageRecursive(String typeName, String packageName) {
      String rewritten = typeInPackage(typeName, packageName);
      if (rewritten != null) {
        return rewritten;
      }
      String[] split = packageName.split("\\.");
      if (split.length > 2) {
        String prevPackage =
            packageName.substring(0, packageName.length() - split[split.length - 1].length() - 1);
        return typeInPackage(typeName, prevPackage);
      }
      return null;
    }

    protected String typeInPackage(String typeName, String packageName) {
      if (typeName.startsWith(packageName)
          && typeName.length() > packageName.length()
          && typeName.charAt(packageName.length()) == '.') {
        int last = typeName.lastIndexOf('.') + 1;
        return typeName.substring(last);
      }
      return null;
    }

    protected String typeInPackage(String typeName) {
      String result = typeInPackageRecursive(typeName, packageName);
      if (result == null) {
        result = typeInPackage(typeName, "java.lang");
      }
      if (result == null) {
        result = typeInPackage(typeName, "java.util.function");
      }
      if (result == null) {
        result = typeName;
      }
      return result.replace('$', '.');
    }

    protected String typeInPackage(DexType type) {
      if (type.isPrimitiveType()) {
        return type.toSourceString();
      }
      return typeInPackage(type.toSourceString());
    }

    protected String accessFlags(ClassAccessFlags accessFlags) {
      List<String> flags = new ArrayList<>();
      if (accessFlags.isPublic()) {
        flags.add("public");
      }
      if (accessFlags.isProtected()) {
        flags.add("protected");
      }
      if (accessFlags.isPrivate()) {
        assert false;
        flags.add("private");
      }
      if (accessFlags.isPackagePrivate()) {
        assert false;
        flags.add("/* package */");
      }
      if (accessFlags.isAbstract() && !accessFlags.isInterface()) {
        flags.add("abstract");
      }
      if (accessFlags.isStatic()) {
        flags.add("static");
      }
      if (accessFlags.isFinal()) {
        flags.add("final");
      }
      return String.join(" ", flags);
    }

    protected String accessFlags(FieldAccessFlags accessFlags) {
      List<String> flags = new ArrayList<>();
      if (accessFlags.isPublic()) {
        flags.add("public");
      }
      if (accessFlags.isProtected()) {
        flags.add("protected");
      }
      if (accessFlags.isPrivate()) {
        assert false;
        flags.add("private");
      }
      if (accessFlags.isPackagePrivate()) {
        assert false;
        flags.add("/* package */");
      }
      if (accessFlags.isStatic()) {
        flags.add("static");
      }
      if (accessFlags.isFinal()) {
        flags.add("final");
      }
      return String.join(" ", flags);
    }

    protected String accessFlags(MethodAccessFlags accessFlags) {
      List<String> flags = new ArrayList<>();
      if (accessFlags.isPublic()) {
        flags.add("public");
      }
      if (accessFlags.isProtected()) {
        flags.add("protected");
      }
      if (accessFlags.isPrivate()) {
        assert false;
        flags.add("private");
      }
      if (accessFlags.isPackagePrivate()) {
        assert false;
        flags.add("/* package */");
      }
      if (accessFlags.isAbstract()) {
        flags.add("abstract");
      }
      if (accessFlags.isStatic()) {
        flags.add("static");
      }
      if (accessFlags.isFinal()) {
        flags.add("final");
      }
      return String.join(" ", flags);
    }

    public String arguments(DexEncodedMethod method) {
      DexProto proto = method.getReference().proto;
      StringBuilder argsBuilder = new StringBuilder();
      boolean firstArg = true;
      int argIndex = method.isVirtualMethod() || method.accessFlags.isConstructor() ? 1 : 0;
      int argNumber = 0;
      argsBuilder.append("(");
      for (DexType type : proto.parameters.values) {
        if (!firstArg) {
          argsBuilder.append(", ");
        }
        if (method.hasCode()) {
          String name = "p" + argNumber;
          for (LocalVariableInfo localVariable : method.getCode().asCfCode().getLocalVariables()) {
            if (localVariable.getIndex() == argIndex) {
              assert !localVariable.getLocal().name.toString().equals("this");
              name = localVariable.getLocal().name.toString();
            }
          }
          argsBuilder.append(typeInPackage(type)).append(" ").append(name);
        } else {
          argsBuilder.append(typeInPackage(type)).append(" p").append(argNumber);
        }
        firstArg = false;
        argIndex += type.isWideType() ? 2 : 1;
        argNumber++;
      }
      argsBuilder.append(")");
      return argsBuilder.toString();
    }
  }

  private static class HTMLBuilder extends StringBuilderWithIndent {

    private String indent = "";

    private void increaseIndent() {
      indent += "  ";
      indent(indent);
    }

    private void decreaseIndent() {
      indent = indent.substring(0, indent.length() - 2);
      indent(indent);
    }

    HTMLBuilder appendTdPackage(String s) {
      String finalString = format(s, 4);
      appendLineStart("<td><code><em>" + finalString + "</em></code><br>");
      if (s.startsWith("java.time")) {
        append("<a href=\"#java-time-customizations\">See customizations</a><br");
      } else if (s.startsWith("java.nio")) {
        append("<a href=\"#java-nio-customizations\">See customizations</a><br");
      }
      return this;
    }

    private String format(String s, int i) {
      String[] regexpSplit = s.split("\\.");
      if (regexpSplit.length < i) {
        return s;
      }
      int splitIndex = 0;
      int mid = i / 2;
      for (int j = 0; j < mid; j++) {
        splitIndex += regexpSplit[j].length();
      }
      splitIndex += mid;
      return s.substring(0, splitIndex) + HTML_SPLIT + s.substring(splitIndex);
    }

    HTMLBuilder appendTdClassName(String s) {
      String finalString = format(s, 2);
      appendLineEnd(
          "<code><br><br><div style=\"font-size:small;font-weight:bold;\">&nbsp;"
              + finalString
              + "</div></code><br><br></td>");
      return this;
    }

    HTMLBuilder appendTdP(String s) {
      appendLine("<td><p>" + s + "</p></td>");
      return this;
    }

    HTMLBuilder appendLiCode(String s) {
      appendLine("<li class=\"java8_table\"><code>" + s + "</code></li>");
      return this;
    }

    HTMLBuilder appendMethodLiCode(String s) {
      if (s.length() < MAX_LINE_CHARACTERS || s.contains("()")) {
        return appendLiCode(s);
      }
      StringBuilder sb = new StringBuilder();
      String[] split = s.split("\\(");
      sb.append(split[0]).append('(').append(HTML_SPLIT);
      if (split[1].length() < MAX_LINE_CHARACTERS - 2) {
        sb.append(split[1]);
        return appendLiCode(sb.toString());
      }
      String[] secondSplit = split[1].split(",");
      sb.append("&nbsp;");
      for (int i = 0; i < secondSplit.length; i++) {
        sb.append(secondSplit[i]);
        if (i != secondSplit.length - 1) {
          sb.append(',');
          sb.append(HTML_SPLIT);
        }
      }
      return appendLiCode(sb.toString());
    }

    HTMLBuilder start(String tag) {
      appendLine("<" + tag + ">");
      increaseIndent();
      return this;
    }

    HTMLBuilder end(String tag) {
      decreaseIndent();
      appendLine("</" + tag + ">");
      return this;
    }
  }

  public static class HTMLSourceBuilder extends SourceBuilder<HTMLSourceBuilder> {

    private final ClassAnnotation classAnnotation;
    private boolean parallelStreamMethod = false;
    private boolean missingFromLatestAndroidJar = false;
    private boolean unsupportedInMinApiRange = false;
    private boolean covariantReturnSupported = false;

    public HTMLSourceBuilder(DexClass clazz, ClassAnnotation classAnnotation) {
      super(clazz);
      this.classAnnotation = classAnnotation;
    }

    @Override
    public HTMLSourceBuilder self() {
      return this;
    }

    private String getTextAnnotations(MethodAnnotation annotation) {
      if (annotation == null) {
        return "";
      }
      StringBuilder stringBuilder = new StringBuilder();
      if (annotation.parallelStreamMethod) {
        stringBuilder.append(SUP_1);
        parallelStreamMethod = true;
      }
      if (annotation.missingFromLatestAndroidJar) {
        stringBuilder.append(SUP_2);
        missingFromLatestAndroidJar = true;
      }
      if (annotation.unsupportedInMinApiRange) {
        stringBuilder.append(SUP_3);
        unsupportedInMinApiRange = true;
      }
      if (annotation.covariantReturnSupported) {
        stringBuilder.append(SUP_4);
        covariantReturnSupported = true;
      }
      return stringBuilder.toString();
    }

    @Override
    public String toString() {
      HTMLBuilder builder = new HTMLBuilder();
      builder.start("tr");
      if (packageName.length() > 0) {
        builder.appendTdPackage(packageName);
      }
      builder.appendTdClassName(typeInPackage(className));
      builder
          .start("td")
          .start(
              "ul style=\"list-style-position:inside; list-style-type: none !important;"
                  + " margin-left:0px;padding-left:0px !important;\"");
      if (!fields.isEmpty()) {
        for (DexEncodedField field : fields) {
          builder.appendLiCode(
              accessFlags(field.accessFlags)
                  + " "
                  + typeInPackage(field.getReference().type)
                  + " "
                  + field.getReference().name);
        }
      }
      if (!constructors.isEmpty()) {
        for (DexEncodedMethod constructor : constructors.keySet()) {
          builder.appendMethodLiCode(
              accessFlags(constructor.accessFlags)
                  + " "
                  + typeInPackage(className)
                  + arguments(constructor)
                  + getTextAnnotations(constructors.get(constructor)));
        }
      }
      if (!methods.isEmpty()) {
        for (DexEncodedMethod method : methods.keySet()) {
          builder.appendMethodLiCode(
              accessFlags(method.accessFlags)
                  + " "
                  + typeInPackage(method.getReference().proto.returnType)
                  + " "
                  + method.getReference().name
                  + arguments(method)
                  + getTextAnnotations(methods.get(method)));
        }
      }
      builder.end("ul").end("td");
      StringBuilder commentBuilder = new StringBuilder();
      if (classAnnotation.fullySupported) {
        commentBuilder.append("Fully implemented class.").append(HTML_SPLIT);
      }
      if (parallelStreamMethod) {
        commentBuilder
            .append(SUP_1)
            .append("Supported only on devices which API level is 21 or higher.")
            .append(HTML_SPLIT);
      }
      if (missingFromLatestAndroidJar) {
        commentBuilder
            .append(SUP_2)
            .append("Not present in Android ")
            .append(MAX_TESTED_ANDROID_API_LEVEL)
            .append(" (May not resolve at compilation).")
            .append(HTML_SPLIT);
      }
      if (unsupportedInMinApiRange) {
        commentBuilder
            .append(SUP_3)
            .append(" Not supported at all minSDK levels.")
            .append(HTML_SPLIT);
      }
      if (covariantReturnSupported) {
        commentBuilder
            .append(SUP_4)
            .append(" Also supported with covariant return type.")
            .append(HTML_SPLIT);
      }
      if (!classAnnotation.unsupportedMethods.isEmpty()) {
        commentBuilder
            .append("Some methods (")
            .append(classAnnotation.unsupportedMethods.size())
            .append(") present in Android ")
            .append(MAX_TESTED_ANDROID_API_LEVEL)
            .append(" are not supported.");
      }
      builder.appendTdP(commentBuilder.toString());
      builder.end("tr");
      return builder.toString();
    }
  }

  private void generateClassHTML(
      PrintStream ps,
      DexClass clazz,
      List<DexEncodedMethod> methods,
      ClassAnnotation classAnnotation,
      Map<DexMethod, MethodAnnotation> methodAnnotationMap) {
    SourceBuilder<HTMLSourceBuilder> builder = new HTMLSourceBuilder(clazz, classAnnotation);
    // We need to extend to support fields.
    StreamSupport.stream(clazz.fields().spliterator(), false)
        .filter(field -> field.accessFlags.isPublic() || field.accessFlags.isProtected())
        .sorted(Comparator.comparing(DexEncodedField::toSourceString))
        .forEach(builder::addField);
    methods.stream()
        .filter(
            method ->
                (method.accessFlags.isPublic() || method.accessFlags.isProtected())
                    && !method.accessFlags.isBridge())
        .sorted(Comparator.comparing(DexEncodedMethod::toSourceString))
        .forEach(m -> builder.addMethod(m, methodAnnotationMap.get(m.getReference())));
    ps.println(builder);
  }

  @Override
  AndroidApiLevel run() throws Exception {
    PrintStream ps = new PrintStream(Files.newOutputStream(outputDirectory.resolve("apis.html")));

    SupportedMethodsWithAnnotations supportedMethods =
        new SupportedMethodsGenerator(options)
            .run(desugaredLibraryImplementation, desugaredLibrarySpecificationPath);

    // Full classes added.
    supportedMethods.supportedMethods.forEach(
        (clazz, methods) -> {
          generateClassHTML(
              ps,
              clazz,
              methods,
              supportedMethods.annotatedClasses.get(clazz.type),
              supportedMethods.annotatedMethods);
        });
    return MAX_TESTED_ANDROID_API_LEVEL;
  }

  public static void main(String[] args) throws Exception {
    AbstractGenerateFiles.main(args);
  }
}