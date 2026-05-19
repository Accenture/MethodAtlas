/**
 * PowerShell Pester test discovery provider for MethodAtlas.
 *
 * <p>
 * This package provides a {@link org.egothor.methodatlas.api.TestDiscovery}
 * implementation for PowerShell source trees using the
 * <a href="https://pester.dev/">Pester</a> test framework. Test files with
 * the {@code .Tests.ps1} or {@code .Test.ps1} suffix are scanned for
 * {@code It "..."} blocks; one {@link org.egothor.methodatlas.api.DiscoveredMethod}
 * is emitted per block.
 * </p>
 *
 * <h2>Pester constructs</h2>
 * <ul>
 *   <li>{@code It "name"} — primary test block (single or double quoted)</li>
 *   <li>{@code -Tag "value"} on the {@code It} line — source-level tag extraction</li>
 *   <li>{@code Describe "name"} / {@code Context "name"} — container blocks
 *       (recognised for future scope-tracking; not yet used for tag inheritance)</li>
 * </ul>
 *
 * <h2>ServiceLoader registration</h2>
 * <p>
 * {@link org.egothor.methodatlas.discovery.powershell.PowerShellTestDiscovery}
 * is registered as a ServiceLoader provider via
 * {@code META-INF/services/org.egothor.methodatlas.api.TestDiscovery}. No
 * changes to the core application are required to activate this plugin; add
 * the module as a {@code runtimeOnly} dependency of the root project.
 * </p>
 *
 * @see org.egothor.methodatlas.discovery.powershell.PowerShellTestDiscovery
 * @see org.egothor.methodatlas.api.TestDiscovery
 */
package org.egothor.methodatlas.discovery.powershell;
