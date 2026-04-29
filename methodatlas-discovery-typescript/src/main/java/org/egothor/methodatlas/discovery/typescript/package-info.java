/**
 * TypeScript and JavaScript test-method discovery for MethodAtlas.
 *
 * <p>
 * This package implements the {@link org.egothor.methodatlas.api.TestDiscovery}
 * SPI for TypeScript and JavaScript source trees.  Tests are identified by
 * function-call names rather than annotations: Jest, Vitest, and Mocha all use
 * {@code test("name", fn)} or {@code it("name", fn)} patterns.
 * {@code describe} / {@code context} / {@code suite} blocks are understood as
 * scope containers and their names are recorded alongside each test.
 * </p>
 *
 * <h2>Architecture</h2>
 *
 * <p>
 * Parsing is delegated to a Node.js sub-process that runs a self-contained
 * JavaScript bundle ({@code ts-scanner.bundle.js}) extracted from the JAR at
 * startup.  The bundle is built at compile time with
 * <a href="https://esbuild.github.io/">esbuild</a> from
 * {@code src/main/node/ts-scanner.js} and its transitive dependency
 * {@code @typescript-eslint/typescript-estree}.  No network access or
 * {@code npm install} occurs at runtime.
 * </p>
 *
 * <p>
 * The Java side manages a pool of long-lived worker processes
 * ({@link TypeScriptWorkerPool}).  Each worker communicates over
 * {@code stdin}/{@code stdout} using a newline-delimited JSON protocol
 * (NDJSON).  One JSON line is written per request; one JSON line is read per
 * response.  Workers are restarted automatically after failures subject to a
 * circuit-breaker limit ({@link WorkerCircuitBreaker}).
 * </p>
 *
 * <h2>Security model</h2>
 *
 * <ul>
 * <li><b>Bundle integrity</b> — the SHA-256 of the bundled JS is computed at
 *     build time and embedded in the JAR manifest under the key
 *     {@code TS-Scanner-Bundle-SHA256}.  {@link BundleIntegrity} verifies this
 *     hash before the first worker is started; a mismatch causes the plugin to
 *     refuse execution with an {@link IllegalStateException}.</li>
 * <li><b>File-system isolation</b> — when Node.js 20 or later is detected,
 *     workers are started with
 *     {@code --experimental-permission --allow-fs-read=<scan-root>} so that
 *     the worker process can only read files under the directories being
 *     scanned.</li>
 * <li><b>No runtime npm</b> — the bundle is fully self-contained; no package
 *     manager is invoked at runtime, eliminating supply-chain risks from
 *     transitive npm dependencies.</li>
 * <li><b>Subprocess audit trail</b> — every worker start, stop, kill, and
 *     restart is logged at {@code INFO} level including the bundle version,
 *     SHA-256 prefix, Node.js version, and OS process ID.</li>
 * </ul>
 *
 * <h2>Timeout and circuit-breaker policy</h2>
 *
 * <ul>
 * <li><b>Per-file timeout</b> — each scan request to a worker is bounded by a
 *     configurable timeout (default 30 s, property
 *     {@code typescript.workerTimeoutSec}).  A worker that does not respond
 *     within the window is killed and restarted.</li>
 * <li><b>Circuit breaker</b> — if a worker restarts more than
 *     {@code typescript.maxConsecutiveRestarts} times (default 5) within a
 *     {@code typescript.restartWindowSec} window (default 60 s) the entire
 *     plugin is disabled for the remainder of the run and a {@code WARNING} is
 *     emitted.  All remaining TypeScript files are skipped rather than
 *     attempted against a broken worker.</li>
 * </ul>
 *
 * <h2>ServiceLoader registration</h2>
 *
 * <p>
 * {@link TypeScriptTestDiscovery} is registered as a
 * {@link org.egothor.methodatlas.api.TestDiscovery} provider via
 * {@code META-INF/services/org.egothor.methodatlas.api.TestDiscovery}.
 * Placing the {@code methodatlas-discovery-typescript} JAR on the classpath
 * activates the plugin automatically; no code change in the application is
 * required.
 * </p>
 */
package org.egothor.methodatlas.discovery.typescript;
