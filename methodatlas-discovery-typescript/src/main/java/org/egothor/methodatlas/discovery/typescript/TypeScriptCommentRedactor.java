// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.discovery.typescript;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.egothor.methodatlas.api.CommentRedactionException;
import org.egothor.methodatlas.api.CommentRedactor;
import org.egothor.methodatlas.util.CommentBlanker;
import org.egothor.methodatlas.util.WorkerCircuitBreaker;

/**
 * {@link CommentRedactor} for TypeScript and JavaScript source text, built on
 * the {@code @typescript-eslint/typescript-estree} parser that already runs in
 * the Node.js scanner workers.
 *
 * <p>
 * A worker parses the text exactly as given and reports the half-open UTF-16
 * range of every comment: line comments, block comments and JSDoc blocks, plus
 * a leading hashbang line, which the parser skips without reporting. This class
 * blanks those ranges with {@link CommentBlanker} (spaces, line terminators
 * kept), so the length invariant and the fail-closed decision live on the Java
 * side. Because a real parser classifies the text, comment markers inside
 * string literals, template literals (including their {@code ${...}}
 * substitutions), regular-expression literals and JSX text are never touched.
 * </p>
 *
 * <p>
 * The redactor fails closed. A {@link CommentRedactionException} is thrown if
 * the text does not parse, if the worker fails, times out or reports a text
 * length different from ours, if a range is invalid, or if parsing the result
 * again still finds a comment. Only {@code '\n'} and {@code '\r'} inside a
 * comment are guaranteed to be kept; the Unicode line separators U+2028 and
 * U+2029 inside a block comment become spaces like any other comment character.
 * </p>
 *
 * <h2>Node.js availability</h2>
 * <p>
 * The redactor needs the same Node.js (version 18 or later) and the same
 * verified bundle as discovery. {@link #supports(Path)} therefore returns
 * {@code false} for TypeScript and JavaScript files when Node.js or the bundle
 * is unusable, so {@code -redact-comments} aborts for those files instead of
 * sending them un-redacted. {@code node --version} is only run when the first
 * TypeScript or JavaScript file is offered, never for other languages.
 * </p>
 *
 * <p>
 * Instances own a lazily created worker pool that is stopped by a JVM shutdown
 * hook; they are safe for concurrent use.
 * </p>
 *
 * @see CommentRedactor
 * @since 4.1.0
 */
public final class TypeScriptCommentRedactor implements CommentRedactor {

    private static final Logger LOG = Logger.getLogger(TypeScriptCommentRedactor.class.getName());

    /** Every suffix the TypeScript plugin family treats as TypeScript or JavaScript source. */
    private static final List<String> SUFFIXES =
            List.of(".ts", ".tsx", ".mts", ".cts", ".js", ".jsx", ".mjs", ".cjs");

    private static final int POOL_SIZE = Math.min(4, Runtime.getRuntime().availableProcessors());
    private static final long WORKER_TIMEOUT_MILLIS = 30_000L;
    private static final int MAX_RESTARTS = 5;
    private static final int RESTART_WINDOW_SEC = 60;

    private final Supplier<NodeEnvironment> environmentFactory;
    private final ReentrantLock initLock = new ReentrantLock();
    private boolean initialised;
    private TypeScriptWorkerPool pool;
    private Path sandboxRoot;

    /**
     * Creates a redactor; required by {@link java.util.ServiceLoader}. Node.js
     * is detected lazily.
     */
    public TypeScriptCommentRedactor() {
        this(NodeEnvironment::new);
    }

    /**
     * Creates a redactor with an injected Node.js detector.
     *
     * @param environmentFactory supplies the Node.js environment; called at
     *                           most once
     */
    /* default */ TypeScriptCommentRedactor(Supplier<NodeEnvironment> environmentFactory) {
        this.environmentFactory = environmentFactory;
    }

    /**
     * Returns the identifier of this redactor, equal to that of
     * {@link TypeScriptTestDiscovery#pluginId()}.
     *
     * @return {@code "typescript"}
     */
    @Override
    public String pluginId() {
        return "typescript";
    }

