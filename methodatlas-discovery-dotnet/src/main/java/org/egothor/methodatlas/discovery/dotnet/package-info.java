/**
 * C# test-method discovery and source patching for MethodAtlas.
 *
 * <p>
 * This package provides {@link org.egothor.methodatlas.api.TestDiscovery} and
 * {@link org.egothor.methodatlas.api.SourcePatcher} implementations for .NET
 * source trees (C#). Three major test frameworks are supported:
 * </p>
 * <ul>
 *   <li><strong>xUnit</strong> — {@code [Fact]}, {@code [Theory]};
 *       tags via {@code [Trait("Tag", "value")]};
 *       display names via {@code DisplayName} named parameter</li>
 *   <li><strong>NUnit</strong> — {@code [Test]}, {@code [TestCase]},
 *       {@code [TestCaseSource]};
 *       tags via {@code [Category("value")]}</li>
 *   <li><strong>MSTest</strong> — {@code [TestMethod]},
 *       {@code [DataTestMethod]};
 *       tags via {@code [TestCategory("value")]}</li>
 * </ul>
 *
 * <p>
 * C# source files are parsed with the ANTLR 4–generated {@code CSharpTest}
 * grammar ({@code CSharpTest.g4}), which is focused on structural elements
 * (namespaces, type declarations, method declarations, attribute sections) and
 * treats method bodies as opaque balanced-brace content.
 * Framework detection is automatic from {@code using} directives.
 * </p>
 *
 * <p>
 * <strong>Parser scope:</strong> the grammar is structural and not a full
 * implementation of the C# language specification. Exotic syntax constructs may
 * not be recognised. When a parse error occurs a {@code WARNING} is logged with
 * the file path, line number, character position, and problem description;
 * ANTLR4 error recovery then continues so as many test methods as possible are
 * still discovered. If you encounter a parse warning on valid source, please
 * report it with the relevant code fragment — grammar fixes are localised and
 * typically quick to deliver.
 * </p>
 *
 * <h2>ServiceLoader registration</h2>
 * <p>
 * Both {@link org.egothor.methodatlas.discovery.dotnet.DotNetTestDiscovery}
 * and {@link org.egothor.methodatlas.discovery.dotnet.DotNetSourcePatcher}
 * are registered as ServiceLoader providers via entries in
 * {@code META-INF/services/}. No changes to the core application are required
 * to activate this plugin; add the module as a {@code runtimeOnly} dependency
 * of the root project.
 * </p>
 *
 * <h2>Third-party dependencies</h2>
 * <p>
 * This plugin uses the ANTLR 4 runtime library
 * ({@code org.antlr:antlr4-runtime}), which is licensed under the
 * BSD 3-Clause License. Attribution is provided in the {@code NOTICE} file
 * and in {@code LICENSES/BSD-3-Clause-ANTLR4.txt}.
 * The {@code CSharpTest.g4} grammar is original work licensed under
 * Apache-2.0.
 * </p>
 *
 * @see org.egothor.methodatlas.discovery.dotnet.DotNetTestDiscovery
 * @see org.egothor.methodatlas.discovery.dotnet.DotNetSourcePatcher
 * @see org.egothor.methodatlas.api.TestDiscovery
 * @see org.egothor.methodatlas.api.SourcePatcher
 */
package org.egothor.methodatlas.discovery.dotnet;
