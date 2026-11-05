// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.evidence;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;

/**
 * CLI command handler for the {@code -export-keyring-secret} mode, which prints
 * an encrypted keyring as a single text value for a CI/CD platform secret.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * methodatlas -export-keyring-secret <keyring-file> > keyring.secret
 * }</pre>
 *
 * <p>
 * The output is the keyring bundle described in {@link KeyringFiles}: the
 * encrypted store and its {@code .nonce} sidecar, Base64-encoded under a header
 * line. It is consumed by {@code -evidence-pack-keyring-env}. The keyring stays
 * encrypted inside the bundle, so no password is needed to export it and the
 * password must be stored as a separate secret. The bundle is printed to
 * {@code stdout}; guidance goes to {@code stderr} so redirection captures only
 * the secret.
 * </p>
 *
 * @see KeyringFiles#exportBundle(Path)
 * @since 6.2.0
 */
public final class ExportKeyringSecretCommand {

    /** Mode flag that selects this command. */
    public static final String FLAG_EXPORT_KEYRING_SECRET = "-export-keyring-secret";

    /** Exit code returned on success. */
    private static final int EXIT_OK = 0;

    /** Exit code returned when the arguments are invalid. */
    private static final int EXIT_BAD_ARGS = 2;

    /** Prevents instantiation. */
    private ExportKeyringSecretCommand() {
    }

    /**
     * Parses {@code -export-keyring-secret} arguments and prints the bundle.
     *
     * @param args full command-line arguments, including the mode flag and its value
     * @param out  writer that receives the bundle text
     * @return {@code 0} on success, {@code 2} when the keyring argument is missing
     * @throws IOException if the keyring cannot be read or is not an encrypted keyring
     */
    public static int run(String[] args, PrintWriter out) throws IOException {
        Path keyringFile = null;
        for (int i = 0; i < args.length - 1; i++) {
            if (FLAG_EXPORT_KEYRING_SECRET.equals(args[i])) {
                keyringFile = Path.of(args[i + 1]);
            }
        }
        if (keyringFile == null) {
            System.err.println("export-keyring-secret: missing keyring file after " + FLAG_EXPORT_KEYRING_SECRET);
            System.err.println("Usage: " + FLAG_EXPORT_KEYRING_SECRET + " <keyring-file>");
            return EXIT_BAD_ARGS;
        }
        out.print(KeyringFiles.exportBundle(keyringFile));
        out.flush();
        System.err.println("Store the text above as a CI secret (e.g. METHODATLAS_KEYRING) and the keyring "
                + "password as a second secret (e.g. METHODATLAS_KEYRING_PASSWORD); then sign with "
                + "-evidence-pack-keyring-env METHODATLAS_KEYRING.");
        return EXIT_OK;
    }
}