    /**
     * Tells whether this redactor can redact {@code sourceFile}: its name ends
     * in a TypeScript or JavaScript suffix (case-insensitive) <em>and</em>
     * Node.js and the verified scanner bundle are usable. Configured discovery
     * suffixes are deliberately not consulted.
     *
     * @param sourceFile path to test; may be {@code null}
     * @return {@code true} if this redactor can handle the file right now
     */
    @Override
    public boolean supports(Path sourceFile) {
        Path fileName = sourceFile == null ? null : sourceFile.getFileName();
        if (fileName == null) {
            return false;
        }
        String name = fileName.toString().toLowerCase(Locale.ROOT);
        return SUFFIXES.stream().anyMatch(name::endsWith) && ensureReady();
    }

    /**
     * Replaces every comment in {@code source} with spaces.
     *
     * @param sourceFile file the text belongs to; its extension selects JSX
     *                   handling, the file is not read
     * @param source     TypeScript or JavaScript source text; must not be
     *                   {@code null}
     * @return text of identical length in which every comment character other
     *         than a line terminator is a space; never {@code null}
     * @throws CommentRedactionException if the text cannot be parsed, the
     *                                   worker fails, or the redaction cannot
     *                                   be guaranteed complete
     */
    @Override
    public String redact(Path sourceFile, String source) throws CommentRedactionException {
        if (!ensureReady()) {
            throw new CommentRedactionException(
                    "Node.js 18+ and the TypeScript scanner bundle are required to redact " + sourceFile);
        }
        TypeScriptWorker.RedactionRanges found = locate(sourceFile, source);
        if (found.ranges().isEmpty()) {
            return source;
        }
        CommentBlanker blanker = new CommentBlanker(source);
        for (TypeScriptWorker.CommentRange range : found.ranges()) {
            blanker.blankChars(range.start(), range.end());
        }
        String result = blanker.result();
        if (result.length() != source.length()) {
            throw new CommentRedactionException("redaction changed the length of " + sourceFile);
        }
        if (!locate(sourceFile, result).ranges().isEmpty()) {
            throw new CommentRedactionException("a comment survived redaction in " + sourceFile);
        }
        return result;
    }

    /**
     * Asks a worker for the comment ranges of {@code text} and checks the
     * answer is trustworthy.
     */
    private TypeScriptWorker.RedactionRanges locate(Path sourceFile, String text)
            throws CommentRedactionException {
        TypeScriptWorker.RedactionRanges answer;
        try {
            answer = pool.redact(sourceFile, text, sandboxRoot);
        } catch (IOException e) {
            throw new CommentRedactionException("TypeScript worker failed for " + sourceFile + ": " + e.getMessage(),
                    e);
        }
        if (answer.error() != null) {
            throw new CommentRedactionException("cannot parse " + sourceFile + ": " + answer.error());
        }
        if (answer.length() != text.length()) {
            throw new CommentRedactionException("worker saw " + answer.length() + " characters of " + sourceFile
                    + " but " + text.length() + " were sent");
        }
        return answer;
    }

    /**
     * Detects Node.js and starts the pool once.
     *
     * @return {@code true} if redaction can run
     */
    private boolean ensureReady() {
        initLock.lock();
        try {
            if (!initialised) {
                initialised = true;
                initialise();
            }
            return pool != null;
        } finally {
            initLock.unlock();
        }
    }

    private void initialise() {
        NodeEnvironment env = environmentFactory.get();
        if (env == null || !env.isAvailable()) {
            LOG.log(Level.WARNING, "-redact-comments cannot redact TypeScript/JavaScript files: Node.js 18 or later is not available "
                    + "(or is too old); the run will abort rather than send un-redacted source to the AI provider. "
                    + "Install Node.js 18 or later or exclude these files from the scan.");
            return;
        }
        try {
            Path bundle = BundleIntegrity.extractAndVerify();
            WorkerCircuitBreaker breaker = new WorkerCircuitBreaker("TypeScript-redact", "typescript-redact",
                    MAX_RESTARTS, RESTART_WINDOW_SEC);
            pool = new TypeScriptWorkerPool(bundle, env, POOL_SIZE, WORKER_TIMEOUT_MILLIS, breaker);
            // Redaction never reads files. Handing the bundle itself as the sandbox
            // "root" makes the worker's allow-fs-read grant cover the bundle only.
            sandboxRoot = bundle;
        } catch (IOException | IllegalStateException e) {
            LOG.log(Level.SEVERE, "Failed to initialise TypeScript comment redactor", e);
            pool = null;
        }
    }
}
