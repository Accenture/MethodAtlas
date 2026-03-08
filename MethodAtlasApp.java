package org.egothor.methodatlas;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.stream.Stream;

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

import org.egothor.methodatlas.ai.AiClassSuggestion;
import org.egothor.methodatlas.ai.AiMethodSuggestion;
import org.egothor.methodatlas.ai.AiOptions;
import org.egothor.methodatlas.ai.AiProvider;
import org.egothor.methodatlas.ai.AiSuggestionEngine;
import org.egothor.methodatlas.ai.AiSuggestionEngineImpl;
import org.egothor.methodatlas.ai.AiSuggestionException;
import org.egothor.methodatlas.ai.SuggestionLookup;

public class MethodAtlasApp {

    private static final Logger LOG = Logger.getLogger(MethodAtlasApp.class.getName());

    private enum OutputMode {
        CSV,
        PLAIN
    }

    private record CliConfig(
            OutputMode outputMode,
            AiOptions aiOptions,
            List<Path> paths
    ) {
    }

    public static void main(String[] args) throws IOException {
        ParserConfiguration parserConfiguration = new ParserConfiguration();
        parserConfiguration.setLanguageLevel(LanguageLevel.JAVA_21);
        StaticJavaParser.setConfiguration(parserConfiguration);

        CliConfig cliConfig = parseArgs(args);
        AiSuggestionEngine aiEngine = buildAiEngine(cliConfig.aiOptions());

        if (cliConfig.outputMode() == OutputMode.CSV) {
            if (cliConfig.aiOptions().enabled()) {
                System.out.println("fqcn,method,loc,tags,ai_security_relevant,ai_display_name,ai_tags,ai_reason");
            } else {
                System.out.println("fqcn,method,loc,tags");
            }
        }

        if (cliConfig.paths().isEmpty()) {
            scanRoot(Paths.get("."), cliConfig.outputMode(), cliConfig.aiOptions(), aiEngine);
            return;
        }

        for (Path path : cliConfig.paths()) {
            scanRoot(path, cliConfig.outputMode(), cliConfig.aiOptions(), aiEngine);
        }
    }

    private static void scanRoot(
            Path root,
            OutputMode mode,
            AiOptions aiOptions,
            AiSuggestionEngine aiEngine
    ) throws IOException {
        LOG.log(Level.INFO, "Scanning {0} for JUnit files", root);

        try (Stream<Path> stream = Files.walk(root)) {
            stream.filter(path -> path.toString().endsWith("Test.java"))
                    .forEach(path -> processFile(path, mode, aiOptions, aiEngine));
        }
    }

    private static void processFile(
            Path path,
            OutputMode mode,
            AiOptions aiOptions,
            AiSuggestionEngine aiEngine
    ) {
        try {
            CompilationUnit compilationUnit = StaticJavaParser.parse(path);
            String packageName = compilationUnit.getPackageDeclaration()
                    .map(packageDeclaration -> packageDeclaration.getNameAsString())
                    .orElse("");

            compilationUnit.findAll(ClassOrInterfaceDeclaration.class).forEach(clazz -> {
                String className = clazz.getNameAsString();
                String fqcn = packageName.isEmpty() ? className : packageName + "." + className;
                SuggestionLookup suggestionLookup = resolveSuggestionLookup(clazz, fqcn, aiOptions, aiEngine);

                clazz.findAll(MethodDeclaration.class).forEach(method -> {
                    if (!isJUnitTest(method)) {
                        return;
                    }

                    int loc = countLOC(method);
                    List<String> tags = getTagValues(method);
                    AiMethodSuggestion suggestion = suggestionLookup.find(method.getNameAsString()).orElse(null);

                    emit(
                            mode,
                            aiOptions.enabled(),
                            fqcn,
                            method.getNameAsString(),
                            loc,
                            tags,
                            suggestion
                    );
                });
            });
        } catch (Exception e) {
            LOG.log(Level.WARNING, "Failed to parse: {0}", path);
            e.printStackTrace();
        }
    }

