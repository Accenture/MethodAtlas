package org.egothor.methodatlas;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.github.javaparser.ParserConfiguration;
import com.github.javaparser.ParserConfiguration.LanguageLevel;
import com.github.javaparser.StaticJavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.MethodDeclaration;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.ArrayInitializerExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.MemberValuePair;

/**
 * Command-line utility that scans Java source trees for JUnit test methods and
 * reports per-method statistics.
 *
 * <p>
 * The tool walks one or more root directories, parses {@code *Test.java} files
 * using JavaParser, and emits a record for each method annotated with one of
 * the supported JUnit Jupiter test annotations.
 * </p>
 *
 * <h2>Detection</h2>
 * <ul>
 * <li>Test methods are detected by annotations {@code @Test},
 * {@code @ParameterizedTest}, and {@code @RepeatedTest} (simple name
 * match).</li>
 * <li>{@code @Tag} values are collected from repeated {@code @Tag("...")}
 * annotations and from {@code @Tags({ @Tag("..."), ... })} containers.</li>
 * <li>Lines of code (LOC) is computed from the AST source range:
 * {@code endLine - beginLine + 1}. If the range is unavailable, LOC is
 * {@code 0}.</li>
 * </ul>
 *
 * <h2>Output</h2>
 * <p>
 * By default, the tool prints CSV with a header line:
 * {@code fqcn,method,loc,tags}. The {@code tags} field is a semicolon-separated
 * list.
 * </p>
 *
 * <p>
 * If {@code -plain} is provided as the first argument, the tool prints a plain
 * text format:
 * </p>
 * <pre>
 * fqcn, method, LOC=&lt;n&gt;, TAGS=&lt;tag1;tag2&gt;
 * </pre>
 * <p>
 * If no tags are present, {@code TAGS=-} is printed.
 * </p>
 *
 * <h2>Examples</h2> <pre>
 * java -jar methodatlas.jar /path/to/repo
 * java -jar methodatlas.jar -plain /path/to/repo /another/repo
 * </pre>
 */
public class MethodAtlasApp {
    /**
     * Logging facility for scan progress and parse failures.
     */
    private static final Logger LOG = Logger.getLogger(MethodAtlasApp.class.getName());

    /**
     * Output formats supported by the application.
     */
    private enum OutputMode {
        /**
         * Comma-separated values with a header line and raw numeric LOC.
         */
        CSV,
        /**
         * Plain text lines including {@code LOC=} and {@code TAGS=} labels.
         */
        PLAIN
    }

    /**
     * Program entry point.
     *
     * <p>
     * Usage:
     * </p>
     * <pre>
     * java -jar methodatlas.jar [ -plain ] &lt;path1&gt; [ &lt;path2&gt; ... ]
     * </pre>
     *
     * <ul>
     * <li>If {@code -plain} is provided as the first argument, the tool uses the
     * plain-text output mode; otherwise CSV output is used.</li>
     * <li>If no paths are provided, the current directory {@code "."} is
     * scanned.</li>
     * </ul>
     *
     * <p>
     * The JavaParser language level is configured before parsing to support modern
     * Java syntax (for example, {@code record} declarations).
     * </p>
     *
     * @param args command-line arguments; see usage above
     * @throws IOException if directory traversal fails while scanning input paths
     */
    public static void main(String[] args) throws IOException {
        ParserConfiguration pc = new ParserConfiguration();
        pc.setLanguageLevel(LanguageLevel.JAVA_21); // or JAVA_17, etc.
        StaticJavaParser.setConfiguration(pc);

        OutputMode mode = OutputMode.CSV;
        int firstPathIndex = 0;

        if (args.length > 0 && "-plain".equals(args[0])) {
            mode = OutputMode.PLAIN;
            firstPathIndex = 1;
        }

        if (mode == OutputMode.CSV) {
            System.out.println("fqcn,method,loc,tags");
        }

        if (args.length <= firstPathIndex) {
            scanRoot(Paths.get("."), mode);
            return;
        }

        for (int i = firstPathIndex; i < args.length; i++) {
            scanRoot(Paths.get(args[i]), mode);
        }
    }

    /**
     * Recursively scans the supplied root directory for Java test files and
     * processes them.
     *
     * <p>
     * The current implementation matches files by suffix {@code "Test.java"}.
     * </p>
     *
     * @param root root directory to scan
     * @param mode output mode to use for emitted records
     * @throws IOException if the file tree walk fails
     */
    private static void scanRoot(Path root, OutputMode mode) throws IOException {
        LOG.log(Level.INFO, "Scanning {0} for JUnit files", root);

        Files.walk(root).filter(p -> p.toString().endsWith("Test.java")).forEach(p -> processFile(p, mode));
    }

