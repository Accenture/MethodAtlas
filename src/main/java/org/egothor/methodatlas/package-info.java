/**
 * Provides the {@code MethodAtlasApp} command-line utility for scanning Java
 * source trees for JUnit test methods and emitting per-method statistics.
 *
 * <p>
 * The primary entry point is {@link org.egothor.methodatlas.MethodAtlasApp}.
 * </p>
 *
 * <p>
 * Output modes:
 * </p>
 * <ul>
 * <li>CSV (default): {@code fqcn,method,loc,tags}</li>
 * <li>Plain text: enabled by {@code -plain} as the first command-line
 * argument</li>
 * </ul>
 */
package org.egothor.methodatlas;