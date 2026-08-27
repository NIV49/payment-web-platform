package com.niv.payment.permission;

import com.niv.payment.permission.service.LoginEmailPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LoginEmailPolicyTest {
    @Test
    void normalizesSurroundingWhitespaceAndCase() {
        assertEquals("admin@merchant.example.test",
            LoginEmailPolicy.normalize("  Admin@Merchant.Example.Test  "));
    }

    @Test
    void acceptsPlusAddressingWithoutProviderSpecificRewriting() {
        assertEquals("ops+night@merchant.example.test",
            LoginEmailPolicy.normalize("ops+night@merchant.example.test"));
    }

    @Test
    void rejectsOpaqueUsernamesAndMalformedAddresses() {
        for (String value : new String[] {
            "admin", "@example.test", "admin@", "admin@@example.test",
            ".admin@example.test", "admin..ops@example.test",
            "admin@-example.test", "admin@example..test"
        }) {
            assertThrows(IllegalArgumentException.class,
                () -> LoginEmailPolicy.normalize(value), value);
        }
    }
}
