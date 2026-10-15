// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.discovery.python;

import java.io.IOException;
import java.nio.file.Path;
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
 * {@link CommentRedactor} for Python source text, built on the standard
 * library {@code tokenize} module running in the Python scanner worker.
 *
 * <p>
 * Python's {@code ast} discards comments, so a worker tokenizes the text
 * exactly as given and reports the half-open UTF-16 range of every
 * {@code COMMENT} token (a {@code #} up to the end of its line, including a
 * shebang or an encoding declaration). This class blanks those ranges with
 * {@link CommentBlanker} (spaces, line terminators kept), so the length
 * invariant and the fail-closed decision live on the Java side. The tokenizer
 * classifies string and f-string literals, so a {@code #} inside one is never
 * touched.
 * </p>
 *
 * <p>
 * <strong>Docstrings are not redacted.</strong> A docstring is a string
 * literal, not a comment, and is kept as written; by owner decision only
 * {@code #} comments are removed.
 * </p>
 *
 * <p>
 * The redactor fails closed. A {@link CommentRedactionException} is thrown if
 * the text cannot be tokenized (unterminated triple-quoted string, inconsistent
 * dedent, ...), if the worker fails, times out or reports a text length
 * different from ours, if a reported range does not start at a {@code #} or
 * spans a line end, or if tokenizing the result again still finds a comment.
 * </p>
 *
 * <h2>Python availability</h2>
 * <p>
 * The redactor needs the same Python (3.8 or later) and the same verified
 * script as discovery. {@link #supports(Path)} therefore returns {@code false}
 * for {@code .py} files when Python or the script is unusable, so
 * {@code -redact-comments} aborts for those files instead of sending them
 * un-redacted. {@code python --version} is only run when the first
 * {@code .py} file is offered, never for other languages.
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
public final class PythonCommentRedactor implements CommentRedactor {

    private static final Logger LOG = Logger.getLogger(PythonCommentRedactor.class.getName());

    private static final String PYTHON_SUFFIX = ".py";
    private static final int POOL_SIZE = Math.min(4, Runtime.getRuntime().availableProcessors());
    private static final long WORKER_TIMEOUT_MILLIS = 30_000L;
    private static final int MAX_RESTARTS = 5;
    private static final int RESTART_WINDOW_SEC = 60;

    private final Supplier<PythonEnvironment> environmentFactory;
    private final ReentrantLock initLock = new ReentrantLock();
    private boolean initialised;
    private PythonWorkerPool pool;

    /**
     * Creates a redactor; required by {@link java.util.ServiceLoader}. Python
     * is detected lazily.
     */
    public PythonCommentRedactor() {
        this(PythonEnvironment::new);
    }

    /**
     * Creates a redactor with an injected Python detector.
     *
     * @param environmentFactory supplies the Python environment; called at
     *                           most once
     */
    /* default */ PythonCommentRedactor(Supplier<PythonEnvironment> environmentFactory) {
        this.environmentFactory = environmentFactory;
    }

    /**
     * Returns the identifier of this redactor, equal to that of
     * {@link PythonTestDiscovery#pluginId()}.
     *
     * @return {@code "python"}
     */
    @Override
    public String pluginId() {
        return "python";
    }

    /**
     * Tells whether this redactor can redact {@code sourceFile}: its name ends
     * in {@code .py} (case-insensitive) <em>and</em> Python and the verified
     * scanner script are usable. Configured discovery suffixes are
     * deliberately not consulted.
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
        return fileName.toString().toLowerCase(Locale.ROOT).endsWith(PYTHON_SUFFIX) && ensureReady();
    }

    /**
     * Replaces every {@code #} comment in {@code source} with spaces;
     * docstrings and all other string literals are left untouched.
     *
     * @param sourceFile file the text belongs to; used for diagnostics only
     * @param source     Python source text; must not be {@code null}
     * @return text of identical length in which every comment character other
     *         than a line terminator is a space; never {@code null}
     * @throws CommentRedactionException if the text cannot be tokenized, the
     *                                   worker fails, or the redaction cannot
     *                                   be guaranteed complete
     */
    @Override
    public String redact(Path sourceFile, String source) throws CommentRedactionException {
        if (!ensureReady()) {
            throw new CommentRedactionException(
                    "Python 3.8+ and the Python scanner script are required to redact " + sourceFile);
        }
        PythonWorker.RedactionRanges found = locate(sourceFile, source);
        if (found.ranges().isEmpty()) {
            return source;
        }
        CommentBlanker blanker = new CommentBlanker(source);
        for (PythonWorker.CommentRange range : found.ranges()) {
            requireCommentShape(sourceFile, source, range);
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
     * Rejects a range that cannot be a Python comment, which would mean the
     * worker's offsets do not line up with this text.
     */
    private static void requireCommentShape(Path sourceFile, String source, PythonWorker.CommentRange range)
            throws CommentRedactionException {
        if (range.start() < 0 || range.end() > source.length() || range.end() <= range.start()
                || source.charAt(range.start()) != '#') {
            throw new CommentRedactionException(
                    "reported comment range " + range.start() + ".." + range.end() + " in " + sourceFile
                            + " does not start at a '#'");
        }
        for (int i = range.start(); i < range.end(); i++) {
            char c = source.charAt(i);
            if (c == '\n' || c == '\r') {
                throw new CommentRedactionException(
                        "reported comment range in " + sourceFile + " spans a line end");
            }
        }
    }

    /**
     * Asks a worker for the comment ranges of {@code text} and checks the
     * answer is trustworthy.
     */
    private PythonWorker.RedactionRanges locate(Path sourceFile, String text) throws CommentRedactionException {
        PythonWorker.RedactionRanges answer;
        try {
            answer = pool.redact(sourceFile, text);
        } catch (IOException e) {
            throw new CommentRedactionException("Python worker failed for " + sourceFile + ": " + e.getMessage(),
                    e);
        }
        if (answer.error() != null) {
            throw new CommentRedactionException("cannot tokenize " + sourceFile + ": " + answer.error());
        }
        if (answer.length() != text.length()) {
            throw new CommentRedactionException("worker saw " + answer.length() + " characters of " + sourceFile
                    + " but " + text.length() + " were sent");
        }
        return answer;
    }

    /**
     * Detects Python and starts the pool once.
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
        PythonEnvironment env = environmentFactory.get();
        if (env == null || !env.isAvailable()) {
            LOG.log(Level.WARNING, "-redact-comments cannot redact Python files: Python 3.8 or later is not available "
                    + "(or is too old); the run will abort rather than send un-redacted source to the AI provider. "
                    + "Install Python 3.8 or later or exclude these files from the scan.");
            return;
        }
        try {
            Path script = PythonScriptExtractor.extractScript();
            WorkerCircuitBreaker breaker = new WorkerCircuitBreaker("Python-redact", "python-redact",
                    MAX_RESTARTS, RESTART_WINDOW_SEC);
            pool = new PythonWorkerPool(script, env, POOL_SIZE, WORKER_TIMEOUT_MILLIS, breaker);
        } catch (IOException | IllegalStateException e) {
            LOG.log(Level.SEVERE, "Failed to initialise Python comment redactor", e);
            pool = null;
        }
    }
}
