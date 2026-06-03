// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.evidence;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.json.JsonMapper;

import org.egothor.methodatlas.ai.AiResponseListener;

/**
 * Buffers AI provider responses during an evidence-pack scan and flushes them
 * as a JSON-Lines file ({@code ai-responses.jsonl}) when the scan completes.
 *
 * <p>
 * This class is package-private because it is an implementation detail of the
 * {@link EvidencePackCommand}; nothing outside the {@code evidence} package
 * should depend on its internal structure.
 * </p>
 *
 * <p>
 * Entries are buffered in insertion order. {@link #flush(Path)} is a no-op
 * when no entries have been captured: an absent file is a stronger signal to
 * auditors than an empty one.
 * </p>
 */
final class AiResponseArchive implements AiResponseListener {

    /** Entries buffered until {@link #flush(Path)} is invoked. */
    private final List<Entry> records = new ArrayList<>();

    /**
     * Appends a single response record to the in-memory buffer.
     *
     * @param contentHash    SHA-256 hash of the analysed class; may be {@code null}
     * @param fqcn           fully qualified class name
     * @param prompt         rendered prompt sent to the provider
     * @param response       response text returned by the provider
     * @param modelId        provider-specific model identifier
     * @param promptTokens   approximate prompt token count; {@code -1} when unknown
     * @param responseTokens approximate response token count; {@code -1} when unknown
     */
    @Override
    @SuppressWarnings("PMD.UseObjectForClearerAPI")
    public void onResponse(String contentHash, String fqcn,
            String prompt, String response,
            String modelId, int promptTokens, int responseTokens) {
        records.add(new Entry(contentHash, fqcn, prompt, response, modelId,
                promptTokens, responseTokens, Instant.now().toString()));
    }

    /**
     * Returns the number of buffered response records.
     *
     * @return record count; non-negative
     */
    /* default */ int size() {
        return records.size();
    }

    /**
     * Writes the buffered records as one JSON object per line to
     * {@code outputFile}, encoded in UTF-8.
     *
     * <p>
     * When no records have been buffered the method returns without creating
     * the file: callers rely on the absence of {@code ai-responses.jsonl} to
     * communicate "no AI was used" to auditors.
     * </p>
     *
     * @param outputFile path of the JSONL file to create or overwrite
     * @throws IOException if writing fails
     */
    /* default */ void flush(Path outputFile) throws IOException {
        if (records.isEmpty()) {
            return;
        }
        JsonMapper mapper = JsonMapper.builder().build();
        // One LinkedHashMap per record is intentional — each row carries
        // independent values and the records list does not retain payloads.
        try (BufferedWriter writer = Files.newBufferedWriter(outputFile, StandardCharsets.UTF_8)) {
            for (Entry record : records) {
                writer.write(mapper.writeValueAsString(toPayload(record)));
                writer.write('\n');
            }
        }
    }

    /**
     * Materialises one record as an insertion-ordered map suitable for
     * JSON serialisation.
     *
     * @param record buffered AI response
     * @return populated map; never {@code null}
     */
    private static Map<String, Object> toPayload(Entry record) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("contentHash", record.contentHash);
        payload.put("fqcn", record.fqcn);
        payload.put("promptTokens", record.promptTokens);
        payload.put("responseTokens", record.responseTokens);
        payload.put("prompt", record.prompt);
        payload.put("response", record.response);
        payload.put("modelId", record.modelId);
        payload.put("timestampUtc", record.timestampUtc);
        return payload;
    }

    /**
     * Internal value type used to capture one provider call. Kept as a
     * private inner class so it cannot leak into the public API of the
     * evidence package.
     */
    private static final class Entry {
        /** SHA-256 of the analysed class; may be {@code null}. */
        private final String contentHash;
        /** Fully qualified class name passed to the provider. */
        private final String fqcn;
        /** Rendered prompt text. */
        private final String prompt;
        /** Raw response text returned by the provider. */
        private final String response;
        /** Provider-specific model identifier; may be {@code null}. */
        private final String modelId;
        /** Approximate prompt token count; {@code -1} when unknown. */
        private final int promptTokens;
        /** Approximate response token count; {@code -1} when unknown. */
        private final int responseTokens;
        /** ISO-8601 UTC capture timestamp. */
        private final String timestampUtc;

        /* default */ Entry(String contentHash, String fqcn, String prompt, String response,
                String modelId, int promptTokens, int responseTokens, String timestampUtc) {
            this.contentHash = contentHash;
            this.fqcn = fqcn;
            this.prompt = prompt;
            this.response = response;
            this.modelId = modelId;
            this.promptTokens = promptTokens;
            this.responseTokens = responseTokens;
            this.timestampUtc = timestampUtc;
        }
    }
}
