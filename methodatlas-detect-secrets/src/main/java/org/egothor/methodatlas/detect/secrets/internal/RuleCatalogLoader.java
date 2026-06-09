package org.egothor.methodatlas.detect.secrets.internal;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.egothor.methodatlas.api.CredentialCategory;
import tools.jackson.databind.JsonNode;
import tools.jackson.dataformat.yaml.YAMLMapper;

/**
 * Parses a YAML credential-detection rule catalog into a {@link RuleCatalog}.
 *
 * @since 4.1.0
 */
public final class RuleCatalogLoader {

    /** Classpath location of the bundled catalog. */
    private static final String BUNDLED = "/credential-rules.yaml";

    private RuleCatalogLoader() {
        // utility class
    }

    /**
     * Loads the catalog bundled in this module.
     *
     * @return the parsed bundled catalog
     * @throws IllegalStateException if the bundled resource is missing
     * @throws UncheckedIOException  if the resource cannot be read
     */
    public static RuleCatalog loadBundled() {
        try (InputStream in = RuleCatalogLoader.class.getResourceAsStream(BUNDLED)) {
            if (in == null) {
                throw new IllegalStateException("Bundled catalog " + BUNDLED + " not found on classpath");
            }
            return parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read bundled catalog", e);
        }
    }

    /**
     * Loads a user-supplied catalog file.
     *
     * @param file path to the YAML catalog; never {@code null}
     * @return the parsed catalog
     * @throws UncheckedIOException if the file cannot be read
     */
    public static RuleCatalog loadFile(Path file) {
        try {
            return parse(Files.readString(file));
        } catch (IOException e) {
            throw new UncheckedIOException("Failed to read catalog " + file, e);
        }
    }

    /**
     * Parses a catalog from a YAML string.
     *
     * @param yaml YAML text; never {@code null}
     * @return the parsed catalog
     */
    public static RuleCatalog loadFromString(String yaml) {
        return parse(yaml);
    }

    /* default */ static RuleCatalog parse(String yaml) {
        JsonNode root = new YAMLMapper().readTree(yaml);
        JsonNode rulesNode = root.get("rules");
        List<CredentialRule> rules = new ArrayList<>();
        if (rulesNode != null && rulesNode.isArray()) {
            for (JsonNode r : rulesNode) {
                rules.add(toRule(r));
            }
        }
        return new RuleCatalog(rules, sha256(yaml));
    }

    private static CredentialRule toRule(JsonNode r) {
        String id = text(r, "id");
        CredentialCategory category = CredentialCategory.valueOf(text(r, "category"));
        List<String> anchors = new ArrayList<>();
        JsonNode anchorsNode = r.get("anchors");
        if (anchorsNode != null && anchorsNode.isArray()) {
            for (JsonNode a : anchorsNode) {
                anchors.add(a.asString());
            }
        }
        String pattern = text(r, "pattern");
        double entropyMin = r.has("entropyMin") ? r.get("entropyMin").asDouble() : 0.0;
        String description = r.has("description") ? text(r, "description") : null;
        String provenance = r.has("provenance") ? text(r, "provenance") : null;
        return new CredentialRule(id, category, anchors, pattern, entropyMin, description, provenance);
    }

    private static String text(JsonNode node, String field) {
        JsonNode v = node.get(field);
        if (v == null) {
            throw new IllegalStateException("Catalog rule missing required field: " + field);
        }
        return v.asString();
    }

    private static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(md.digest(text.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
