package org.egothor.methodatlas;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import org.egothor.methodatlas.ai.AiProviderClient;
import org.egothor.methodatlas.command.Command;

import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * ArchUnit structural tests that enforce package-level constraints for the
 * MethodAtlas architecture.
 *
 * <p>
 * The project uses a multi-module architecture with four key packages visible
 * at test time:
 * </p>
 * <ul>
 * <li>{@code org.egothor.methodatlas} – scanner core and CLI routing</li>
 * <li>{@code org.egothor.methodatlas.api} – SPI contracts:
 * {@code TestDiscovery}, {@code SourcePatcher}, {@code DiscoveredMethod},
 * {@code ScanRecord}</li>
 * <li>{@code org.egothor.methodatlas.command} – CLI command handlers
 * ({@code Command} interface, {@code CommandSupport}, and one class per mode)</li>
 * <li>{@code org.egothor.methodatlas.discovery.jvm} – Java/JVM test discovery
 * and source patching implementation (runtime-only dependency)</li>
 * <li>{@code org.egothor.methodatlas.emit} – output emitter implementations</li>
 * <li>{@code org.egothor.methodatlas.ai} – AI subsystem, HTTP clients,
 * prompt builder</li>
 * </ul>
 *
 * <p>
 * These tests guard against architectural drift as the codebase evolves.
 * They run as ordinary JUnit 5 tests and fail with a descriptive message
 * whenever a rule is violated.
 * </p>
 */
@AnalyzeClasses(packages = "org.egothor.methodatlas")
class ArchitectureTest {

    /**
     * All classes that implement {@link AiProviderClient} must be declared
     * {@code final}.  Provider implementations are internal strategy objects
     * and are not designed to be subclassed.
     */
    @ArchTest
    static final ArchRule AI_PROVIDER_IMPLEMENTATIONS_ARE_FINAL =
            classes().that().implement(AiProviderClient.class)
                    .should().haveModifier(JavaModifier.FINAL)
                    .because("provider implementations are internal strategy objects;"
                            + " subclassing them is not supported");

    /**
     * {@code AiSuggestionException} must reside in the {@code ai} package
     * because it is part of that package's public contract.
     */
    @ArchTest
    static final ArchRule AI_EXCEPTION_IN_AI_PACKAGE =
            classes().that().haveSimpleName("AiSuggestionException")
                    .should().resideInAPackage("org.egothor.methodatlas.ai")
                    .because("exceptions are part of the package's public contract"
                            + " and must live alongside the API they describe");

    /**
     * {@code HttpSupport} is an internal HTTP abstraction for the AI subsystem.
     * No class outside the {@code ai} package may access it.
     */
    @ArchTest
    static final ArchRule HTTP_SUPPORT_CONFINED_TO_AI_PACKAGE =
            noClasses().that().resideOutsideOfPackage("org.egothor.methodatlas.ai")
                    .should().accessClassesThat().haveSimpleName("HttpSupport")
                    .because("HttpSupport is an internal ai-subsystem abstraction;"
                            + " callers outside the package must not depend on it");

    /**
     * Root-package classes must not access concrete provider client classes
     * ({@code AnthropicClient}, {@code OllamaClient},
     * {@code OpenAiCompatibleClient}) directly.  All access must go through
     * {@code AiProviderFactory}, which returns the {@code AiProviderClient}
     * interface.
     */
    @ArchTest
    static final ArchRule NO_DIRECT_PROVIDER_ACCESS_FROM_ROOT =
            noClasses().that().resideInAPackage("org.egothor.methodatlas")
                    .should().accessClassesThat().implement(AiProviderClient.class)
                    .because("use AiProviderFactory to obtain an AiProviderClient;"
                            + " root-package code must not depend on concrete provider implementations");

