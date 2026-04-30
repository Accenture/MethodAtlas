package org.egothor.methodatlas.ai;

/**
 * Callback invoked by {@link HttpSupport} immediately before each rate-limit
 * pause caused by an HTTP&nbsp;429 response from an AI provider.
 *
 * <p>The callback fires on the <em>calling thread</em> (typically a background
 * worker thread), synchronously before the thread sleeps for the indicated
 * duration.  Implementations must not block for longer than a few milliseconds;
 * heavyweight work such as Swing EDT dispatch must be deferred by the listener
 * itself (e.g.&nbsp;via {@code SwingUtilities.invokeLater}).</p>
 *
 * <p>A no-op listener can be expressed concisely as a lambda:
 * <pre>    (wait, attempt, max) -&gt; {}</pre></p>
 *
 * @see HttpSupport
 */
@FunctionalInterface
public interface RateLimitListener {

    /**
     * Called when the HTTP client is about to pause after receiving an HTTP
     * 429 (Too Many Requests) response from an AI provider.
     *
     * <p>At the time this method is called the pause has not yet started.
     * The calling thread will sleep for exactly {@code waitSeconds} seconds
     * immediately after this method returns.</p>
     *
     * @param waitSeconds number of seconds the client will sleep before
     *                    retrying; always positive
     * @param attempt     1-based retry attempt number; the first retry is
     *                    {@code 1}, the second is {@code 2}, and so on
     * @param maxRetries  maximum number of retries configured for the
     *                    enclosing {@link HttpSupport} instance;
     *                    {@code attempt} will not exceed this value when
     *                    this method is called
     */
    void onRateLimitPause(long waitSeconds, int attempt, int maxRetries);
}
