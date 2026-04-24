package org.egothor.methodatlas;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.classes;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import org.egothor.methodatlas.ai.AiProviderClient;

import com.tngtech.archunit.core.domain.JavaModifier;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * ArchUnit structural tests that enforce package-level constraints for the
 * MethodAtlas architecture.
 *
 * <p>
 * Two packages exist in this project:
 * </p>
 * <ul>
 * <li>{@code org.egothor.methodatlas} – scanner core, CLI, output</li>
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
     * {@code CliArgs} must not depend on the JavaParser scan engine.
     *
     * <p>
     * {@code CliArgs} is a pure argument-parsing utility.  Pulling in the
     * JavaParser library would mean that changing the parser API could force
     * argument-parsing changes — a violation of the single-responsibility
     * principle and a source of surprising transitive coupling.
     * </p>
     */
    @ArchTest
    static final ArchRule CLI_ARGS_DOES_NOT_DEPEND_ON_SCAN_ENGINE =
            noClasses().that().haveSimpleName("CliArgs")
                    .should().dependOnClassesThat().resideInAPackage("com.github.javaparser..")
                    .because("CliArgs is a pure argument-parsing utility; "
                            + "it must not depend on the JavaParser scan engine");

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
}