    private static SuggestionLookup resolveSuggestionLookup(
            ClassOrInterfaceDeclaration clazz,
            String fqcn,
            AiOptions aiOptions,
            AiSuggestionEngine aiEngine
    ) {
        if (!aiOptions.enabled() || aiEngine == null) {
            return SuggestionLookup.from(null);
        }

        String classSource = clazz.toString();
        if (classSource.length() > aiOptions.maxClassChars()) {
            LOG.log(
                    Level.INFO,
                    "Skipping AI for {0}: class source too large ({1} chars)",
                    new Object[] { fqcn, classSource.length() }
            );
            return SuggestionLookup.from(null);
        }

        try {
            AiClassSuggestion aiClassSuggestion = aiEngine.suggestForClass(fqcn, classSource);
            return SuggestionLookup.from(aiClassSuggestion);
        } catch (AiSuggestionException e) {
            LOG.log(Level.WARNING, "AI suggestion failed for class " + fqcn, e);
            return SuggestionLookup.from(null);
        }
    }

    private static AiSuggestionEngine buildAiEngine(AiOptions aiOptions) {
        if (!aiOptions.enabled()) {
            return null;
        }

        try {
            return new AiSuggestionEngineImpl(aiOptions);
        } catch (AiSuggestionException e) {
            throw new IllegalStateException("Failed to initialize AI engine", e);
        }
    }

    private static void emit(
            OutputMode mode,
            boolean aiEnabled,
            String fqcn,
            String method,
            int loc,
            List<String> tags,
            AiMethodSuggestion suggestion
    ) {
        if (mode == OutputMode.PLAIN) {
            emitPlain(aiEnabled, fqcn, method, loc, tags, suggestion);
            return;
        }

        emitCsv(aiEnabled, fqcn, method, loc, tags, suggestion);
    }

    private static void emitPlain(
            boolean aiEnabled,
            String fqcn,
            String method,
            int loc,
            List<String> tags,
            AiMethodSuggestion suggestion
    ) {
        String existingTags = tags.isEmpty() ? "-" : String.join(";", tags);

        if (!aiEnabled) {
            System.out.println(fqcn + ", " + method + ", LOC=" + loc + ", TAGS=" + existingTags);
            return;
        }

        String aiSecurity = suggestion == null ? "-" : Boolean.toString(suggestion.securityRelevant());
        String aiDisplayName = suggestion == null || suggestion.displayName() == null ? "-" : suggestion.displayName();
        String aiTags = suggestion == null || suggestion.tags() == null || suggestion.tags().isEmpty()
                ? "-"
                : String.join(";", suggestion.tags());
        String aiReason = suggestion == null || suggestion.reason() == null || suggestion.reason().isBlank()
                ? "-"
                : suggestion.reason();

        System.out.println(
                fqcn + ", " + method
                        + ", LOC=" + loc
                        + ", TAGS=" + existingTags
                        + ", AI_SECURITY=" + aiSecurity
                        + ", AI_DISPLAY=" + aiDisplayName
                        + ", AI_TAGS=" + aiTags
                        + ", AI_REASON=" + aiReason
        );
    }

    private static void emitCsv(
            boolean aiEnabled,
            String fqcn,
            String method,
            int loc,
            List<String> tags,
            AiMethodSuggestion suggestion
    ) {
        String existingTags = tags.isEmpty() ? "" : String.join(";", tags);

        if (!aiEnabled) {
            System.out.println(
                    csvEscape(fqcn) + ","
                            + csvEscape(method) + ","
                            + loc + ","
                            + csvEscape(existingTags)
            );
            return;
        }

        String aiSecurity = suggestion == null ? "" : Boolean.toString(suggestion.securityRelevant());
        String aiDisplayName = suggestion == null || suggestion.displayName() == null ? "" : suggestion.displayName();
        String aiTags = suggestion == null || suggestion.tags() == null ? "" : String.join(";", suggestion.tags());
        String aiReason = suggestion == null || suggestion.reason() == null ? "" : suggestion.reason();

        System.out.println(
                csvEscape(fqcn) + ","
                        + csvEscape(method) + ","
                        + loc + ","
                        + csvEscape(existingTags) + ","
                        + csvEscape(aiSecurity) + ","
                        + csvEscape(aiDisplayName) + ","
                        + csvEscape(aiTags) + ","
                        + csvEscape(aiReason)
        );
    }

    private static String csvEscape(String value) {
        if (value == null) {
            return "";
        }

        boolean mustQuote =
                value.indexOf(',') >= 0
                        || value.indexOf('"') >= 0
                        || value.indexOf('\n') >= 0
                        || value.indexOf('\r') >= 0;

        if (!mustQuote) {
            return value;
        }

        return "\"" + value.replace("\"", "\"\"") + "\"";
    }

