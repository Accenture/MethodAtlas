// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.evidence;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.GeneralSecurityException;

/**
 * CLI command handler for the {@code -gen-signing-key} mode, which creates a
 * ZeroEcho keyring containing a fresh signing key pair for evidence-pack
 * signing.
 *
 * <p>
 * The mode is recognised and dispatched by {@code MethodAtlasApp} before the
 * normal scan-argument parsing, mirroring how {@code -diff} is handled. It is
 * self-contained: it parses its own small set of options and never participates
 * in the scan pipeline.
 * </p>
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * methodatlas -gen-signing-key <keyring-file> [-key-alias <alias>] \
 *             [-key-algo <algorithm>] [-overwrite]
 * }</pre>
 *
 * <p>
 * The produced keyring is a plaintext file holding the private key in clear
 * text; protect it with file-system permissions and keep it out of version
 * control and out of any distributed evidence pack. See {@link SigningKeyGenerator}.
 * </p>
 *
 * @see SigningKeyGenerator
 * @see ZeroEchoSigner
 * @since 4.0.0
 */
public final class GenSigningKeyCommand {

    /** Mode flag that selects this command. */
    public static final String FLAG_GEN_SIGNING_KEY = "-gen-signing-key";

    private static final String FLAG_KEY_ALIAS = "-key-alias";
    private static final String FLAG_KEY_ALGO = "-key-algo";
    private static final String FLAG_OVERWRITE = "-overwrite";

    /** Default alias used when {@code -key-alias} is omitted. */
    private static final String DEFAULT_ALIAS = "methodatlas-signing";

    /** Exit code returned on success. */
    private static final int EXIT_OK = 0;

    /** Exit code returned when the arguments are invalid. */
    private static final int EXIT_BAD_ARGS = 2;

    private final Path keyringFile;
    private final String alias;
    private final String algorithm;
    private final boolean overwrite;

    /**
     * Creates a new command.
     *
     * @param keyringFile target keyring file; must not be {@code null}
     * @param alias       base alias for the generated key pair
     * @param algorithm   algorithm id, or {@code null} for the default
     * @param overwrite   whether to replace an existing alias
     */
    private GenSigningKeyCommand(Path keyringFile, String alias, String algorithm, boolean overwrite) {
        this.keyringFile = keyringFile;
        this.alias = alias;
        this.algorithm = algorithm;
        this.overwrite = overwrite;
    }

    /**
     * Parses {@code -gen-signing-key} arguments and runs the command.
     *
     * @param args full command-line arguments, including the
     *             {@code -gen-signing-key} flag and its value
     * @param out  writer that receives the success summary
     * @return {@code 0} on success, {@code 2} when the arguments are invalid
     * @throws IOException if the keyring cannot be written, an alias collides
     *                     without {@code -overwrite}, or key generation fails
     */
    public static int run(String[] args, PrintWriter out) throws IOException {
        final GenSigningKeyCommand command;
        try {
            command = parse(args);
        } catch (IllegalArgumentException e) {
            System.err.println("gen-signing-key: " + e.getMessage());
            System.err.println("Usage: -gen-signing-key <keyring-file> [-key-alias <alias>] "
                    + "[-key-algo <algorithm>] [-overwrite]");
            return EXIT_BAD_ARGS;
        }
        return command.execute(out);
    }

    /**
     * Parses the command-line arguments into a command instance.
     *
     * @param args full command-line arguments
     * @return parsed command
     * @throws IllegalArgumentException if the keyring value is missing or a flag
     *                                  lacks its required value
     */
    @SuppressWarnings("PMD.AvoidReassigningLoopVariables") // ++i consumes a flag's value, matching CliArgs
    private static GenSigningKeyCommand parse(String... args) {
        Path keyringFile = null;
        String alias = DEFAULT_ALIAS;
        String algorithm = null;
        boolean overwrite = false;

        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case FLAG_GEN_SIGNING_KEY -> keyringFile = Paths.get(value(args, ++i, FLAG_GEN_SIGNING_KEY));
                case FLAG_KEY_ALIAS -> alias = value(args, ++i, FLAG_KEY_ALIAS);
                case FLAG_KEY_ALGO -> algorithm = value(args, ++i, FLAG_KEY_ALGO);
                case FLAG_OVERWRITE -> overwrite = true;
                default -> {
                    // Ignore unrelated tokens so the mode can be combined with
                    // a leading program name or stray scan arguments.
                }
            }
        }
        if (keyringFile == null) {
            throw new IllegalArgumentException("missing keyring file after " + FLAG_GEN_SIGNING_KEY);
        }
        return new GenSigningKeyCommand(keyringFile, alias, algorithm, overwrite);
    }

    /**
     * Reads the value following a flag at index {@code i}.
     *
     * @param args command-line arguments
     * @param i    index of the value (already advanced past the flag)
     * @param flag the flag whose value is being read, for diagnostics
     * @return the value token
     * @throws IllegalArgumentException if the value is missing
     */
    private static String value(String[] args, int i, String flag) {
        if (i >= args.length) {
            throw new IllegalArgumentException("missing value after " + flag);
        }
        return args[i];
    }

    /**
     * Generates the key pair and prints a summary plus the matching
     * evidence-pack invocation.
     *
     * @param out writer that receives the success summary
     * @return {@code 0} on success
     * @throws IOException if the keyring cannot be written, an alias collides
     *                     without {@code -overwrite}, or key generation fails
     */
    private int execute(PrintWriter out) throws IOException {
        final SigningKeyGenerator.GeneratedKey generated;
        try {
            generated = SigningKeyGenerator.generate(keyringFile, alias, algorithm, overwrite);
        } catch (IllegalArgumentException e) {
            System.err.println("gen-signing-key: " + e.getMessage());
            return EXIT_BAD_ARGS;
        } catch (GeneralSecurityException e) {
            throw new IOException("Signing-key generation failed: " + e.getMessage(), e);
        }

        out.println("Generated " + generated.algorithm() + " signing key in "
                + generated.keyringFile().toAbsolutePath());
        out.println("  public alias:  " + generated.publicAlias());
        out.println("  private alias: " + generated.privateAlias());
        out.println("  public key:    " + generated.publicKeyPem().toAbsolutePath() + " (X.509 PEM, for verifiers)");
        out.println("The keyring holds the private key in clear text — keep it private and out of version control.");
        out.println("Sign an evidence pack with:");
        out.println("  -evidence-pack <framework> -evidence-pack-keyring " + generated.keyringFile()
                + " -evidence-pack-key-alias " + alias);
        return EXIT_OK;
    }
}
