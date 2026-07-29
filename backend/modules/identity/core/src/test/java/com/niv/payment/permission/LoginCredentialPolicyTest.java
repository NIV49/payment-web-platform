package com.niv.payment.permission;

import com.niv.payment.permission.service.LoginCredentialPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LoginCredentialPolicyTest {
    private static final String PAYLOAD =
        "N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    @Test
    void acceptsSupportedBcryptVariantsAtTheConfiguredCostBoundary() {
        assertTrue(LoginCredentialPolicy.isLoginCapableHash("$2a$10$" + PAYLOAD));
        assertTrue(LoginCredentialPolicy.isLoginCapableHash("$2b$12$" + PAYLOAD));
        assertTrue(LoginCredentialPolicy.isLoginCapableHash("$2y$14$" + PAYLOAD));
    }

    @Test
    void rejectsMissingMalformedWeakAndExcessiveCostHashes() {
        assertFalse(LoginCredentialPolicy.isLoginCapableHash(null));
        assertFalse(LoginCredentialPolicy.isLoginCapableHash("not-a-bcrypt-hash"));
        assertFalse(LoginCredentialPolicy.isLoginCapableHash("$2x$12$" + PAYLOAD));
        assertFalse(LoginCredentialPolicy.isLoginCapableHash("$2a$09$" + PAYLOAD));
        assertFalse(LoginCredentialPolicy.isLoginCapableHash("$2a$15$" + PAYLOAD));
        assertFalse(LoginCredentialPolicy.isLoginCapableHash("$2a$12$short"));
    }
}
