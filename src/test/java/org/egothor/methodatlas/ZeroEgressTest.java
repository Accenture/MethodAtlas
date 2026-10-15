// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import org.egothor.methodatlas.ai.AiOptions;
import org.egothor.methodatlas.ai.AiProvider;
import org.egothor.methodatlas.ai.AiProviderClient;
import org.egothor.methodatlas.ai.AiProviderFactory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Air-gapped (zero-egress) assurance test for the Tier&nbsp;1 / manual tiers.
 *
 * <p>
 * The air-gapped claim was previously evidenced only by payload-content tests
 * (what the AI prompt does and does not contain) and by the ArchUnit rule
 * {@code HTTP_SUPPORT_CONFINED_TO_AI_PACKAGE}, which proves the HTTP chokepoint
 * is confined to the {@code ai} package but says nothing about whether a scan
 * opens a non-loopback connection. This test closes that gap (SABSA attribute
 * BA-011 Credible, objective CO-012, service SS-006, evidence EV-006) with a
 * genuine egress assertion at the HTTP layer.
 * </p>
 *
 * <h2>Mechanism</h2>
 *
 * <p>
 * MethodAtlas performs every outbound AI call through a single
 * {@code java.net.http.HttpClient} built by {@code HttpSupport} — the sole
 * network chokepoint, as fixed by the ArchUnit confinement rule. A
 * {@code java.net.http.HttpClient} built with default settings consults the
 * JVM-default {@link ProxySelector} once per request to decide routing. This
 * test installs a recording {@link ProxySelector} as the default and inspects
 * the target host of every request the chokepoint issues.
 * </p>
 *
 * <p>
 * The monitor is <em>self-validating</em>: {@link #tier1LocalProvider_onlyConnectsToLoopback}
 * drives a real Tier&nbsp;1 provider client against a loopback
 * {@link ServerSocket} sentinel and proves (a) that the recording selector does
 * observe the chokepoint's connections and (b) that the sentinel accepted a
 * connection from a loopback address. Only because the monitor demonstrably
 * sees egress is the "zero non-loopback targets" assertion in the structural and
 * manual paths meaningful rather than vacuous.
 * </p>
 */
@Tag("unit")
@Tag("security")
@Tag("air-gapped")
class ZeroEgressTest {

    private ProxySelector previousDefault;
    private RecordingProxySelector monitor;

    @BeforeEach
    void installMonitor() {
        previousDefault = ProxySelector.getDefault();
        monitor = new RecordingProxySelector();
        ProxySelector.setDefault(monitor);
    }

    @AfterEach
    void restoreDefault() {
        ProxySelector.setDefault(previousDefault);
    }

    // -------------------------------------------------------------------------
    // (c) Self-validation: a Tier-1 local provider connects only to loopback,
    //     and the monitor demonstrably observes that connection.
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("a Tier-1 local provider connects only to a loopback sentinel, and the monitor sees it")
    @Tag("positive")
    void tier1LocalProvider_onlyConnectsToLoopback() throws Exception {
        List<String> serverRemoteHosts = new CopyOnWriteArrayList<>();
        try (ServerSocket sentinel = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
            int port = sentinel.getLocalPort();
            Thread server = new Thread(() -> acceptOneLoopbackRequest(sentinel, serverRemoteHosts), "loopback-sentinel");
            server.setDaemon(true);
            server.start();

            AiOptions options = AiOptions.builder()
                    .enabled(true)
                    .provider(AiProvider.OLLAMA)
                    .baseUrl("http://127.0.0.1:" + port)
                    .timeout(Duration.ofSeconds(5))
                    .build();
            AiProviderClient client = AiProviderFactory.create(options);

            // The response parse may or may not succeed; only the connection target matters here.
            // AiSuggestionException is checked, so catch Exception broadly: a parse failure against a
            // stub that is not a real Ollama is irrelevant to the egress assertion.
            try {
                client.suggestForClass("com.acme.FooTest", "classify this");
            } catch (Exception expectedPossibly) {
                // Intentionally ignored — the connection was still issued and observed.
            }
            server.join(5000);
        }

        List<String> targets = monitor.targets();
        assertFalse(targets.isEmpty(),
                "the recording ProxySelector must observe the chokepoint's connection — "
                        + "otherwise the zero-egress assertions would be vacuous");
        assertTrue(targets.stream().allMatch(ZeroEgressTest::isLoopback),
                "a Tier-1 local provider must connect only to loopback; saw: " + targets);
        assertTrue(serverRemoteHosts.stream().allMatch(ZeroEgressTest::isLoopback),
                "the sentinel must have accepted the connection from a loopback address; saw: " + serverRemoteHosts);
    }

    // -------------------------------------------------------------------------
    // (a) Structural-only scan: no non-loopback connection at all.
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("a structural-only scan opens no non-loopback connection")
    @Tag("positive")
    void structuralScan_opensNoNonLoopbackConnection(@TempDir Path tempDir) throws Exception {
        Path sourceDir = tempDir.resolve("src");
        Files.createDirectories(sourceDir);
        copyFixture(sourceDir, "AccessControlServiceTest.java");

        runApp(new String[] { sourceDir.toString() });

        assertNoNonLoopbackEgress();
    }

    // -------------------------------------------------------------------------
    // (b) Manual Tier-2 prepare: no non-loopback connection at all.
    // -------------------------------------------------------------------------

    @Test
    @DisplayName("the manual Tier-2 prepare path opens no non-loopback connection")
    @Tag("positive")
    void manualPrepare_opensNoNonLoopbackConnection(@TempDir Path tempDir) throws Exception {
        Path sourceDir = tempDir.resolve("src");
        Path workDir = tempDir.resolve("work");
        Files.createDirectories(sourceDir);
        copyFixture(sourceDir, "AccessControlServiceTest.java");

        runApp(new String[] { "-manual-prepare", workDir.toString(), workDir.toString(), sourceDir.toString() });

        assertNoNonLoopbackEgress();
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void assertNoNonLoopbackEgress() {
        List<String> nonLoopback = monitor.targets().stream()
                .filter(host -> !isLoopback(host))
                .toList();
        assertTrue(nonLoopback.isEmpty(),
                "the path must not open any non-loopback outbound connection; saw: " + nonLoopback);
    }

    private static void runApp(String[] args) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (PrintWriter out = new PrintWriter(new OutputStreamWriter(baos, StandardCharsets.UTF_8), true)) {
            MethodAtlasApp.run(args, out);
        }
    }

    private static void copyFixture(Path destDir, String fixtureFileName) throws IOException {
        String resourcePath = "/fixtures/" + fixtureFileName + ".txt";
        try (InputStream in = ZeroEgressTest.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new IOException("Missing test resource: " + resourcePath);
            }
            Files.copy(in, destDir.resolve(fixtureFileName));
        }
    }

    /**
     * Accepts a single connection on the loopback sentinel, records the peer
     * address, drains the request headers, and replies with a minimal Ollama-shaped
     * JSON body so the client's response parser is satisfied.
     *
     * @param sentinel          the loopback server socket
     * @param serverRemoteHosts collects the remote host address of the accepted peer
     */
    private static void acceptOneLoopbackRequest(ServerSocket sentinel, List<String> serverRemoteHosts) {
        try (Socket peer = sentinel.accept()) {
            SocketAddress remote = peer.getRemoteSocketAddress();
            if (remote instanceof InetSocketAddress isa) {
                serverRemoteHosts.add(isa.getAddress().getHostAddress());
            }
            BufferedReader reader = new BufferedReader(
                    new InputStreamReader(peer.getInputStream(), StandardCharsets.UTF_8));
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                // Drain request line and headers; the body is irrelevant to the egress assertion.
            }
            String body = "{\"message\":{\"content\":\"{\\\"className\\\":\\\"com.acme.FooTest\\\",\\\"methods\\\":[]}\"}}";
            byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);
            OutputStream out = peer.getOutputStream();
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: "
                    + bodyBytes.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(bodyBytes);
            out.flush();
        } catch (IOException ignored) {
            // The client may close early; the connection was still observed, which is all the test needs.
        }
    }

    /**
     * Returns whether {@code host} resolves to a loopback address (127.0.0.0/8 or
     * {@code ::1}). A host that cannot be resolved is treated as non-loopback so
     * that an unexpected external target can never be mistaken for loopback.
     *
     * @param host host name or literal address from a request URI
     * @return {@code true} only when {@code host} is a loopback address
     */
    private static boolean isLoopback(String host) {
        if (host == null) {
            return false;
        }
        try {
            return InetAddress.getByName(host).isLoopbackAddress();
        } catch (IOException unresolvable) {
            return false;
        }
    }

    /**
     * A {@link ProxySelector} that records the target host of every request the
     * JVM routes through it, while leaving the connection to proceed directly
     * ({@link Proxy#NO_PROXY}). Installed as the JVM default so it observes every
     * request issued by the {@code HttpSupport} chokepoint.
     */
    private static final class RecordingProxySelector extends ProxySelector {

        private final List<String> targets = new CopyOnWriteArrayList<>();

        @Override
        public List<Proxy> select(URI uri) {
            if (uri != null && uri.getHost() != null) {
                targets.add(uri.getHost());
            }
            return List.of(Proxy.NO_PROXY);
        }

        @Override
        public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
            // No-op: a failed direct connection is still a recorded target via select().
        }

        /**
         * Returns an immutable snapshot of the hosts observed so far.
         *
         * @return every request target host recorded, in observation order
         */
        List<String> targets() {
            return List.copyOf(targets);
        }
    }
}
