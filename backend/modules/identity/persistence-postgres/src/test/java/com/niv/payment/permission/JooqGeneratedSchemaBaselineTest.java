package com.niv.payment.permission;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static com.niv.payment.permission.persistence.jooq.generated.Sequences.IAM_ID_SEQ;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_AUDIT_EVENT;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_AUTHENTICATION_CREDENTIAL;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_DEPARTMENT;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_GRANT_DIMENSION;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_GRANT_TARGET;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_MEMBERSHIP;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_MEMBERSHIP_ROLE;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_MENU;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_PERMISSION;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_PERMISSION_CHANGE_OUTBOX;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_PERMISSION_CHANGE_RELAY_STATE;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_ROLE;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_ROLE_GRANT;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_ROLE_MENU;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_TENANT;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_USER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JooqGeneratedSchemaBaselineTest {
    @Test
    void generatedModelContainsEveryIdentityTableAndTheSharedSequence() {
        Set<String> tables = Set.of(
            IAM_USER.getName(),
            IAM_TENANT.getName(),
            IAM_DEPARTMENT.getName(),
            IAM_MEMBERSHIP.getName(),
            IAM_ROLE.getName(),
            IAM_MEMBERSHIP_ROLE.getName(),
            IAM_PERMISSION.getName(),
            IAM_ROLE_GRANT.getName(),
            IAM_GRANT_DIMENSION.getName(),
            IAM_GRANT_TARGET.getName(),
            IAM_MENU.getName(),
            IAM_ROLE_MENU.getName(),
            IAM_AUDIT_EVENT.getName(),
            IAM_PERMISSION_CHANGE_OUTBOX.getName(),
            IAM_PERMISSION_CHANGE_RELAY_STATE.getName(),
            IAM_AUTHENTICATION_CREDENTIAL.getName()
        );

        assertEquals(16, tables.size());
        assertTrue(tables.stream().allMatch(name -> name.startsWith("iam_")));
        assertEquals("iam_id_seq", IAM_ID_SEQ.getName());
    }

    @Test
    void generatedModelCarriesTheAuthorizationAndCredentialSafetyChecks() {
        assertTrue(IAM_PERMISSION.getChecks().stream().anyMatch(check ->
            check.getName().equals("ck_iam_permission_related_party_read_action")));
        assertTrue(IAM_AUTHENTICATION_CREDENTIAL.getChecks().stream().anyMatch(check ->
            check.getName().equals("ck_iam_authentication_bcrypt_hash")));
    }
}
