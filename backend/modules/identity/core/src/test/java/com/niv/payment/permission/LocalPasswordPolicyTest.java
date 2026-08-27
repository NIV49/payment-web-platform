package com.niv.payment.permission;

import com.niv.payment.permission.service.IdentityAdministrationService;
import com.niv.payment.permission.service.LocalPasswordPolicy;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class LocalPasswordPolicyTest {
    @Test
    void acceptsTheExactLocalPasswordContract() {
        assertEquals("Abcd1234Efgh!!!!", LocalPasswordPolicy.requireValid("Abcd1234Efgh!!!!"));
        assertEquals("Abcd1234EfghIjklMnOp!!!!",
            LocalPasswordPolicy.requireValid("Abcd1234EfghIjklMnOp!!!!"));
    }

    @Test
    void rejectsInvalidLengthsCharacterClassesSpecialCountsAndAlphabets() {
        for (String password : new String[]{
            "Aa1!Aa1!Aa1!Aa1", "aaaaaaaaaaaa!!!!", "AAAAAAAAAAAA!!!!",
            "111111111111!!!!", "Aa1!Aa1!Aa1!Aa1!!", "Aa1!Aa1!Aa1!Aa1?",
            "Aa1!Aa1!Aa1!Aa1中"
        }) {
            assertThrows(IdentityAdministrationService.InvalidCommandException.class,
                () -> LocalPasswordPolicy.requireValid(password));
        }
    }

    @Test
    void neverTrimsOrNormalizesThePassword() {
        assertThrows(IdentityAdministrationService.InvalidCommandException.class,
            () -> LocalPasswordPolicy.requireValid(" Abcd1234Efgh!!!!"));
    }
}
