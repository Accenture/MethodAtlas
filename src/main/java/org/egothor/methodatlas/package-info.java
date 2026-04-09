/**
 * Provides the core command-line utility for analyzing Java test sources and
 * producing structured metadata about JUnit test methods.
 *
 * <p>
 * The central component of this package is
 * {@link org.egothor.methodatlas.MethodAtlasApp}, a command-line application
 * that scans Java source trees, identifies JUnit Jupiter test methods, and
 * emits per-method metadata describing the discovered tests.
 * </p>
 *
 * <h2>Overview</h2>
 *
 * <p>
 * The application traverses one or more directory roots, parses Java source
 * files using the <a href="https://javaparser.org/">JavaParser</a> library, and
 * extracts information about test methods declared in classes whose file names
 * follow the conventional {@code *Test.java} pattern.
 * </p>
 *
 * <p>
 * For each detected test method the application reports:
 * </p>
 *
 * <ul>
 * <li>fully-qualified class name (FQCN)</li>
 * <li>test method name</li>
 * <li>method size measured in lines of code (LOC)</li>
 * <li>JUnit {@code @Tag} annotations declared on the method</li>
 * </ul>
 *
 * <p>
 * The resulting dataset can be used for test inventory generation, quality
 * metrics, governance reporting, or security analysis of test coverage.
 * </p>
 *
 * <h2>AI-Based Security Tagging</h2>
 *
 * <p>
 * When enabled via command-line options, the application can augment the
 * extracted test metadata with security classification suggestions produced by
 * an AI provider. The AI integration is implemented through the
 * {@link org.egothor.methodatlas.ai.AiSuggestionEngine} abstraction located in
 * the {@code org.egothor.methodatlas.ai} package.
 * </p>
 *
 * <p>
 * In this mode the application sends each discovered test class to the
 * configured AI provider and receives suggested security annotations, such as:
 * </p>
 *
 * <ul>
 * <li>whether the test validates a security property</li>
 * <li>suggested {@code @DisplayName} describing the security intent</li>
 * <li>taxonomy-based security tags</li>
 * <li>optional explanatory reasoning</li>
 * </ul>
 *
 * <p>
 * These suggestions are merged with the source-derived metadata and emitted
 * alongside the standard output fields.
 * </p>
 *
 * <h2>Output Formats</h2>
 *
 * <p>
 * The application supports two output modes:
 * </p>
 *
 * <ul>
 * <li><b>CSV (default)</b> <pre>{@code fqcn,method,loc,tags}</pre> or, when AI
 * enrichment is enabled:
 * <pre>{@code fqcn,method,loc,tags,ai_security_relevant,ai_display_name,ai_tags,ai_reason}</pre>
 * </li>
 * <li><b>Plain text</b>, enabled using the {@code -plain} command-line option
 * </li>
 * </ul>
 *
 * <h2>Typical Usage</h2>
 *
 * <pre>{@code
 * java -jar methodatlas.jar /path/to/project
 * }
 * </pre>
 *
 * <pre>{@code
 * java -jar methodatlas.jar -plain /path/to/project
 * }
 * </pre>
 *
 * <p>
 * The command scans the specified source directory recursively and emits one
 * output record per detected test method.
 * </p>
 *
 * <h2>Implementation Notes</h2>
 *
 * <ul>
 * <li>Parsing is performed using {@link com.github.javaparser.JavaParser}
 * (instance API, not the static singleton).</li>
 * <li>Test detection is based on JUnit Jupiter annotations such as
 * {@code @Test}, {@code @ParameterizedTest}, {@code @RepeatedTest},
 * {@code @TestFactory}, and {@code @TestTemplate}.</li>
 * <li>Tag extraction supports both {@code @Tag} annotations and the container
 * form {@code @Tags}.</li>
 * </ul>
 *
 * @see org.egothor.methodatlas.MethodAtlasApp
 * @see org.egothor.methodatlas.AnnotationInspector
 * @see org.egothor.methodatlas.OutputEmitter
 * @see org.egothor.methodatlas.ai.AiSuggestionEngine
 * @see org.egothor.methodatlas.ai.ManualPrepareEngine
 * @see org.egothor.methodatlas.ai.ManualConsumeEngine
 */
package org.egothor.methodatlas;