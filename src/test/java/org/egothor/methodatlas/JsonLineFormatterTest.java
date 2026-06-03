// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.LogRecord;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JsonLineFormatter}.
 *
 * <p>
 * The formatter is the audit-trail-friendly JSON-line output for the
 * MethodAtlas log records. Tests parse the formatter's output back through
 * Jackson to verify that each documented field appears and that escape
 * handling produces valid JSON across every interesting character class.
 * </p>
 *
 * @since 1.0.0
 */
class JsonLineFormatterTest {

    private final JsonLineFormatter formatter = new JsonLineFormatter();
    private final ObjectMapper mapper = new ObjectMapper();

    @AfterEach
    void clearRunContext() {
        ScanRunContext.clear();
    }

    private static LogRecord record(Level level, String message) {
        LogRecord r = new LogRecord(level, message);
        r.setLoggerName("org.example.Test");
        r.setInstant(Instant.ofEpochMilli(1_700_000_000_000L)); // fixed timestamp for determinism
        return r;
    }

    // ── Schema fields ────────────────────────────────────────────────────────

    @Test
    void output_endsWithNewline() {
        String out = formatter.format(record(Level.INFO, "hello"));
        assertTrue(out.endsWith("\n"), "Each record must terminate with a newline");
    }

    @Test
    void output_singleLine() {
        String out = formatter.format(record(Level.INFO, "hello"));
        // Strip the trailing newline; the body must have no embedded newlines.
        String body = out.substring(0, out.length() - 1);
        assertFalse(body.contains("\n"),
                "Body must be on a single line; multi-line records break log aggregation");
    }

    @Test
    void output_isValidJsonWithExpectedFields() throws IOException {
        String json = formatter.format(record(Level.INFO, "hello")).trim();
        JsonNode node = mapper.readTree(json);

        assertEquals("INFO", node.get("level").asString());
        assertEquals("org.example.Test", node.get("logger").asString());
        assertEquals("hello", node.get("message").asString());
        assertEquals("2023-11-14T22:13:20Z", node.get("timestamp").asString(),
                "Timestamp must be ISO-8601 derived from LogRecord.getMillis()");
        assertTrue(node.has("thread"), "Thread name must always be present");
    }

    @Test
    void output_omitsRunIdWhenNoScanRunSet() throws IOException {
        String json = formatter.format(record(Level.INFO, "hello")).trim();
        JsonNode node = mapper.readTree(json);
        assertFalse(node.has("runId"),
                "runId must be omitted when no ScanRun is set on the current thread");
    }

    @Test
    void output_includesRunIdWhenScanRunSet() throws IOException {
        ScanRun run = ScanRun.create("dev", "config-text");
        ScanRunContext.set(run);

        String json = formatter.format(record(Level.INFO, "hello")).trim();
        JsonNode node = mapper.readTree(json);

        assertEquals(run.runId(), node.get("runId").asString(),
                "runId must come from ScanRunContext.current()");
    }

    @Test
    void output_includesThrownWhenExceptionAttached() throws IOException {
        LogRecord r = record(Level.WARNING, "boom");
        r.setThrown(new IllegalStateException("synthetic failure"));

        String json = formatter.format(r).trim();
        JsonNode node = mapper.readTree(json);

        assertTrue(node.has("thrown"));
        assertTrue(node.get("thrown").asString().contains("IllegalStateException"));
        assertTrue(node.get("thrown").asString().contains("synthetic failure"));
    }

    @Test
    void output_omitsThrownWhenNoExceptionAttached() throws IOException {
        String json = formatter.format(record(Level.INFO, "hello")).trim();
        JsonNode node = mapper.readTree(json);
        assertFalse(node.has("thrown"),
                "thrown must be omitted when LogRecord.getThrown() is null");
    }

    // ── Escape handling ──────────────────────────────────────────────────────

    @Test
    void output_escapesDoubleQuotes() throws IOException {
        String json = formatter.format(record(Level.INFO, "he said \"hi\"")).trim();
        JsonNode node = mapper.readTree(json);
        assertEquals("he said \"hi\"", node.get("message").asString(),
                "Embedded double quotes must round-trip through JSON parsing");
    }

    @Test
    void output_escapesNewlinesAsBackslashN() throws IOException {
        String json = formatter.format(record(Level.INFO, "line1\nline2")).trim();
        JsonNode node = mapper.readTree(json);
        assertEquals("line1\nline2", node.get("message").asString(),
                "Embedded newlines must round-trip as \\n");
    }

    @Test
    void output_escapesBackslash() throws IOException {
        String json = formatter.format(record(Level.INFO, "path\\to\\file")).trim();
        JsonNode node = mapper.readTree(json);
        assertEquals("path\\to\\file", node.get("message").asString(),
                "Embedded backslashes must round-trip as \\\\");
    }

    @Test
    void output_escapesControlCharactersAsUnicode() throws IOException {
        String json = formatter.format(record(Level.INFO, "bell:done")).trim();
        JsonNode node = mapper.readTree(json);
        assertEquals("bell:done", node.get("message").asString(),
                "Control characters must round-trip via \\uXXXX escapes");
    }

    @Test
    void output_handlesNullLoggerNameAsEmpty() throws IOException {
        LogRecord r = new LogRecord(Level.INFO, "no logger");
        r.setInstant(Instant.ofEpochMilli(1_700_000_000_000L));
        // Leave loggerName as null intentionally.

        String json = formatter.format(r).trim();
        JsonNode node = mapper.readTree(json);
        assertEquals("", node.get("logger").asString(),
                "Null logger name must render as an empty string, not 'null'");
    }
}
