package org.egothor.methodatlas.emit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.io.UncheckedIOException;
import java.io.Writer;
import java.util.List;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.egothor.methodatlas.ai.AiMethodSuggestion;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link JsonEmitter}, focused on the AI enrichment fields and
 * the drift-detection columns shared with the CSV and SARIF outputs.
 */
@Tag("unit")
class JsonEmitterTest {

    @Test
    @DisplayName("drift detection adds tag_ai_drift, tags_added, and tags_removed fields")
    @Tag("positive")
    void driftDetect_addsDriftAndTagDeltaFields() throws Exception {
        JsonEmitter emitter = new JsonEmitter(true, false, false, true, false);
        AiMethodSuggestion suggestion = new AiMethodSuggestion(
                "m", true, null, List.of("security", "crypto"), "reason", 0.0, 0.0);
        emitter.record("com.acme.Foo", "m", 1, 5, null, List.of("security", "perf"), "", suggestion, null);

        JsonNode record = firstRecord(emitter);
        assertEquals("none", record.path("tag_ai_drift").asText(),
                "security tag and AI verdict agree");
        assertEquals("perf", record.path("tags_added").asText(),
                "tags_added lists source tags the AI did not suggest");
        assertEquals("crypto", record.path("tags_removed").asText(),
                "tags_removed lists AI tags absent from source");
    }

    @Test
    @DisplayName("without drift detection the tag-delta fields are omitted")
    @Tag("edge-case")
    void withoutDriftDetect_tagDeltaFieldsAbsent() throws Exception {
        JsonEmitter emitter = new JsonEmitter(true, false, false, false, false);
        AiMethodSuggestion suggestion = new AiMethodSuggestion(
                "m", true, null, List.of("security"), "reason", 0.0, 0.0);
        emitter.record("com.acme.Foo", "m", 1, 5, null, List.of("security"), "", suggestion, null);

        JsonNode record = firstRecord(emitter);
        assertTrue(record.path("tag_ai_drift").isMissingNode(), "tag_ai_drift omitted without -drift-detect");
        assertTrue(record.path("tags_added").isMissingNode(), "tags_added omitted without -drift-detect");
        assertTrue(record.path("tags_removed").isMissingNode(), "tags_removed omitted without -drift-detect");
    }

    @Test
    @DisplayName("flush surfaces a stream write error instead of silently truncating")
    @Tag("edge-case")
    void flush_surfacesWriteError() {
        JsonEmitter emitter = new JsonEmitter(false, false, false, false, false);
        emitter.record("com.acme.Foo", "m", 1, 5, null, List.of(), "", null, null);
        PrintWriter failing = new PrintWriter(new Writer() {
            @Override
            public void write(char[] cbuf, int off, int len) throws IOException {
                throw new IOException("disk full");
            }

            @Override
            public void flush() {
                // no-op
            }

            @Override
            public void close() {
                // no-op
            }
        });
        assertThrows(UncheckedIOException.class, () -> emitter.flush(failing),
                "a stream write error must surface, not be swallowed");
    }

    private static JsonNode firstRecord(JsonEmitter emitter) throws Exception {
        StringWriter sw = new StringWriter();
        try (PrintWriter pw = new PrintWriter(sw)) {
            emitter.flush(pw);
        }
        return new ObjectMapper().readTree(sw.toString()).get(0);
    }
}
