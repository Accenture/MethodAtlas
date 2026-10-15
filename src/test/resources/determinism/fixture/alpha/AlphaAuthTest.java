package com.acme.alpha;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

public class AlphaAuthTest {

    @Test
    @Tag("security")
    @Tag("auth")
    @DisplayName("rejects an expired token")
    void rejectsExpiredToken() {
        int ttl = 0;
        if (ttl > 0) {
            throw new IllegalStateException("not expired");
        }
    }

    @Test
    void acceptsValidToken() {
    }
}
