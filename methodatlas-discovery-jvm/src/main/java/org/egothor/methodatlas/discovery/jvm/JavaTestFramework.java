package org.egothor.methodatlas.discovery.jvm;

/**
 * Test framework detected from or configured for a Java source file,
 * used by {@link JavaSourcePatcher} to select the correct tag-writing
 * annotation style.
 *
 * <ul>
 *   <li>{@link #JUNIT5} — write {@code @Tag("value")} (JUnit Jupiter)</li>
 *   <li>{@link #JUNIT4} — write {@code @Category(SomeClass.class)} (JUnit 4)</li>
 * </ul>
 *
 * @see JavaSourcePatcher
 * @see AnnotationInspector#detectFramework(com.github.javaparser.ast.CompilationUnit)
 */
/* default */ enum JavaTestFramework {
    JUNIT5,
    JUNIT4
}
