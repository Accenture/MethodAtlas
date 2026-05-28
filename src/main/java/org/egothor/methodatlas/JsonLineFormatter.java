// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.time.Instant;
import java.util.logging.Formatter;
import java.util.logging.LogRecord;

/**
 * {@link java.util.logging.Formatter} that emits one JSON object per log
 * record, on a single line, suitable for ingestion by log-aggregation
 * pipelines (Elastic, Splunk, Loki) and for reproducible audit-trail
 * archives.
 *
 * <p>
 * Acts as the JUL-native equivalent of an SLF4J / Logback JSON encoder.
 * The MethodAtlas codebase stays on the classic {@code java.util.logging}
 * stack (no SLF4J / Log4j dependency) per project convention; this
 * formatter delivers structured-logging semantics without adding any
 * third-party logging library.
 * </p>
 *
 * <h2>Output schema</h2>
 *
 * <p>
 * Each {@link LogRecord} becomes one line of UTF-8 text containing a JSON
 * object with the following fields:
 * </p>
 * <ul>
 *   <li>{@code timestamp} — ISO-8601 instant ({@code 2026-05-27T13:45:06Z})</li>
 *   <li>{@code level}     — JUL level name ({@code INFO}, {@code WARNING}, …)</li>
 *   <li>{@code logger}    — full logger name from
 *       {@link LogRecord#getLoggerName()}</li>
 *   <li>{@code thread}    — name of the thread that emitted the record</li>
 *   <li>{@code message}   — the formatted log message (with parameters
 *       interpolated)</li>
 *   <li>{@code runId}     — short correlation id from {@link ScanRunContext},
 *       omitted when no run is set on the current thread</li>
 *   <li>{@code thrown}    — string-rendered exception stack trace, present
 *       only when {@link LogRecord#getThrown()} is non-null</li>
 * </ul>
 *
 * <h2>Activation</h2>
 *
 * <p>
 * The formatter is not installed automatically. Activate it through a JUL
 * configuration file (commonly via the {@code -Djava.util.logging.config.file}
 * system property), or programmatically by attaching the formatter to a
 * {@link java.util.logging.Handler}:
 * </p>
 * <pre>{@code
 * Handler handler = new ConsoleHandler();
 * handler.setFormatter(new JsonLineFormatter());
 * Logger.getLogger("org.egothor.methodatlas").addHandler(handler);
 * }</pre>
 *
 * <h2>Thread safety</h2>
 *
 * <p>
 * The formatter is stateless and safe for concurrent use by multiple
 * handlers and threads.
 * </p>
 *
 * @see ScanRun
 * @see ScanRunContext
 * @since 1.0.0
 */
public final class JsonLineFormatter extends Formatter {

    /**
     * Highest control-code code point that JSON requires to be escaped as a
     * {@code \\u00XX} sequence. Characters strictly below {@code U+0020}
     * (space) are not valid unescaped inside a JSON string per RFC 8259.
     */
    private static final char JSON_CONTROL_BOUNDARY = 0x20;

    /**
     * Creates a new formatter. The class carries no instance state and is
     * safe to share across handlers.
     */
    public JsonLineFormatter() {
        super();
    }

    /**
     * Renders {@code record} as a single line of JSON terminated by
     * {@code \n}. The exact field set is documented on the class.
     *
     * @param record log record to render; must not be {@code null}
     * @return JSON-encoded log entry followed by a newline
     */
    @Override
    @SuppressWarnings("PMD.DoNotUseThreads") // MethodAtlas is a CLI tool, not a J2EE webapp; current-thread name is metadata, not concurrency
    public String format(LogRecord record) {
        StringBuilder buf = new StringBuilder(256);
        buf.append('{');
        appendField(buf, "timestamp",
                Instant.ofEpochMilli(record.getMillis()).toString(), true);
        appendField(buf, "level", record.getLevel().getName(), false);
        appendField(buf, "logger",
                record.getLoggerName() == null ? "" : record.getLoggerName(), false);
        appendField(buf, "thread", Thread.currentThread().getName(), false);
        appendField(buf, "message", formatMessage(record), false);

        ScanRunContext.current().ifPresent(run -> {
            buf.append(',');
            appendField(buf, "runId", run.runId(), true);
        });

        if (record.getThrown() != null) {
            buf.append(',');
            appendField(buf, "thrown", renderThrowable(record.getThrown()), true);
        }

        buf.append("}\n");
        return buf.toString();
    }

    private static void appendField(StringBuilder buf, String name, String value, boolean first) {
        if (!first) {
            buf.append(',');
        }
        buf.append('"').append(name).append("\":\"").append(escape(value)).append('"');
    }

    private static String renderThrowable(Throwable t) {
        StringWriter sw = new StringWriter();
        try (PrintWriter pw = new PrintWriter(sw)) {
            t.printStackTrace(pw);
        }
        return sw.toString();
    }

    private static String escape(String value) {
        if (value == null) {
            return "";
        }
        StringBuilder out = new StringBuilder(value.length() + 16);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"'  -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                default -> {
                    if (c < JSON_CONTROL_BOUNDARY) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }
}