    private static CliConfig parseArgs(String[] args) {
        OutputMode outputMode = OutputMode.CSV;
        List<Path> paths = new ArrayList<>();
        AiOptions.Builder aiBuilder = AiOptions.builder();

        for (int i = 0; i < args.length; i++) {
            String arg = args[i];

            switch (arg) {
                case "-plain" -> outputMode = OutputMode.PLAIN;
                case "-ai" -> aiBuilder.enabled(true);
                case "-ai-provider" -> aiBuilder.provider(
                        AiProvider.valueOf(nextArg(args, ++i, arg).toUpperCase())
                );
                case "-ai-model" -> aiBuilder.modelName(nextArg(args, ++i, arg));
                case "-ai-base-url" -> aiBuilder.baseUrl(nextArg(args, ++i, arg));
                case "-ai-api-key" -> aiBuilder.apiKey(nextArg(args, ++i, arg));
                case "-ai-api-key-env" -> aiBuilder.apiKeyEnv(nextArg(args, ++i, arg));
                case "-ai-taxonomy" -> aiBuilder.taxonomyFile(Paths.get(nextArg(args, ++i, arg)));
                case "-ai-taxonomy-mode" -> aiBuilder.taxonomyMode(
                        AiOptions.TaxonomyMode.valueOf(nextArg(args, ++i, arg).toUpperCase())
                );
                case "-ai-max-class-chars" -> aiBuilder.maxClassChars(
                        Integer.parseInt(nextArg(args, ++i, arg))
                );
                case "-ai-timeout-sec" -> aiBuilder.timeout(
                        Duration.ofSeconds(Long.parseLong(nextArg(args, ++i, arg)))
                );
                case "-ai-max-retries" -> aiBuilder.maxRetries(
                        Integer.parseInt(nextArg(args, ++i, arg))
                );
                default -> {
                    if (arg.startsWith("-")) {
                        throw new IllegalArgumentException("Unknown argument: " + arg);
                    }
                    paths.add(Paths.get(arg));
                }
            }
        }

        return new CliConfig(outputMode, aiBuilder.build(), paths);
    }

    private static String nextArg(String[] args, int index, String option) {
        if (index >= args.length) {
            throw new IllegalArgumentException("Missing value for " + option);
        }
        return args[index];
    }

    private static boolean isJUnitTest(MethodDeclaration method) {
        for (AnnotationExpr annotation : method.getAnnotations()) {
            String name = annotation.getNameAsString();
            if ("Test".equals(name) || "ParameterizedTest".equals(name) || "RepeatedTest".equals(name)) {
                return true;
            }
        }

        return false;
    }

    private static int countLOC(MethodDeclaration method) {
        return method.getRange()
                .map(range -> range.end.line - range.begin.line + 1)
                .orElse(0);
    }

    private static List<String> getTagValues(MethodDeclaration method) {
        List<String> tagValues = new ArrayList<>();

        for (AnnotationExpr annotation : method.getAnnotations()) {
            String name = annotation.getNameAsString();

            if ("Tag".equals(name)) {
                extractTagValue(annotation).ifPresent(tagValues::add);
            } else if ("Tags".equals(name) && annotation.isNormalAnnotationExpr()) {
                for (MemberValuePair pair : annotation.asNormalAnnotationExpr().getPairs()) {
                    if ("value".equals(pair.getNameAsString()) && pair.getValue().isArrayInitializerExpr()) {
                        ArrayInitializerExpr array = pair.getValue().asArrayInitializerExpr();
                        for (Expression expression : array.getValues()) {
                            if (expression.isAnnotationExpr()) {
                                extractTagValue(expression.asAnnotationExpr()).ifPresent(tagValues::add);
                            }
                        }
                    }
                }
            }
        }

        return tagValues;
    }

    private static Optional<String> extractTagValue(AnnotationExpr annotation) {
        if (!"Tag".equals(annotation.getNameAsString())) {
            return Optional.empty();
        }

        if (annotation.isSingleMemberAnnotationExpr()) {
            Expression memberValue = annotation.asSingleMemberAnnotationExpr().getMemberValue();
            if (memberValue.isStringLiteralExpr()) {
                return Optional.of(memberValue.asStringLiteralExpr().asString());
            }
        }

        if (annotation.isNormalAnnotationExpr()) {
            for (MemberValuePair pair : annotation.asNormalAnnotationExpr().getPairs()) {
                if ("value".equals(pair.getNameAsString()) && pair.getValue().isStringLiteralExpr()) {
                    return Optional.of(pair.getValue().asStringLiteralExpr().asString());
                }
            }
        }

        return Optional.empty();
    }
}
