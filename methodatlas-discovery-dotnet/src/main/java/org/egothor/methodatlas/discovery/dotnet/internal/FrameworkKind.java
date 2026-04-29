package org.egothor.methodatlas.discovery.dotnet.internal;

import java.util.List;
import java.util.Set;

/**
 * C# test framework detected from {@code using} directives in a source file.
 */
public enum FrameworkKind {

    XUNIT, NUNIT, MSTEST, UNKNOWN;

    private static final Set<String> ALL_DEFAULT_MARKERS = Set.of(
            "Fact", "Theory",
            "Test", "TestCase", "TestCaseSource",
            "TestMethod", "DataTestMethod");

    /**
     * Detects the framework from a list of using-directive namespace strings
     * (e.g. {@code "NUnit.Framework"}, {@code "Xunit"}).
     */
    public static FrameworkKind detect(List<String> usingDirectives) {
        for (String u : usingDirectives) {
            if (u.equals("Xunit") || u.startsWith("Xunit.")) return XUNIT;
            if (u.equals("NUnit.Framework") || u.startsWith("NUnit.")) return NUNIT;
            if (u.contains("VisualStudio.TestTools")) return MSTEST;
        }
        return UNKNOWN;
    }

    /** Default test-attribute names for this framework. */
    public Set<String> defaultTestMarkers() {
        return switch (this) {
            case XUNIT   -> Set.of("Fact", "Theory");
            case NUNIT   -> Set.of("Test", "TestCase", "TestCaseSource");
            case MSTEST  -> Set.of("TestMethod", "DataTestMethod");
            case UNKNOWN -> ALL_DEFAULT_MARKERS;
        };
    }

    /** Attribute simple-names that carry tag / category values on test methods. */
    public Set<String> tagAttributeNames() {
        return switch (this) {
            case XUNIT   -> Set.of("Trait");
            case NUNIT   -> Set.of("Category");
            case MSTEST  -> Set.of("TestCategory");
            case UNKNOWN -> Set.of("Category", "Trait", "TestCategory");
        };
    }

    /**
     * Builds a single-line attribute text (without surrounding whitespace) for
     * the given tag value, e.g. {@code [Category("security")]}.
     */
    public String buildTagAttribute(String tagValue) {
        String escaped = tagValue.replace("\\", "\\\\").replace("\"", "\\\"");
        return switch (this) {
            case XUNIT            -> "[Trait(\"Tag\", \"" + escaped + "\")]";
            case NUNIT, UNKNOWN   -> "[Category(\"" + escaped + "\")]";
            case MSTEST           -> "[TestCategory(\"" + escaped + "\")]";
        };
    }

    /**
     * Returns {@code true} when the attribute carries a display-name that
     * MethodAtlas can read and write for this framework.
     * Only xUnit embeds {@code DisplayName} as a named parameter of
     * {@code [Fact]} / {@code [Theory]}.
     */
    public boolean supportsDisplayName() {
        return this == XUNIT;
    }
}
