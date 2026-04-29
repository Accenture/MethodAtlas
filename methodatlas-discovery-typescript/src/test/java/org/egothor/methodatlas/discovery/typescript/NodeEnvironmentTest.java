package org.egothor.methodatlas.discovery.typescript;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Unit tests for {@link NodeEnvironment#parseMajorVersion(String)}.
 *
 * <p>
 * The full {@link NodeEnvironment} constructor requires a live Node.js process
 * on the PATH and is exercised by integration tests only.  These unit tests
 * target the static parsing helper in isolation.
 * </p>
 */
class NodeEnvironmentTest {

    @ParameterizedTest(name = "parseMajorVersion({0}) = {1}")
    @CsvSource({
        "v20.11.0, 20",
        "v18.0.0,  18",
        "v22.3.0,  22",
        "20.11.0,  20",   // no leading 'v'
        "v20,      20",   // no minor/patch
    })
    void parseMajorVersion_knownVersions(String input, int expected) {
        assertEquals(expected, NodeEnvironment.parseMajorVersion(input));
    }

    @ParameterizedTest(name = "parseMajorVersion({0}) = 0")
    @CsvSource({
        "''",
        "not-a-version",
        "vX.Y.Z",
    })
    void parseMajorVersion_invalidInput_returnsZero(String input) {
        assertEquals(0, NodeEnvironment.parseMajorVersion(input.isEmpty() ? "" : input));
    }

    @Test
    void parseMajorVersion_null_returnsZero() {
        assertEquals(0, NodeEnvironment.parseMajorVersion(null));
    }

    @Test
    void minimumVersionConstant_isEighteen() {
        assertEquals(18, NodeEnvironment.MINIMUM_MAJOR_VERSION);
    }

    @Test
    void permissionModelVersion_isTwenty() {
        assertEquals(20, NodeEnvironment.PERMISSION_MODEL_VERSION);
    }
}
