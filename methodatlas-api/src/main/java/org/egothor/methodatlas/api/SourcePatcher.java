package org.egothor.methodatlas.api;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Language-specific service for writing test-classification metadata back into
 * source files.
 *
 * <p>
 * Implementations are discovered at runtime via {@link java.util.ServiceLoader}.
 * Each implementation handles the source files for one language or framework
 * by translating the language-neutral tag and display-name maps into the
 * correct annotation or attribute syntax for that language.
 * </p>
 *
 * <p>
 * A plugin that does not support source write-back (e.g. a TypeScript plugin
 * where no annotation system exists) simply does not register an implementation.
 * </p>
 *
 * <h2>ServiceLoader registration</h2>
 * <p>
 * Each provider JAR must contain a UTF-8 text file at:<br>
 * {@code META-INF/services/org.egothor.methodatlas.api.SourcePatcher}<br>
 * listing one fully qualified implementation class name per line.
 * </p>
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>{@link #configure(TestDiscoveryConfig)} is called once after the
 *       instance is loaded, before any call to {@link #supports} or
 *       {@link #patch}.</li>
 *   <li>{@link #supports(Path)} is called to determine whether this patcher
 *       handles a given source file.</li>
 *   <li>{@link #patch} is called for each file that {@link #supports} accepted,
 *       with the tags and display names to write.</li>
 * </ol>
 *
 * @see TestDiscovery
 * @see TestDiscoveryConfig
 */
public interface SourcePatcher {

    /**
     * Returns the unique identifier of this source-patcher provider.
     *
     * <p>
     * Mirrors {@link TestDiscovery#pluginId()}: the ID is used to route
     * {@link TestDiscoveryConfig#fileSuffixesFor(String)} entries and to
     * enforce uniqueness at startup. Two patchers with the same ID cause an
     * {@link IllegalStateException} during provider loading.
     * </p>
     *
     * @return non-null, non-empty plugin identifier; must be unique across all
     *         loaded patchers
     * @see TestDiscovery#pluginId()
     */
    String pluginId();

    /**
     * Receives the runtime configuration built from CLI arguments and the YAML
     * config file.
     *
     * <p>
     * Called once after the instance is loaded by {@link java.util.ServiceLoader},
     * before the first call to {@link #supports} or {@link #patch}.
     * The default implementation is a no-op; override when the patcher needs
     * access to {@link TestDiscoveryConfig#testMarkers()} or
     * {@link TestDiscoveryConfig#fileSuffixes()} to decide which files it owns.
     * </p>
     *
     * @param config runtime configuration; never {@code null}
     */
    default void configure(TestDiscoveryConfig config) {
        // no-op by default
    }

    /**
     * Returns {@code true} if this patcher can handle the given source file.
     *
     * <p>
     * The orchestration layer calls this method for every file it wants to patch
     * and forwards the file only to the first patcher that returns {@code true}.
     * Implementations typically check the file-name suffix against the configured
     * {@link TestDiscoveryConfig#fileSuffixes()}.
     * </p>
     *
     * @param sourceFile path to the candidate source file; never {@code null}
     * @return {@code true} if this patcher accepts the file
     */
    boolean supports(Path sourceFile);

    /**
     * Returns the test methods found in the given source file, grouped by their
     * fully qualified class name, or an empty map when the file contains no test
     * methods or when this implementation does not support source inventory.
     *
     * <p>
     * The orchestration layer uses this method to build a source-method inventory
     * for mismatch detection between the CSV desired-state and the actual source
     * tree. Implementations that support source inventory override this method;
     * the default no-op implementation returns an empty map, which causes the
     * file to be excluded from mismatch counting.
     * </p>
     *
     * <p>
     * The map key is the fully qualified class name and the value is the list of
     * simple method names declared in that class.
     * </p>
     *
     * @param sourceFile path to the source file to inspect; never {@code null}
     * @return map from FQCN to list of simple method names; never {@code null};
     *         may be empty
     * @throws IOException if the file cannot be read
     */
    default Map<String, List<String>> discoverMethodsByClass(Path sourceFile) throws IOException {
        return Map.of();
    }

    /**
     * Writes tags and display names back into the source file.
     *
     * <p>
     * The implementation locates each test method named in {@code tagsToApply}
     * or {@code displayNames} and adds or replaces the appropriate annotation or
     * attribute in the source. Methods that appear in neither map are left
     * unchanged. Files that contain no matching methods should be left unchanged.
     * </p>
     *
     * <p>
     * Implementations are expected to preserve all existing source formatting
     * that is not directly related to the tags or display names being written.
     * </p>
     *
     * @param sourceFile   path to the source file to patch; the file is
     *                     overwritten in place on success
     * @param tagsToApply  map from test-method simple name to the list of tag
     *                     values to write; may be empty
     * @param displayNames map from test-method simple name to the display-name
     *                     string to write; may be empty
     * @param diagnostics  writer for human-readable diagnostic output; never
     *                     {@code null}
     * @return number of annotation changes made; {@code 0} if the file was not
     *         modified
     * @throws IOException if the file cannot be read or written
     */
    int patch(Path sourceFile,
              Map<String, List<String>> tagsToApply,
              Map<String, String> displayNames,
              PrintWriter diagnostics) throws IOException;
}