    /**
     * All classes that implement {@link AiProviderClient} must reside in the
     * {@code ai} package.  Placing an implementation in the root package
     * would bypass the factory-based access pattern.
     */
    @ArchTest
    static final ArchRule AI_PROVIDER_IMPLEMENTATIONS_IN_AI_PACKAGE =
            classes().that().implement(AiProviderClient.class)
                    .should().resideInAPackage("org.egothor.methodatlas.ai")
                    .because("all AI provider implementations belong in the ai package"
                            + " and are accessed exclusively through AiProviderFactory");

    /**
     * The {@code ai} subsystem must not depend on the root package.
     *
     * <p>
     * The AI subsystem is designed to be a self-contained component that the
     * scanner core calls into.  Any reverse dependency (ai → root) would
     * create a cyclic coupling that prevents the subsystem from being reused
     * or extracted independently.
     * </p>
     */
    @ArchTest
    static final ArchRule AI_SUBSYSTEM_DOES_NOT_DEPEND_ON_ROOT =
            noClasses().that().resideInAPackage("org.egothor.methodatlas.ai")
                    .should().dependOnClassesThat().resideInAPackage("org.egothor.methodatlas")
                    .because("the AI subsystem must be self-contained; "
                            + "a reverse dependency from ai.* to the root package creates cyclic coupling");

