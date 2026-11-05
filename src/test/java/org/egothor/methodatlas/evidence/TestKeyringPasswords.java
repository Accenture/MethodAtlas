// SPDX-License-Identifier: Apache-2.0
// Copyright 2026 Egothor
// Copyright 2026 Accenture
package org.egothor.methodatlas.evidence;

import java.nio.file.Path;

import zeroecho.core.storage.KeyringPassword;
import zeroecho.core.storage.KeyringStore;

/**
 * Shared password fixtures for keyring tests: a fixed password delivered
 * through an injected prompt, so tests need neither a console nor process
 * environment variables.
 */
final class TestKeyringPasswords {

    /** Password used by every test keyring; long enough for a new keyring. */
    static final String PASSWORD = "correct-horse-battery";

    private TestKeyringPasswords() {
    }

    /**
     * Returns a source that answers every prompt with {@link #PASSWORD}.
     *
     * @return password source independent of the environment
     */
    static KeyringPasswordSource fixed() {
        return of(PASSWORD);
    }

    /**
     * Returns a source that answers every prompt with {@code password}.
     *
     * @param password password to supply
     * @return password source independent of the environment
     */
    static KeyringPasswordSource of(String password) {
        return new KeyringPasswordSource(null, null, name -> null, prompt -> password.toCharArray());
    }

    /**
     * Opens a test keyring with {@link #PASSWORD}; the caller must close it.
     *
     * @param keyring keyring path
     * @return the open keyring
     * @throws Exception if opening fails
     */
    static KeyringStore open(Path keyring) throws Exception {
        try (KeyringPassword password = new KeyringPassword(PASSWORD.toCharArray())) {
            return KeyringStore.open(keyring, password);
        }
    }
}
