/**
 * Platform-neutral API contracts for MethodAtlas.
 *
 * <p>
 * This package defines the architectural boundaries between the scanner core
 * and its concrete implementations, enabling support for multiple languages
 * and platforms (JVM, .NET, TypeScript, etc.) without changing the core logic.
 * </p>
 *
 * <h2>Input side — service-provider interfaces</h2>
 *
 * <ul>
 * <li>{@link org.egothor.methodatlas.api.TestDiscovery} — SPI that a scanner
 *     implementation must provide; loaded via {@link java.util.ServiceLoader}
 *     and configured at runtime before each scan; yields a stream of
 *     {@link org.egothor.methodatlas.api.DiscoveredMethod} records.</li>
 * <li>{@link org.egothor.methodatlas.api.TestDiscoveryConfig} — runtime
 *     configuration record passed to each {@code TestDiscovery} provider via
 *     {@link org.egothor.methodatlas.api.TestDiscovery#configure}; carries
 *     file-name suffixes, language-neutral test markers (annotation/attribute
 *     names), and a generic properties map for plugin-specific settings.</li>
 * <li>{@link org.egothor.methodatlas.api.DiscoveredMethod} — data carrier
 *     produced by a scanner, conveying per-method metadata and a lazy
 *     {@link org.egothor.methodatlas.api.SourceContent} provider.</li>
 * <li>{@link org.egothor.methodatlas.api.SourceContent} — functional interface
 *     for lazy, shared access to the class source text used by AI analysis.</li>
 * </ul>
 *
 * <h2>Output side</h2>
 *
 * <ul>
 * <li>{@link org.egothor.methodatlas.api.TestMethodSink} — interface that
 *     receives fully enriched (post-AI) test method records; implemented by
 *     the emitters in {@code org.egothor.methodatlas.emit}.</li>
 * <li>{@link org.egothor.methodatlas.api.ScanRecord} — structured record
 *     representing one row of a MethodAtlas CSV output, used by delta
 *     comparison and AI result caching.</li>
 * </ul>
 *
 * @see org.egothor.methodatlas.discovery.jvm.JavaTestDiscovery
 * @see org.egothor.methodatlas.emit.OutputEmitter
 */
package org.egothor.methodatlas.api;
