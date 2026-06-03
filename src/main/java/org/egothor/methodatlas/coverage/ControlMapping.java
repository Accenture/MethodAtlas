// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.coverage;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * User-authored mapping from taxonomy tags to compliance-control requirement
 * IDs, loaded from JSON.
 *
 * <p>
 * The mapping is the user's responsibility; the tool records what the file
 * says and does not pass judgement on compliance claims. Validation is
 * structural only — see {@link #load(Path)} for the exact rules.
 * </p>
 *
 * <p>
 * Package-private because the only consumers — {@link
 * ControlCoverageCollector} and {@link ControlCoverageWriter} — live in the
 * same package. The {@code MethodAtlasApp} entry point talks to the package
 * exclusively through {@link CoverageFacade}.
 * </p>
 *
 * @param framework        compliance framework label (e.g. {@code "ASVS"});
 *                         non-blank
 * @param frameworkVersion framework version (e.g. {@code "4.0"}); non-blank
 * @param source           absolute path of the mapping file as a string;
 *                         used for provenance in the output report
 * @param tagToControls    immutable mapping from taxonomy tag to a list of
 *                         control requirements; non-empty
 */
/* default */ record ControlMapping(
        String framework,
        String frameworkVersion,
        String source,
        Map<String, List<ControlEntry>> tagToControls) {

    /** Schema version this implementation accepts. */
    private static final String SCHEMA_VERSION = "1";

    /** Prefix used in every validation error message. */
    private static final String ERROR_PREFIX = "Mapping file '";

    /** Closing token following the file path in every validation error message. */
    private static final String ERROR_SUFFIX = "' ";

    /**
     * Loads and validates a control mapping from a JSON file on disk.
     *
     * <p>
     * The loader is tolerant of unknown top-level fields (forward
     * compatibility) but strict about the documented schema. Violations
     * surface as {@link IllegalArgumentException} with a message that
     * names the file and the failed constraint, so the CLI can render a
     * clear stderr message before exiting with code {@code 2}.
     * </p>
     *
     * <h2>Validation rules</h2>
     * <ol>
     *   <li>{@code schemaVersion} must equal {@code "1"}.</li>
     *   <li>{@code framework} and {@code frameworkVersion} must be present
     *       and non-blank.</li>
     *   <li>{@code tagToControls} must be a non-empty JSON object whose
     *       values are arrays of control entries.</li>
     *   <li>Every entry must have a non-blank {@code id}; {@code chapter}
     *       and {@code chapterTitle} are optional.</li>
     * </ol>
     *
     * <p>
     * The {@link #source()} field of the returned mapping captures the
     * absolute path string so the resulting report can document exactly
     * which file produced the claim being made.
     * </p>
     *
     * @param file path to the mapping JSON; must not be {@code null}
     * @return validated, deeply-unmodifiable mapping
     * @throws IOException              if the file cannot be read or parsed
     * @throws IllegalArgumentException if any validation rule fails
     */
    /* default */ static ControlMapping load(Path file) throws IOException {
        ObjectMapper mapper = JsonMapper.builder()
                .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).build();
        JsonNode root;
        try {
            root = mapper.readTree(file.toFile());
        } catch (JacksonException e) {
            throw new IOException("Cannot read or parse control mapping file '" + file + "'", e);
        }

        validateSchemaVersion(root, file);
        String framework = requireString(root, "framework", file);
        String frameworkVersion = requireString(root, "frameworkVersion", file);
        Map<String, List<ControlEntry>> tagToControls = parseTagMap(root, file);

        return new ControlMapping(framework, frameworkVersion,
                file.toAbsolutePath().toString(),
                Collections.unmodifiableMap(tagToControls));
    }

    /**
     * Asserts that {@code root.schemaVersion} equals {@value #SCHEMA_VERSION}.
     *
     * @param root parsed JSON root
     * @param file source file (used for error context)
     */
    private static void validateSchemaVersion(JsonNode root, Path file) {
        JsonNode node = root.path("schemaVersion");
        if (!node.isTextual() || !SCHEMA_VERSION.equals(node.asText())) {
            throw new IllegalArgumentException(ERROR_PREFIX + file + ERROR_SUFFIX
                    + "has unsupported schemaVersion; expected \"" + SCHEMA_VERSION + "\"");
        }
    }

    /**
     * Extracts a required non-blank string field from {@code root}.
     *
     * @param root  parsed JSON root
     * @param field field name to fetch
     * @param file  source file (used for error context)
     * @return the field's text value
     */
    private static String requireString(JsonNode root, String field, Path file) {
        JsonNode node = root.path(field);
        if (!node.isTextual() || node.asText().isBlank()) {
            throw new IllegalArgumentException(ERROR_PREFIX + file + ERROR_SUFFIX + "is missing required "
                    + "non-blank field '" + field + "'");
        }
        return node.asText();
    }

    /**
     * Parses the {@code tagToControls} object into a {@link LinkedHashMap}
     * preserving JSON declaration order, validating every entry along the way.
     *
     * @param root parsed JSON root
     * @param file source file (used for error context)
     * @return populated map; never empty
     */
    private static Map<String, List<ControlEntry>> parseTagMap(JsonNode root, Path file) {
        JsonNode tagNode = root.path("tagToControls");
        if (!tagNode.isObject() || tagNode.isEmpty()) {
            throw new IllegalArgumentException("Mapping file '" + file
                    + "' must contain a non-empty 'tagToControls' object");
        }
        Map<String, List<ControlEntry>> result = new LinkedHashMap<>();
        tagNode.properties().forEach(entry ->
                result.put(entry.getKey(), parseControlList(entry.getKey(), entry.getValue(), file)));
        return result;
    }

    /**
     * Parses one tag's control-array value, validating every element.
     *
     * @param tag    tag name (for error context)
     * @param array  JSON array node
     * @param file   source file (for error context)
     * @return immutable list of control entries
     */
    private static List<ControlEntry> parseControlList(String tag, JsonNode array, Path file) {
        if (!array.isArray()) {
            throw new IllegalArgumentException(ERROR_PREFIX + file + ERROR_SUFFIX + "tag '" + tag
                    + "' must map to a JSON array of control entries");
        }
        List<ControlEntry> entries = new ArrayList<>(array.size());
        for (JsonNode element : array) {
            entries.add(parseControlEntry(tag, element, file));
        }
        return Collections.unmodifiableList(entries);
    }

    /**
     * Parses a single {@code {id, chapter, chapterTitle}} object.
     *
     * @param tag     tag name (for error context)
     * @param node    JSON object node
     * @param file    source file (for error context)
     * @return populated entry
     */
    private static ControlEntry parseControlEntry(String tag, JsonNode node, Path file) {
        if (!node.isObject()) {
            throw new IllegalArgumentException(ERROR_PREFIX + file + ERROR_SUFFIX + "tag '" + tag
                    + "' entries must be JSON objects");
        }
        JsonNode idNode = node.path("id");
        if (!idNode.isTextual() || idNode.asText().isBlank()) {
            throw new IllegalArgumentException(ERROR_PREFIX + file + ERROR_SUFFIX + "tag '" + tag
                    + "' has an entry with missing or blank 'id'");
        }
        String chapter = optionalString(node, "chapter");
        String chapterTitle = optionalString(node, "chapterTitle");
        return new ControlEntry(idNode.asText(), chapter, chapterTitle);
    }

    /**
     * Reads an optional textual field, returning {@code null} when absent or
     * explicitly JSON-null. Blank strings are preserved so authors can supply
     * empty placeholders intentionally.
     *
     * @param node  JSON object node
     * @param field field name
     * @return field value or {@code null}
     */
    private static String optionalString(JsonNode node, String field) {
        JsonNode child = node.get(field);
        if (child == null || child.isNull() || !child.isTextual()) {
            return null;
        }
        return child.asText();
    }
}
