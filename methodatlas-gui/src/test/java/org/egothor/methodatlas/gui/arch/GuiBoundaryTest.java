// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.gui.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * ArchUnit boundary tests for the {@code methodatlas-gui} Swing module.
 *
 * <p>
 * The GUI module is the only one that consumes the AI subsystem through a
 * user-facing event loop. The risk this test guards against is that a
 * future refactor inadvertently re-introduces a direct dependency on a
 * concrete AI implementation class, defeating the substitution point the
 * {@code AiSuggestionEngine.create} factory establishes.
 * </p>
 *
 * @since 1.0.0
 */
@AnalyzeClasses(
        packages = "org.egothor.methodatlas.gui",
        importOptions = ImportOption.DoNotIncludeTests.class)
class GuiBoundaryTest {

    /**
     * Asserts that no class in the Swing GUI module names
     * {@link org.egothor.methodatlas.ai.AiSuggestionEngineImpl} directly.
     * The GUI must consume the AI engine through the
     * {@code AiSuggestionEngine.create(...)} factory so that the concrete
     * implementation is substitutable (for tests, alternative providers, or
     * future engine variants) without touching GUI code.
     */
    @ArchTest
    static final ArchRule guiMustNotImportAiSuggestionEngineImpl =
            noClasses()
                    .that().resideInAPackage("org.egothor.methodatlas.gui..")
                    .should().dependOnClassesThat()
                    .haveFullyQualifiedName("org.egothor.methodatlas.ai.AiSuggestionEngineImpl")
                    .because("The Swing GUI must consume the AI engine through "
                            + "AiSuggestionEngine.create(...), not through the "
                            + "AiSuggestionEngineImpl constructor. Naming the concrete "
                            + "implementation defeats the substitution point the "
                            + "factory establishes.");
}
