// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.evidence;

import java.io.Console;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.UnaryOperator;
import java.util.logging.Level;
import java.util.logging.Logger;

import zeroecho.core.storage.KeyringPassword;

/**
 * Resolves the password that unlocks an encrypted ZeroEcho keyring.
 *
 * <p>
 * ZeroEcho 1.2 keyrings are encrypted (PBKDF2-HMAC-SHA-256 + AES-256-GCM), so
 * every keyring operation needs a password. A password is never accepted as a
 * command-line value, because argument vectors are visible to other local users
 * and end up in shell history and CI logs. The sources are consulted in this
 * order, and the first one configured wins:
 * </p>
 * <ol>
 * <li>a password file named on the command line (first line, trailing line
 * break removed) — suits container and CI secret files;</li>
 * <li>an environment variable named on the command line — it must be set;</li>
 * <li>the {@value #DEFAULT_ENV} environment variable, when set;</li>
 * <li>an interactive, non-echoing prompt when an interactive terminal is attached.</li>
 * </ol>
 *
 * <p>
 * When creating a new keyring the password must be at least
 * {@value #MIN_NEW_PASSWORD_LENGTH} characters long, and an interactive prompt
 * asks for it twice. Instances are immutable and safe to reuse; every
 * {@link #resolve(boolean)} call returns a fresh {@link KeyringPassword} that
 * the caller must close.
 * </p>
 *
 * @since 6.2.0
 */
final class KeyringPasswordSource {

    private static final Logger LOG = Logger.getLogger(KeyringPasswordSource.class.getName());

    /** Environment variable consulted when no explicit source is configured. */
    /* default */ static final String DEFAULT_ENV = "METHODATLAS_KEYRING_PASSWORD";

    /** Minimum length enforced for the password of a newly created keyring. */
    /* default */ static final int MIN_NEW_PASSWORD_LENGTH = 12;

    private final Path passwordFile;
    private final String passwordEnv;
    private final UnaryOperator<String> environment;
    /** Non-echoing prompt: maps prompt text to the entered characters (empty or {@code null} = no input). */
    private final Function<String, char[]> prompt;

    /** Signals that no password source is configured and no console is attached. */
    /* default */ static final class MissingPasswordException extends IOException {
        private static final long serialVersionUID = 1L;

        /** Creates the exception with a fixed message. */
        /* default */ MissingPasswordException() {
            super("No keyring password available");
        }
    }

    /**
     * Creates a source backed by the process environment and the system console.
     *
     * @param passwordFile file holding the password, or {@code null}
     * @param passwordEnv  name of an environment variable holding the password,
     *                     or {@code null}
     */
    /* default */ KeyringPasswordSource(Path passwordFile, String passwordEnv) {
        this(passwordFile, passwordEnv, System::getenv, KeyringPasswordSource::readFromConsole);
    }

    /**
     * Creates a source with injectable environment and prompt, for tests.
     *
     * @param passwordFile file holding the password, or {@code null}
     * @param passwordEnv  name of an environment variable holding the password,
     *                     or {@code null}
     * @param environment  environment lookup; must not be {@code null}
     * @param prompt       interactive prompt, or {@code null} when none is available;
     *                     a prompt returning {@code null} or an empty array means no input
     */
    /* default */ KeyringPasswordSource(Path passwordFile, String passwordEnv, UnaryOperator<String> environment,
            Function<String, char[]> prompt) {
        this.passwordFile = passwordFile;
        this.passwordEnv = passwordEnv;
        this.environment = environment;
        this.prompt = prompt;
    }

    /**
     * Resolves the keyring password from the first configured source.
     *
     * @param newKeyring {@code true} when the password protects a keyring that
     *                   is about to be created (length check and confirmation)
     * @return the password; the caller must close it
     * @throws MissingPasswordException if no source is configured and no
     *                     interactive console is available
     * @throws IOException if a configured source is empty or unreadable, the confirmation does not match, or
     *                     a new password is too short
     */
    /* default */ KeyringPassword resolve(boolean newKeyring) throws IOException {
        char[] chars = resolveChars(newKeyring);
        try {
            if (newKeyring && chars.length < MIN_NEW_PASSWORD_LENGTH) {
                throw new IOException("The keyring password must be at least " + MIN_NEW_PASSWORD_LENGTH
                        + " characters long");
            }
            return new KeyringPassword(chars);
        } finally {
            Arrays.fill(chars, '\0');
        }
    }

