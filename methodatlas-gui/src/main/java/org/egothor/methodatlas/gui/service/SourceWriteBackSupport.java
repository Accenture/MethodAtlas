package org.egothor.methodatlas.gui.service;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.ServiceLoader;
import java.util.stream.Collectors;

import org.egothor.methodatlas.api.SourcePatcher;
import org.egothor.methodatlas.api.TestDiscoveryConfig;

/**
 * GUI helper that loads all {@link SourcePatcher} implementations registered
 * via {@link ServiceLoader}, configures them with the active
 * {@link TestDiscoveryConfig}, and exposes lookup helpers used by the
 * panels and the save-all flow.
 *
 * <p>
 * Source write-back is only supported for languages whose discovery plugin
 * ships a {@link SourcePatcher}. At the time of writing this means
 * <strong>Java</strong> (handled by {@code methodatlas-discovery-jvm}) and
 * <strong>C#</strong> (handled by {@code methodatlas-discovery-dotnet}).
 * For every other discovered language the GUI prevents staging and
 * the Save All / apply-tags flows skip the file with a clear notice.
 * </p>
 *
 * <p>
 * Instances are immutable after construction. Rebuild a fresh instance
 * whenever the {@link TestDiscoveryConfig} changes (typically at the start
 * of every scan).
 * </p>
 *
 * @see SourcePatcher
 * @see TestDiscoveryConfig
 */
public final class SourceWriteBackSupport {

    /**
     * Mapping from a patcher's {@code pluginId()} to the human-readable
     * language label used in {@link #supportedLanguagesLabel()}. Keys must
     * match the {@code pluginId()} return values of the actual
     * {@link SourcePatcher} implementations:
     * {@code JavaSourcePatcher.pluginId() == "java"} and
     * {@code DotNetSourcePatcher.pluginId() == "dotnet"}.
     */
    private static final Map<String, String> LANGUAGE_LABELS = Map.of(
            "java",   "Java",
            "dotnet", "C#"
    );

    private final List<SourcePatcher> patchers;

    /**
     * Loads all {@link SourcePatcher} providers from the classpath and
     * configures each with {@code config}.
     *
     * @param config runtime configuration forwarded to every patcher; never
     *               {@code null}
     */
    public SourceWriteBackSupport(TestDiscoveryConfig config) {
        List<SourcePatcher> loaded = new ArrayList<>();
        ServiceLoader.load(SourcePatcher.class).forEach(p -> {
            p.configure(config);
            loaded.add(p);
        });
        this.patchers = List.copyOf(loaded);
    }

    /**
     * Returns the list of configured patchers, in ServiceLoader iteration
     * order. The list is unmodifiable.
     *
     * @return list of loaded {@link SourcePatcher}s; never {@code null}; may
     *         be empty if no provider jars are on the classpath
     */
    public List<SourcePatcher> patchers() {
        return patchers;
    }

    /**
     * Returns {@code true} if any loaded patcher accepts the given source file.
     *
     * @param sourceFile path to a source file; may be {@code null}
     * @return {@code true} if at least one patcher's
     *         {@link SourcePatcher#supports(Path)} returns {@code true};
     *         {@code false} when {@code sourceFile} is {@code null} or no
     *         patcher accepts it
     */
    public boolean supports(Path sourceFile) {
        if (sourceFile == null) {
            return false;
        }
        for (SourcePatcher p : patchers) {
            if (p.supports(sourceFile)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Returns the first loaded patcher that accepts {@code sourceFile}, or
     * {@code null} when none does.
     *
     * @param sourceFile path to a source file; never {@code null}
     * @return matching patcher or {@code null}
     */
    public SourcePatcher findPatcher(Path sourceFile) {
        for (SourcePatcher p : patchers) {
            if (p.supports(sourceFile)) {
                return p;
            }
        }
        return null;
    }

    /**
     * Returns a human-readable description of the languages currently
     * supported for source write-back, e.g. {@code "Java, C#"}.
     *
     * <p>
     * The label is built from each patcher's {@link SourcePatcher#pluginId()}
     * by looking it up in a built-in mapping. Unrecognised plugin IDs are
     * shown as-is in uppercase. When no patchers are loaded the method
     * returns {@code "(none)"}.
     * </p>
     *
     * @return non-{@code null} comma-separated label
     */
    public String supportedLanguagesLabel() {
        if (patchers.isEmpty()) {
            return "(none)";
        }
        return patchers.stream()
                .map(p -> LANGUAGE_LABELS.getOrDefault(p.pluginId(),
                        p.pluginId().toUpperCase(Locale.ROOT)))
                .distinct()
                .collect(Collectors.joining(", "));
    }
}
