// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.arch;

import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;
import static com.tngtech.archunit.library.dependencies.SlicesRuleDefinition.slices;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

/**
 * ArchUnit boundary tests guarding the MethodAtlas plugin seam.
 *
 * <p>
 * MethodAtlas discovers language plugins at runtime through
 * {@link java.util.ServiceLoader}. The architectural invariant that makes this
 * model work is that no compile-time dependency exists in either direction
 * outside the public SPI in {@code methodatlas-api}:
 * </p>
 * <ul>
 * <li>Non-plugin code (orchestration, AI subsystem, output emitters, GUI) must
 *     not reference any concrete plugin class. If it did, removing or replacing
 *     a plugin would break the rest of the system at compile time, and the
 *     optional-plugin contract would be a fiction.</li>
 * <li>Plugin code must depend only on {@code methodatlas-api} and external
 *     (non-MethodAtlas) libraries. A plugin that pulled in root, AI, or
 *     {@code emit} types would no longer be a drop-in module — it would chain
 *     its own release to the whole project.</li>
 * <li>Discovery plugins must not depend on each other. Each language plugin is
 *     independently distributable; a cross-plugin import would mean that
 *     omitting one plugin (for example because the host environment lacks
 *     Node.js) could break another plugin's compilation or class loading.</li>
 * </ul>
 *
 * <h2>Frozen-at-current-state policy</h2>
 *
 * <p>
 * These rules are deliberately scoped to invariants that the current codebase
 * already satisfies. They lock in today's correctness so that future changes
 * cannot silently erode the plugin seam. Tightening to additional invariants
 * (for example, no package cycles within the orchestration core or a GUI
 * façade boundary) is sequenced through later phases of the architecture
 * remediation plan.
 * </p>
 *
 * <h2>Scan scope</h2>
 *
 * <p>
 * {@link AnalyzeClasses} imports all production classes under the
 * {@code org.egothor.methodatlas} package tree that are visible on the test
 * classpath. Test classes are excluded via {@link ImportOption.DoNotIncludeTests}
 * so test helpers cannot accidentally satisfy or violate a rule.
 * </p>
 *
 * <p>
 * The eight discovery plugin modules are placed on the root project's test
 * runtime classpath via {@code testRuntimeOnly} in {@code build.gradle}. The
 * deliberately weaker {@code testRuntimeOnly} (rather than
 * {@code testImplementation}) prevents this test class — and any other root
 * test — from forming a compile-time dependency on a plugin, which would
 * itself violate the rules under test.
 * </p>
 *
 * @see org.egothor.methodatlas.api.TestDiscovery
 * @see org.egothor.methodatlas.api.SourcePatcher
 * @since 1.0.0
 */
@AnalyzeClasses(
        packages = "org.egothor.methodatlas",
        importOptions = ImportOption.DoNotIncludeTests.class)
class ArchitectureTest {

