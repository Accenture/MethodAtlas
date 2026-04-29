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
 * The application traverses one or more directory roots and discovers test
 * methods via pluggable {@link org.egothor.methodatlas.api.TestDiscovery}
 * providers. The default JVM provider uses
 * <a href="https://javaparser.org/">JavaParser</a> internally; file selection
 * follows the conventional {@code *Test.java} pattern.
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
 * <li>text of any {@code @DisplayName} annotation declared on the method</li>
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
 * <li><b>CSV (default)</b> <pre>{@code fqcn,method,loc,tags,display_name}</pre> or, when AI
 * enrichment is enabled:
 * <pre>{@code fqcn,method,loc,tags,display_name,ai_security_relevant,ai_display_name,ai_tags,ai_reason,ai_interaction_score}</pre>
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
 * <li>Test method discovery is performed by pluggable
 * {@link org.egothor.methodatlas.api.TestDiscovery} providers loaded via
 * {@link java.util.ServiceLoader}; the JVM provider ships in the
 * {@code methodatlas-discovery-jvm} module.</li>
 * <li>Source file write-back is performed by pluggable
 * {@link org.egothor.methodatlas.api.SourcePatcher} providers, also loaded
 * via {@link java.util.ServiceLoader}.</li>
 * <li>Test detection in the JVM provider is based on JUnit Jupiter annotations
 * such as {@code @Test}, {@code @ParameterizedTest}, {@code @RepeatedTest},
 * {@code @TestFactory}, and {@code @TestTemplate}.</li>
 * </ul>
 *
 * <h2>Sub-packages</h2>
 *
 * <ul>
 * <li>{@code org.egothor.methodatlas.api} — platform-neutral SPI contracts:
 *     {@link org.egothor.methodatlas.api.TestDiscovery},
 *     {@link org.egothor.methodatlas.api.SourcePatcher},
 *     {@link org.egothor.methodatlas.api.DiscoveredMethod},
 *     {@link org.egothor.methodatlas.api.ScanRecord}</li>
 * <li>{@code org.egothor.methodatlas.discovery.jvm} — Java/JVM test discovery
 *     implementation (shipped in the {@code methodatlas-discovery-jvm} module):
 *     {@code JavaTestDiscovery}, {@code JavaSourcePatcher}</li>
 * <li>{@code org.egothor.methodatlas.discovery.dotnet} — C#/.NET test discovery
 *     implementation (shipped in the {@code methodatlas-discovery-dotnet} module):
 *     {@code DotNetTestDiscovery}, {@code DotNetSourcePatcher}</li>
 * <li>{@code org.egothor.methodatlas.emit} — output emitters:
 *     {@link org.egothor.methodatlas.emit.OutputEmitter},
 *     {@link org.egothor.methodatlas.emit.SarifEmitter},
 *     {@link org.egothor.methodatlas.emit.GitHubAnnotationsEmitter},
 *     {@link org.egothor.methodatlas.emit.DeltaEmitter}</li>
 * <li>{@code org.egothor.methodatlas.ai} — AI suggestion engine and provider
 *     integrations</li>
 * </ul>
 *
 * @see org.egothor.methodatlas.MethodAtlasApp
 * @see org.egothor.methodatlas.api.SourcePatcher
 * @see org.egothor.methodatlas.emit.OutputEmitter
 * @see org.egothor.methodatlas.ai.AiSuggestionEngine
 * @see org.egothor.methodatlas.ai.ManualPrepareEngine
 * @see org.egothor.methodatlas.ai.ManualConsumeEngine
 */
package org.egothor.methodatlas;