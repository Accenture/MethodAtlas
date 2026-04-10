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
}