    /**
     * Asserts that no non-plugin class declares a compile-time dependency on a
     * class in a discovery-plugin package.
     *
     * <p>
     * Plugins are resolved at runtime via {@link java.util.ServiceLoader} through
     * the SPI in {@code methodatlas-api}. A direct compile-time import from any
     * other module onto a plugin class would defeat the optional, drop-in nature
     * of the plugin architecture and couple the orchestration core to the
     * release cycle of every plugin.
     * </p>
     */
    @ArchTest
    static final ArchRule nonPluginCodeMustNotDependOnDiscoveryPlugins =
            noClasses()
                    .that().resideOutsideOfPackage("org.egothor.methodatlas.discovery..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("org.egothor.methodatlas.discovery..")
                    .because("Discovery plugins are resolved at runtime via ServiceLoader. "
                            + "A compile-time dependency from any non-plugin class onto a "
                            + "plugin class breaks the optional-plugin contract and forces "
                            + "the core to rebuild whenever a plugin changes.");

    /**
     * Asserts that classes in any discovery plugin depend only on
     * {@code methodatlas-api} and external (non-MethodAtlas) libraries.
     *
     * <p>
     * Imports of orchestration core types ({@code org.egothor.methodatlas}),
     * the AI subsystem, output emitters, or GUI types from inside a plugin
     * would chain the plugin's release to the depended-on subsystem and break
     * the SPI-only contract that makes plugins shippable in isolation.
     * </p>
     */
    @ArchTest
    static final ArchRule pluginsMustDependOnlyOnTheApiAndExternalLibraries =
            noClasses()
                    .that().resideInAPackage("org.egothor.methodatlas.discovery..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(
                            "org.egothor.methodatlas",
                            "org.egothor.methodatlas.ai..",
                            "org.egothor.methodatlas.command..",
                            "org.egothor.methodatlas.emit..",
                            "org.egothor.methodatlas.gui..")
                    .because("Discovery plugins must depend only on methodatlas-api and "
                            + "external libraries; any other compile-time dependency from "
                            + "a plugin chains the plugin's release to the depended-on "
                            + "subsystem and breaks the SPI-only contract.");

    /**
     * Asserts that discovery plugins do not depend on each other.
     *
     * <p>
     * Each language plugin (Java, .NET, TypeScript, Go, Python, PowerShell,
     * ABAP, COBOL) is independently distributable. A cross-plugin import would
     * mean omitting one plugin — for example, because Node.js is unavailable
     * for the TypeScript scanner — could break another plugin's compilation or
     * class loading, defeating the optional-plugin model.
     * </p>
     */
    @ArchTest
    static final ArchRule discoveryPluginsMustNotDependOnEachOther =
            slices()
                    .matching("org.egothor.methodatlas.discovery.(*)..")
                    .should().notDependOnEachOther()
                    .because("Each discovery plugin is independently distributable. A "
                            + "cross-plugin compile-time dependency would mean omitting "
                            + "one plugin could break another's compilation or class "
                            + "loading, defeating the optional-plugin model.");

    /**
     * Asserts that the built-in credential-detection plugin
     * ({@code methodatlas-detect-secrets}) depends only on {@code methodatlas-api}
     * and external libraries.
     *
     * <p>
     * The detector is resolved at runtime through the {@code CredentialDetector} SPI.
     * In particular it must not depend on the AI subsystem: deterministic
     * detection is the source of truth and must never call AI. Any dependency on
     * root, AI, command, emit, or GUI types would chain the plugin's release to
     * those subsystems and break the SPI-only contract.
     * </p>
     */
    @ArchTest
    static final ArchRule secretDetectorPluginMustDependOnlyOnTheApiAndExternalLibraries =
            noClasses()
                    .that().resideInAPackage("org.egothor.methodatlas.detect..")
                    .should().dependOnClassesThat()
                    .resideInAnyPackage(
                            "org.egothor.methodatlas",
                            "org.egothor.methodatlas.ai..",
                            "org.egothor.methodatlas.command..",
                            "org.egothor.methodatlas.emit..",
                            "org.egothor.methodatlas.gui..")
                    .because("The credential detector is a drop-in CredentialDetector plugin; it "
                            + "must depend only on methodatlas-api and external libraries, and "
                            + "in particular must never depend on the AI subsystem because "
                            + "deterministic detection must not call AI.");

    /**
     * Asserts that no non-plugin class declares a compile-time dependency on a
     * class in the credential-detection plugin package.
     *
     * <p>
     * Like the discovery plugins, the credential detector is resolved at runtime
     * via {@link java.util.ServiceLoader} through the SPI in
     * {@code methodatlas-api}. Orchestration code programs against the
     * {@code CredentialDetector} interface, never the concrete detector.
     * </p>
     */
    @ArchTest
    static final ArchRule nonPluginCodeMustNotDependOnTheCredentialDetectorPlugin =
            noClasses()
                    .that().resideOutsideOfPackage("org.egothor.methodatlas.detect..")
                    .should().dependOnClassesThat()
                    .resideInAPackage("org.egothor.methodatlas.detect..")
                    .because("The credential detector is resolved at runtime via ServiceLoader. "
                            + "A compile-time dependency from any non-plugin class onto a "
                            + "detector class breaks the optional-plugin contract.");
}