    /**
     * Root-package and API classes must not depend on discovery implementations.
     *
     * <p>
     * The core module uses the {@code TestDiscovery} and {@code SourcePatcher}
     * SPIs exclusively; it must never take a compile-time dependency on a
     * concrete provider such as {@code JavaTestDiscovery} or
     * {@code AnnotationInspector}.  All provider code is loaded via
     * {@link java.util.ServiceLoader} at runtime from the provider JAR.
     * </p>
     */
    @ArchTest
    static final ArchRule CORE_DOES_NOT_DEPEND_ON_DISCOVERY_IMPLEMENTATIONS =
            noClasses().that().resideInAPackage("org.egothor.methodatlas..")
                    .and().resideOutsideOfPackages(
                            "org.egothor.methodatlas.discovery..",
                            "org.egothor.methodatlas.api..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("org.egothor.methodatlas.discovery..")
                    .because("core classes must access discovery providers via SPI only; "
                            + "compile-time coupling to a concrete discovery implementation "
                            + "prevents provider substitution and breaks the plugin model");

    /**
     * All {@link Command} implementations must reside in the
     * {@code org.egothor.methodatlas.command} package.
     *
     * <p>
     * Placing a command handler in the root package or any other package
     * defeats the purpose of the command-handler refactoring and would
     * re-introduce orchestration logic into the routing layer.
     * </p>
     */
    @ArchTest
    static final ArchRule COMMAND_IMPLEMENTATIONS_IN_COMMAND_PACKAGE =
            classes().that().implement(Command.class)
                    .should().resideInAPackage("org.egothor.methodatlas.command")
                    .because("all CLI command handlers must reside in the command package; "
                            + "routing logic belongs in MethodAtlasApp.run(), not in a command class");

    /**
     * Output-emitter classes must not orchestrate AI suggestion calls.
     *
     * <p>
     * {@code OutputEmitter}, {@code SarifEmitter}, and
     * {@code GitHubAnnotationsEmitter} are formatting components.  They
     * receive pre-computed {@code AiMethodSuggestion} data objects as
     * parameters.  If any emitter were to call an {@code *Engine} class
     * directly it would silently cross the boundary between formatting and
     * orchestration, making the emitters impossible to test without a live
     * AI backend.
     * </p>
     */
    @ArchTest
    static final ArchRule OUTPUT_EMITTERS_DO_NOT_ORCHESTRATE_AI =
            noClasses().that().haveSimpleNameEndingWith("Emitter")
                    .should().accessClassesThat().haveSimpleNameEndingWith("Engine")
                    .because("emitters are formatting components; "
                            + "they must receive pre-computed suggestions as data, "
                            + "not call AI engine classes directly");

    /**
     * The {@code emit} package must not depend on the {@code command} package.
     *
     * <p>
     * Emitters are pure formatting components: they receive pre-computed data
     * and write it to an output stream.  Any reverse dependency
     * (emit → command) would entangle output formatting with scan
     * orchestration and make emitters impossible to test in isolation.
     * </p>
     */
    @ArchTest
    static final ArchRule EMIT_DOES_NOT_DEPEND_ON_COMMAND =
            noClasses().that().resideInAPackage("org.egothor.methodatlas.emit")
                    .should().dependOnClassesThat()
                    .resideInAPackage("org.egothor.methodatlas.command")
                    .because("emitters are formatting components that receive pre-computed data; "
                            + "depending on the orchestration layer would prevent isolated testing");

    /**
     * The {@code api} package must not depend on the {@code command} package.
     *
     * <p>
     * The {@code api} module is the SPI contract that all language plugins
     * implement.  It must carry zero upward dependencies so that plugins
     * can be compiled and shipped without pulling in the scanner core.
     * A dependency on {@code command} would force every plugin to include
     * the orchestration layer on its compile classpath.
     * </p>
     */
    @ArchTest
    static final ArchRule API_DOES_NOT_DEPEND_ON_COMMAND =
            noClasses().that().resideInAPackage("org.egothor.methodatlas.api")
                    .should().dependOnClassesThat()
                    .resideInAPackage("org.egothor.methodatlas.command")
                    .because("the api module is an SPI contract; it must have zero upward dependencies "
                            + "so that language plugins can be compiled without the scanner core");

    /**
     * The {@code api} package must not depend on the {@code emit} package.
     *
     * <p>
     * Same rationale as {@link #API_DOES_NOT_DEPEND_ON_COMMAND}: the SPI
     * contract must be usable without pulling in any output-formatting code.
     * </p>
     */
    @ArchTest
    static final ArchRule API_DOES_NOT_DEPEND_ON_EMIT =
            noClasses().that().resideInAPackage("org.egothor.methodatlas.api")
                    .should().dependOnClassesThat()
                    .resideInAPackage("org.egothor.methodatlas.emit")
                    .because("the api module is an SPI contract; it must have zero upward dependencies "
                            + "so that language plugins can be compiled without any output-formatting code");

    /**
     * The {@code api} package must not depend on the {@code ai} package.
     *
     * <p>
     * Language plugins implement the {@code TestDiscovery} and
     * {@code SourcePatcher} SPIs without any knowledge of the AI subsystem.
     * A dependency from {@code api} to {@code ai} would force every plugin
     * author to take a compile dependency on the AI engine, breaking the
     * plugin model.
     * </p>
     */
    @ArchTest
    static final ArchRule API_DOES_NOT_DEPEND_ON_AI =
            noClasses().that().resideInAPackage("org.egothor.methodatlas.api")
                    .should().dependOnClassesThat()
                    .resideInAPackage("org.egothor.methodatlas.ai")
                    .because("the api module is an SPI contract; language plugins must not require "
                            + "the AI subsystem on their compile classpath");

    /**
     * The {@code ai} package must not depend on the {@code emit} package.
     *
     * <p>
     * The AI subsystem produces data objects ({@code AiClassSuggestion},
     * {@code AiMethodSuggestion}).  Formatting those objects for output is
     * the job of the {@code emit} package.  A reverse dependency
     * (ai → emit) would couple the AI engine to a specific output format
     * and prevent the engine from being reused with a different formatter.
     * </p>
     */
    @ArchTest
    static final ArchRule AI_DOES_NOT_DEPEND_ON_EMIT =
            noClasses().that().resideInAPackage("org.egothor.methodatlas.ai")
                    .should().dependOnClassesThat()
                    .resideInAPackage("org.egothor.methodatlas.emit")
                    .because("the ai subsystem produces data objects; "
                            + "depending on emit would couple the AI engine to a specific output format");
}
