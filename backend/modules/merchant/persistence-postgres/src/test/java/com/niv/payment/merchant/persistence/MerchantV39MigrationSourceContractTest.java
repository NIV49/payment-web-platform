package com.niv.payment.merchant.persistence;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MerchantV39MigrationSourceContractTest {
    private static final Path MIGRATION = locateBackendRoot().resolve(
        "modules/merchant/persistence-postgres/src/main/resources/db/migration/"
            + "V39__allow_guarded_retained_amendment_rotation.sql");

    @Test
    void v39IsAForwardOnlyLeastPrivilegeAmendmentRotationBoundary() throws Exception {
        String sql = Files.readString(MIGRATION);

        assertThat(sql)
            .contains("expected frozen V38 checksum 1630466861")
            .contains("retained amendment rotation column shape drifted")
            .contains("retained amendment nonce registry binding drifted")
            .contains("retained amendment crypto or decision semantics drifted")
            .contains("rotation capability must not own Merchant evidence")
            .contains("LOCK TABLE merchant_amendment IN ACCESS EXCLUSIVE MODE")
            .contains("CREATE FUNCTION merchant_amendment_rotation_guard()")
            .contains("current_user='payment_merchant_registration_rotation'")
            .contains("CREATE TRIGGER trg_merchant_amendment_rotation_guard")
            .contains("REVOKE ALL ON merchant_amendment FROM PUBLIC")
            .contains("REVOKE ALL ON merchant_amendment FROM payment_merchant_registration_rotation")
            .contains("GRANT SELECT ON merchant_amendment")
            .contains("GRANT UPDATE (registration_fingerprint,registration_search_key_id")
            .contains("legal_id_protection_version)")
            .doesNotContain("GRANT UPDATE (status")
            .doesNotContain("GRANT UPDATE (author_tenant_id")
            .doesNotContain("CREATE EXTENSION");
    }

    private static Path locateBackendRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve("modules/merchant/persistence-postgres"))) {
                return current;
            }
            if (Files.isDirectory(current.resolve("backend/modules/merchant/persistence-postgres"))) {
                return current.resolve("backend");
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate backend root");
    }
}
