package org.egothor.methodatlas.util;

import java.util.List;
import java.util.logging.Logger;

import org.egothor.methodatlas.api.TestDiscoveryConfig;

/**
 * Helpers for reading typed values out of a {@link TestDiscoveryConfig}.
 *
 * <p>
 * Discovery plugins expose a number of integer tuning knobs (pool sizes,
 * timeouts, circuit-breaker limits) via {@link TestDiscoveryConfig#properties()}.
 * This helper centralises the parse-with-fallback logic — previously duplicated
 * verbatim across plugins — including the {@code WARNING} logged when a
 * configured value cannot be parsed.
 * </p>
 */
public final class ConfigProperties {

    private static final Logger LOG = Logger.getLogger(ConfigProperties.class.getName());

    private ConfigProperties() {
        // utility class — no instances
    }

    /**
     * Reads a single integer property from the configuration.
     *
     * <p>
     * Returns {@code defaultValue} when the key is absent, has no values, or the
     * first value is not a valid integer.  An unparseable value is logged at
     * {@code WARNING}.
     * </p>
     *
     * @param config       discovery configuration; never {@code null}
     * @param key          property key
     * @param defaultValue value to use when the key is absent or unparseable
     * @return the parsed integer, or {@code defaultValue}
     */
    public static int parseInt(TestDiscoveryConfig config, String key, int defaultValue) {
        List<String> values = config.properties().get(key);
        if (values == null || values.isEmpty()) {
            return defaultValue;
        }
        try {
            return Integer.parseInt(values.get(0));
        } catch (NumberFormatException e) {
            if (LOG.isLoggable(java.util.logging.Level.WARNING)) {
                LOG.warning("Invalid value for property '" + key + "': " + values.get(0)
                        + " — using default " + defaultValue);
            }
            return defaultValue;
        }
    }
}