    /**
     * Parses a single Java source file and emits records for all detected JUnit
     * test methods.
     *
     * <p>
     * Parse errors are logged. Files that cannot be parsed are skipped.
     * </p>
     *
     * @param path Java source file to parse
     * @param mode output mode to use for emitted records
     */
    private static void processFile(Path path, OutputMode mode) {
        try {
            CompilationUnit cu = StaticJavaParser.parse(path);

            String pkg = cu.getPackageDeclaration().map(p -> p.getNameAsString()).orElse("");

            cu.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
                String className = clazz.getNameAsString();
                String fqcn = pkg.isEmpty() ? className : pkg + "." + className;

                clazz.findAll(MethodDeclaration.class).forEach(method -> {
                    if (isJUnitTest(method)) {
                        int loc = countLOC(method);
                        List<String> tags = getTagValues(method);
                        emit(mode, fqcn, method.getNameAsString(), loc, tags);
                    }
                });
            });

        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to parse: {0}", path);
            e.printStackTrace();
        }
    }

    /**
     * Emits a single output record representing one test method.
     *
     * <p>
     * For CSV output, values are printed as {@code fqcn,method,loc,tags} with CSV
     * escaping applied to text fields. The {@code tags} field is a
     * semicolon-separated list, or an empty field if no tags exist.
     * </p>
     *
     * <p>
     * For plain output, labels {@code LOC=} and {@code TAGS=} are included. If no
     * tags exist, {@code TAGS=-} is printed.
     * </p>
     *
     * @param mode   output mode to use
     * @param fqcn   fully-qualified class name
     * @param method method name
     * @param loc    lines of code for the method declaration (inclusive range)
     * @param tags   list of tag values; may be empty
     */
    private static void emit(OutputMode mode, String fqcn, String method, int loc, List<String> tags) {
        if (mode == OutputMode.PLAIN) {
            String tagText = tags.isEmpty() ? "-" : String.join(";", tags);
            System.out.println(fqcn + ", " + method + ", LOC=" + loc + ", TAGS=" + tagText);
            return;
        }

        String tagText = tags.isEmpty() ? "" : String.join(";", tags);
        System.out.println(csvEscape(fqcn) + "," + csvEscape(method) + "," + loc + "," + csvEscape(tagText));
    }

    /**
     * Escapes a value for safe inclusion in a CSV field.
     *
     * <p>
     * If the value contains a comma, quote, or line break, the value is quoted and
     * internal quotes are doubled, per common CSV conventions.
     * </p>
     *
     * @param value input value; may be {@code null}
     * @return escaped CSV field value (never {@code null})
     */
    private static String csvEscape(String value) {
        if (value == null) {
            return "";
        }
        boolean mustQuote = value.indexOf(',') >= 0 || value.indexOf('"') >= 0 || value.indexOf('\n') >= 0
                || value.indexOf('\r') >= 0;

        if (!mustQuote) {
            return value;
        }
        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    /**
     * Determines whether the given method declaration represents a JUnit Jupiter
     * test method.
     *
     * <p>
     * The method is considered a test if it is annotated with one of the supported
     * test annotations by simple name: {@code Test}, {@code ParameterizedTest}, or
     * {@code RepeatedTest}.
     * </p>
     *
     * @param method method declaration to inspect
     * @return {@code true} if the method is considered a test method; {@code false}
     *         otherwise
     */
    private static boolean isJUnitTest(MethodDeclaration method) {
        for (AnnotationExpr ann : method.getAnnotations()) {
            String name = ann.getNameAsString();
            if ("Test".equals(name) || "ParameterizedTest".equals(name) || "RepeatedTest".equals(name)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Collects all {@code @Tag} values declared directly on a method.
     *
     * <p>
     * Supported forms:
     * </p>
     * <ul>
     * <li>Repeated {@code @Tag("...")} annotations</li>
     * <li>{@code @Tags({ @Tag("..."), @Tag("...") })} container annotation</li>
     * </ul>
     *
     * @param method method declaration to inspect
     * @return list of tag values in encounter order; never {@code null}
     */
    private static List<String> getTagValues(MethodDeclaration method) {
        List<String> tags = new ArrayList<>();

        for (AnnotationExpr ann : method.getAnnotations()) {
            String name = ann.getNameAsString();
            if (isTagAnnotationName(name)) {
                extractTagValue(ann).ifPresent(tags::add);
            } else if (isTagsContainerAnnotationName(name)) {
                tags.addAll(extractTagValuesFromContainer(ann));
            }
        }

        return tags;
    }

    /**
     * Checks whether an annotation name represents {@code @Tag}.
     *
     * @param name annotation name (simple or qualified)
     * @return {@code true} if it matches {@code Tag}; {@code false} otherwise
     */
    private static boolean isTagAnnotationName(String name) {
        return "Tag".equals(name) || name.endsWith(".Tag");
    }

    /**
     * Checks whether an annotation name represents {@code @Tags} (the container for
     * {@code @Tag}).
     *
     * @param name annotation name (simple or qualified)
     * @return {@code true} if it matches {@code Tags}; {@code false} otherwise
     */
    private static boolean isTagsContainerAnnotationName(String name) {
        return "Tags".equals(name) || name.endsWith(".Tags");
    }

    /**
     * Extracts the tag value from a {@code @Tag} annotation expression.
     *
     * <p>
     * Both single-member and normal annotation syntaxes are supported:
     * </p>
     * <ul>
     * <li>{@code @Tag("fast")}</li>
     * <li>{@code @Tag(value = "fast")}</li>
     * </ul>
     *
     * @param ann annotation expression representing {@code @Tag}
     * @return extracted tag value, or empty if it cannot be determined
     */
    private static Optional<String> extractTagValue(AnnotationExpr ann) {
        if (ann.isSingleMemberAnnotationExpr()) {
            return Optional.of(expressionToTagText(ann.asSingleMemberAnnotationExpr().getMemberValue()));
        }

        if (ann.isNormalAnnotationExpr()) {
            for (MemberValuePair pair : ann.asNormalAnnotationExpr().getPairs()) {
                if ("value".equals(pair.getNameAsString())) {
                    return Optional.of(expressionToTagText(pair.getValue()));
                }
            }
        }

        return Optional.empty();
    }

    /**
     * Extracts all contained {@code @Tag} values from a {@code @Tags} container
     * annotation.
     *
     * <p>
     * Handles array initializers such as:
     * </p>
     * <pre>
     * &#64;Tags({ @Tag("a"), @Tag("b") })
     * </pre>
     *
     * @param ann annotation expression representing {@code @Tags}
     * @return list of extracted tag values; never {@code null}
     */
    private static List<String> extractTagValuesFromContainer(AnnotationExpr ann) {
        List<String> tags = new ArrayList<>();
        Optional<Expression> maybeValue = Optional.empty();

        if (ann.isSingleMemberAnnotationExpr()) {
            maybeValue = Optional.of(ann.asSingleMemberAnnotationExpr().getMemberValue());
        } else if (ann.isNormalAnnotationExpr()) {
            for (MemberValuePair pair : ann.asNormalAnnotationExpr().getPairs()) {
                if ("value".equals(pair.getNameAsString())) {
                    maybeValue = Optional.of(pair.getValue());
                    break;
                }
            }
        }

        if (maybeValue.isEmpty()) {
            return tags;
        }

        Expression value = maybeValue.get();
        if (value.isArrayInitializerExpr()) {
            ArrayInitializerExpr array = value.asArrayInitializerExpr();
            for (Expression element : array.getValues()) {
                if (element.isAnnotationExpr()) {
                    AnnotationExpr inner = element.asAnnotationExpr();
                    if (isTagAnnotationName(inner.getNameAsString())) {
                        extractTagValue(inner).ifPresent(tags::add);
                    }
                }
            }
        } else if (value.isAnnotationExpr()) {
            AnnotationExpr inner = value.asAnnotationExpr();
            if (isTagAnnotationName(inner.getNameAsString())) {
                extractTagValue(inner).ifPresent(tags::add);
            }
        }

        return tags;
    }

    /**
     * Converts an annotation value expression to tag text.
     *
     * <p>
     * String literals are returned as their unescaped string value. Other
     * expressions are returned using {@link Expression#toString()}.
     * </p>
     *
     * @param expr expression to convert; may be {@code null}
     * @return converted tag text (never {@code null})
     */
    private static String expressionToTagText(Expression expr) {
        if (expr == null) {
            return "";
        }
        if (expr.isStringLiteralExpr()) {
            return expr.asStringLiteralExpr().asString();
        }
        return expr.toString();
    }

    /**
     * Computes the lines of code (LOC) for a method declaration using its source
     * range.
     *
     * @param method method declaration
     * @return inclusive LOC computed from the source range; {@code 0} if range is
     *         not available
     */
    private static int countLOC(MethodDeclaration method) {
        if (method.getRange().isPresent()) {
            return method.getRange().get().end.line - method.getRange().get().begin.line + 1;
        }
        return 0;
    }
}
