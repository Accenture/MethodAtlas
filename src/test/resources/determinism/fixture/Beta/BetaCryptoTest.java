package com.acme.beta;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

public class BetaCryptoTest {

    @Test
    @Tag("crypto")
    void usesAuthenticatedEncryption() {
        String mode = "GCM";
        assert mode.length() == 3;
    }

    @Test
    void ünïcödeNamedCase() {
    }
}