    /**
     * Returns a human-readable list of the accepted password sources, used in
     * error messages.
     *
     * @param fileFlag flag that names a password file
     * @param envFlag  flag that names a password environment variable
     * @return guidance text; never {@code null}
     */
    /* default */ static String howToSupply(String fileFlag, String envFlag) {
        return "supply the keyring password with " + fileFlag + " <file>, " + envFlag + " <VAR>, the "
                + DEFAULT_ENV + " environment variable, or run interactively in a terminal";
    }

    private char[] resolveChars(boolean newKeyring) throws IOException {
        if (passwordFile != null) {
            return fromFile();
        }
        if (passwordEnv != null) {
            String value = environment.apply(passwordEnv);
            if (value == null || value.isEmpty()) {
                throw new IOException("Keyring password environment variable " + passwordEnv + " is unset or empty");
            }
            return value.toCharArray();
        }
        String fallback = environment.apply(DEFAULT_ENV);
        if (fallback != null && !fallback.isEmpty()) {
            return fallback.toCharArray();
        }
        return fromPrompt(newKeyring);
    }

    private char[] fromFile() throws IOException {
        warnIfShared(passwordFile);
        String content = Files.readString(passwordFile, StandardCharsets.UTF_8);
        int end = content.indexOf('\n');
        String line = end < 0 ? content : content.substring(0, end);
        if (line.endsWith("\r")) {
            line = line.substring(0, line.length() - 1);
        }
        if (line.isEmpty()) {
            throw new IOException("Keyring password file " + passwordFile + " is empty");
        }
        return line.toCharArray();
    }

    /**
     * Warns when a password file is readable by other users on POSIX systems.
     *
     * @param file password file
     */
    private static void warnIfShared(Path file) {
        try {
            PosixFileAttributeView view = Files.getFileAttributeView(file, PosixFileAttributeView.class);
            if (view == null) {
                return;
            }
            Set<PosixFilePermission> perms = view.readAttributes().permissions();
            if ((perms.contains(PosixFilePermission.GROUP_READ) || perms.contains(PosixFilePermission.OTHERS_READ))
                    && LOG.isLoggable(Level.WARNING)) {
                LOG.log(Level.WARNING, "Keyring password file {0} is readable by other users; restrict it with "
                        + "''chmod 600 {0}''", file);
            }
        } catch (IOException e) {
            if (LOG.isLoggable(Level.FINE)) {
                LOG.log(Level.FINE, "Cannot inspect permissions of " + file, e);
            }
        }
    }

    private char[] fromPrompt(boolean newKeyring) throws IOException {
        char[] first = prompt == null ? new char[0]
                : prompt.apply(newKeyring ? "New keyring password: " : "Keyring password: ");
        if (first == null || first.length == 0) {
            throw new MissingPasswordException();
        }
        if (newKeyring) {
            char[] confirm = prompt.apply("Repeat keyring password: ");
            boolean matches = confirm != null && Arrays.equals(first, confirm);
            if (confirm != null) {
                Arrays.fill(confirm, '\0');
            }
            if (!matches) {
                Arrays.fill(first, '\0');
                throw new IOException("The keyring passwords do not match");
            }
        }
        return first;
    }

    private static char[] readFromConsole(String text) {
        // Since JDK 22 System.console() returns a Console even without a terminal
        // (redirected stdin, CI); only an interactive terminal may be prompted.
        char[] entered = Optional.ofNullable(System.console())
                .filter(Console::isTerminal)
                .map(console -> console.readPassword("%s", text))
                .orElse(null);
        return entered == null ? new char[0] : entered;
    }
}
