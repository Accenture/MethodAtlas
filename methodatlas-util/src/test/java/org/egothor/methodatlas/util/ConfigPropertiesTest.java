package org.egothor.methodatlas.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;

import org.egothor.methodatlas.api.TestDiscoveryConfig;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ConfigProperties}.
 */
class ConfigPropertiesTest {

    private static TestDiscoveryConfig configWith(Map<String, List<String>> props) {
        TestDiscoveryConfig config = mock(TestDiscoveryConfig.class);
        when(config.properties()).thenReturn(props);
        return config;
    }

    @Test
    void parseInt_presentAndValid_returnsParsedValue() {
        TestDiscoveryConfig config = configWith(Map.of("pool.size", List.of("8")));
        assertEquals(8, ConfigProperties.parseInt(config, "pool.size", 2));
    }

    @Test
    void parseInt_absentKey_returnsDefault() {
        TestDiscoveryConfig config = configWith(Map.of());
        assertEquals(2, ConfigProperties.parseInt(config, "pool.size", 2));
    }

    @Test
    void parseInt_emptyValueList_returnsDefault() {
        TestDiscoveryConfig config = configWith(Map.of("pool.size", List.of()));
        assertEquals(2, ConfigProperties.parseInt(config, "pool.size", 2));
    }

    @Test
    void parseInt_unparseableValue_returnsDefault() {
        TestDiscoveryConfig config = configWith(Map.of("pool.size", List.of("not-a-number")));
        assertEquals(2, ConfigProperties.parseInt(config, "pool.size", 2));
    }

    @Test
    void parseInt_usesFirstValue() {
        TestDiscoveryConfig config = configWith(Map.of("pool.size", List.of("5", "9")));
        assertEquals(5, ConfigProperties.parseInt(config, "pool.size", 2));
    }
}
