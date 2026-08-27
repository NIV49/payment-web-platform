package com.niv.payment.merchant.persistence;

import com.niv.payment.merchant.core.AccountDomain;
import com.niv.payment.merchant.core.MerchantActor;
import com.niv.payment.merchant.core.MerchantApplicationService;
import com.niv.payment.merchant.core.MerchantCommand;
import com.niv.payment.merchant.core.MerchantDetail;
import com.niv.payment.merchant.core.MerchantException;
import com.niv.payment.merchant.core.MerchantQuery;
import com.niv.payment.merchant.core.MerchantOnboardingService;
import com.niv.payment.merchant.core.MerchantOnboardingModels;
import com.niv.payment.merchant.core.MerchantStatus;
import com.niv.payment.merchant.core.ProfileUpdateRequest;
import com.niv.payment.merchant.core.SubmissionRequest;
import com.niv.payment.merchant.core.TransitionCommand;
import com.niv.payment.merchant.persistence.crypto.JdkMerchantCryptography;
import com.niv.payment.merchant.persistence.crypto.ThreePurposeKeyRing;
import com.niv.payment.merchant.persistence.crypto.JdkMerchantOnboardingCryptography;
import com.niv.payment.merchant.persistence.crypto.MerchantOnboardingKeyRing;
import com.niv.payment.merchant.core.crypto.MerchantCryptography;
import org.flywaydb.core.Flyway;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;
import org.jooq.DSLContext;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.OffsetDateTime;
import java.time.LocalDate;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.postgresql.ds.PGSimpleDataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class MerchantRepositoryIntegrationTest {
    private static final Path BACKEND_ROOT = locateBackendRoot();
    private static final long TENANT = 62001;
    private static final long SECOND_TENANT = 62011;
    private static final long USER = 62002;
    private static final long MEMBERSHIP = 62003;
    private static final long ROLE = 62004;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse(
        "postgres:18.4-alpine@sha256:9a8afca54e7861fd90fab5fdf4c42477a6b1cb7d293595148e674e0a3181de15")
        .asCompatibleSubstituteFor("postgres"))
        .withDatabaseName("payment_platform")
        .withUsername("payment_dev")
        .withPassword("payment_dev");

    private DSLContext dsl;
    private MerchantApplicationService service;
    private MerchantOnboardingService onboardingService;
    private com.niv.payment.merchant.core.MerchantDocumentMaintenance documentMaintenance;
    private com.niv.payment.merchant.core.crypto.MerchantOnboardingCryptography onboardingCryptography;
    private JooqMerchantRegistrationKeyRotation rotation;
    private MerchantCryptography cryptography;
    private DSLContext rotationDsl;
    private Connection rotationConnection;

    @BeforeEach
    void setUp() throws Exception {
        resetTestDatabaseRoles();
        flyway(null).clean();
        flyway("31").migrate();
        DSLContext migrationDsl = DSL.using(dataSource(
            POSTGRES.getUsername(), POSTGRES.getPassword()), SQLDialect.POSTGRES);
        dsl = migrationDsl;
        seedMerchantActor();
        flyway(null).migrate();
        seedSecondEligibleTenant(migrationDsl);
        createSeparatedDatabasePrincipals(migrationDsl);
        dsl = DSL.using(dataSource("mch_runtime_app", "mch-runtime-test"), SQLDialect.POSTGRES);
        var ring = ThreePurposeKeyRing.fromBase64(
            Map.of("mch-registration-search-v1", key(11),
                "mch-registration-search-v2", key(12)),
            Map.of("mch-idempotency-v1", key(22)),
            Map.of("mch-registration-aead-v1", key(33),
                "mch-registration-aead-v2", key(34)));
        cryptography = new JdkMerchantCryptography(ring);
        var onboardingRing = MerchantOnboardingKeyRing.fromBase64(
            Map.of("mch-legal-id-aead-v1", key(44), "mch-legal-id-aead-v2", key(45)),
            Map.of("mch-document-aead-v1", key(55), "mch-document-aead-v2", key(56)),
            java.util.List.of(
                Map.of("mch-registration-search-v1", key(11),
                    "mch-registration-search-v2", key(12)),
                Map.of("mch-idempotency-v1", key(22)),
                Map.of("mch-registration-aead-v1", key(33),
                    "mch-registration-aead-v2", key(34))));
        onboardingCryptography = new JdkMerchantOnboardingCryptography(onboardingRing);
        var repository = new JooqMerchantRepository(dsl, cryptography, onboardingCryptography,
            "mch-idempotency-v1", () -> "repository-it");
        documentMaintenance = repository;
        service = new MerchantApplicationService(repository);
        onboardingService = new MerchantOnboardingService(repository);
        rotationConnection = DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), "mch_rotation_ops", "mch-rotation-test");
        rotationDsl = DSL.using(rotationConnection, SQLDialect.POSTGRES);
        rotation = new JooqMerchantRegistrationKeyRotation(
            rotationDsl, cryptography, onboardingCryptography);
    }

    @AfterEach
    void closeRotationConnection() throws Exception {
        if (rotationConnection != null) rotationConnection.close();
    }

    @Test
    void submitReplayIsStableAfterStateChangesAndStoresNoPlainRegistrationNumber() {
        UUID key = UUID.fromString("b74109ae-b3b9-47bd-95c4-f2072d248d83");
        var request = request(key, null, "2026-001234-Z");

        var first = service.submit(actor(), request);
        dsl.execute("UPDATE merchant SET status='ACTIVE', status_reason_code='PROFILE_VERIFIED', "
            + "row_version=1 WHERE id=?", first.merchantId());
        var replay = service.submit(actor(), request);

        assertThat(replay).isEqualTo(first);
        assertThat(value("SELECT count(*) FROM merchant_command_dedup", Long.class)).isEqualTo(1);
        assertThat(value("SELECT count(*) FROM merchant_audit_event", Long.class)).isEqualTo(1);
        assertThat(value("""
            SELECT count(*) FROM merchant
             WHERE position(convert_to('2026-001234-Z','UTF8') in registration_ciphertext) > 0
            """, Long.class)).isZero();
        assertThat(value("SELECT registration_number_masked FROM merchant", String.class))
            .endsWith("234Z").doesNotContain("2026");
    }

    @Test
    void ordinaryRoleCannotSplicePortalAndActionPermissions() {
        dsl.execute("""
            INSERT INTO iam_role(id,tenant_id,role_code,role_name,applicable_tenant_type,
                                 assignable,system_role,status)
            VALUES (62100,62001,'ordinary','Ordinary','DIRECT_MERCHANT',true,false,'ACTIVE');
            INSERT INTO iam_membership_role(tenant_id,membership_id,role_id)
            VALUES (62001,62003,62100);
            DELETE FROM iam_membership_role
             WHERE tenant_id=62001 AND membership_id=62003 AND role_id=62004;
            """);
        long permission = value(
            "SELECT id FROM iam_permission WHERE permission_code='merchant:submit'", Long.class);
        dsl.execute("""
            INSERT INTO iam_role_grant(id,tenant_id,role_id,permission_id,grant_key,status,
                                       valid_from,valid_until)
            VALUES (62101,62001,62100,?,'ordinary-submit','ACTIVE',now(),now()+interval '1 day');
            INSERT INTO iam_grant_dimension(id,grant_id,dimension_code,scope_mode)
            VALUES (62102,62101,'TENANT','TENANT_ALL')
            """, permission);

        assertThatThrownBy(() -> service.submit(actor(), request(UUID.randomUUID(), null, "2026001234Z")))
            .isInstanceOf(MerchantException.PermissionDenied.class);
        assertThat(value("SELECT count(*) FROM merchant", Long.class)).isZero();
    }

    @Test
    void revokedAssignmentExpiredGrantAndOidcMismatchFailClosedBeforeMerchantRead() {
        dsl.execute("DELETE FROM iam_membership_role WHERE tenant_id=? AND membership_id=?",
            TENANT, MEMBERSHIP);
        assertThatThrownBy(() -> service.findSelf(actor()))
            .isInstanceOf(MerchantException.PermissionDenied.class);

        dsl.execute("INSERT INTO iam_membership_role(tenant_id,membership_id,role_id) VALUES (?,?,?)",
            TENANT, MEMBERSHIP, ROLE);
        dsl.execute("""
            UPDATE iam_role_grant SET valid_from=now()-interval '2 days',
                                      valid_until=now()-interval '1 second'
             WHERE role_id=? AND permission_id=(SELECT id FROM iam_permission
                                                 WHERE permission_code='merchant:self-view')
            """, ROLE);
        assertThatThrownBy(() -> service.findSelf(actor()))
            .isInstanceOf(MerchantException.PermissionDenied.class);

        dsl.execute("""
            UPDATE iam_role_grant SET valid_from=now()-interval '1 day',
                                      valid_until=now()+interval '1 day'
             WHERE role_id=? AND permission_id=(SELECT id FROM iam_permission
                                                 WHERE permission_code='merchant:self-view')
            """, ROLE);
        MerchantActor wrongOidc = new MerchantActor(USER, MEMBERSHIP, TENANT,
            AccountDomain.MERCHANT, 0, 0, 0, "https://wrong", "wrong", true, false);
        assertThatThrownBy(() -> service.findSelf(wrongOidc))
            .isInstanceOf(MerchantException.PermissionDenied.class);
    }

    @Test
    void submissionAndReviewAreAtomicAndOptimisticallyLocked() {
        var submitted = service.submit(actor(), request(UUID.randomUUID(), null, "2026001234Z"));
        MerchantActor platform = platformStepUpActor();
        var platformService = service;

        var approved = platformService.transition(platform, new TransitionCommand(
            submitted.merchantId(), MerchantCommand.APPROVE, "PROFILE_VERIFIED", 0,
            UUID.randomUUID()));
        assertThat(approved.status()).isEqualTo(MerchantStatus.ACTIVE);
        assertThatThrownBy(() -> platformService.transition(platform, new TransitionCommand(
            submitted.merchantId(), MerchantCommand.DISABLE, "RISK_CONTROL", 0,
            UUID.randomUUID())))
            .isInstanceOf(MerchantException.OptimisticLockConflict.class);
        assertThat(value("SELECT count(*) FROM merchant_audit_event", Long.class))
            .isEqualTo(2);
        assertThat(value("SELECT count(*) FROM merchant_command_dedup", Long.class))
            .isEqualTo(2);
    }

    @Test
    void platformListIsBoundedAndReturnsMaskedOnly() {
        var submitted = service.submit(actor(), request(UUID.randomUUID(), null, "2026001234Z"));
        var page = service.findPlatform(platformActor(), new MerchantQuery(
            null, "Example", MerchantStatus.PENDING_REVIEW, "SG",
            OffsetDateTime.now().minusDays(1), OffsetDateTime.now().plusDays(1), 1, 20));
        assertThat(page.total()).isEqualTo(1);
        assertThat(page.items().getFirst().registrationNumberMasked()).endsWith("234Z");
        assertThat(page.reviewPendingMerchantIds()).containsExactly(submitted.merchantId());

        service.transition(platformStepUpActor(), new TransitionCommand(
            submitted.merchantId(), MerchantCommand.APPROVE, "PROFILE_VERIFIED", 0,
            UUID.randomUUID()));
        var reviewed = service.findPlatform(platformActor(), new MerchantQuery(
            null, "Example", MerchantStatus.ACTIVE, "SG",
            OffsetDateTime.now().minusDays(1), OffsetDateTime.now().plusDays(1), 1, 20));
        assertThat(reviewed.reviewPendingMerchantIds()).isEmpty();
    }

    @Test
    void schemaTwoProfileUpdateCanOnlyReplayAnAuthorizedExistingReceipt() {
        var submitted = service.submit(actor(), request(UUID.randomUUID(), null, "2026001234Z"));
        service.transition(platformStepUpActor(), new TransitionCommand(
            submitted.merchantId(), MerchantCommand.APPROVE, "PROFILE_VERIFIED", 0,
            UUID.randomUUID()));
        MerchantActor platform = platformStepUpActor();
        UUID updateKey = UUID.randomUUID();
        ProfileUpdateRequest update = new ProfileUpdateRequest(1, updateKey,
            "Updated Legal", "Updated Display", "PLATFORM", "Updated Director", "ENTERPRISE",
            "operating note", java.util.List.of("BRA", "PHL"));
        var digest = cryptography.idempotencyDigest("mch-idempotency-v1", 1, java.util.List.of(
            "2", "UPDATE_PROFILE", Long.toString(submitted.merchantId()), "1",
            "Updated Legal", "Updated Display", "PLATFORM", "Updated Director", "ENTERPRISE",
            "operating note", "BRA", "PHL"));
        dsl.execute("""
            INSERT INTO merchant_command_dedup(
              id,actor_account_domain,actor_tenant_id,actor_membership_id,command_type,
              idempotency_key,request_digest,idempotency_hmac_key_id,command_schema_version,
              canonical_digest_scheme_version,required_permission,merchant_id,
              result_merchant_code,result_status,result_row_version)
            VALUES (nextval('iam_id_seq'),'PLATFORM',1,63003,'UPDATE_PROFILE',?,?,?,2,1,
                    'merchant:update',?,?,?,2)
            """, updateKey, digest.value(), digest.keyId(), submitted.merchantId(),
            submitted.merchantCode(), MerchantStatus.ACTIVE.name());
        long auditCount = value("SELECT count(*) FROM merchant_audit_event", Long.class);
        long dedupCount = value("SELECT count(*) FROM merchant_command_dedup", Long.class);

        assertThatThrownBy(() -> service.updateProfile(platformActor(), submitted.merchantId(), update))
            .isInstanceOf(MerchantException.PermissionDenied.class);
        assertThat(service.updateProfile(platform, submitted.merchantId(), update))
            .isEqualTo(new com.niv.payment.merchant.core.MerchantMutationResult(
                submitted.merchantId(), submitted.merchantCode(), MerchantStatus.ACTIVE, 2));
        assertThatThrownBy(() -> service.updateProfile(platform, submitted.merchantId(),
            new ProfileUpdateRequest(1, updateKey, "Different", "Updated Display",
                "PLATFORM", "Updated Director", "ENTERPRISE", "operating note",
                java.util.List.of("BRA", "PHL"))))
            .isInstanceOf(MerchantException.IdempotencyConflict.class);
        assertThatThrownBy(() -> service.updateProfile(platform, submitted.merchantId(),
            new ProfileUpdateRequest(1, UUID.randomUUID(), "Updated Legal", "Updated Display",
                "PLATFORM", "Updated Director", "ENTERPRISE", "operating note",
                java.util.List.of("BRA", "PHL"))))
            .isInstanceOf(MerchantException.StateConflict.class);
        assertThat(value("SELECT count(*) FROM merchant_audit_event", Long.class))
            .isEqualTo(auditCount);
        assertThat(value("SELECT count(*) FROM merchant_command_dedup", Long.class))
            .isEqualTo(dedupCount);
        assertThat(value("SELECT row_version FROM merchant", Long.class)).isEqualTo(1);
    }

    @Test
    void legacyProfileShapeCanOnlyReplayAnAuthorizedSchemaOneReceipt() {
        var submitted = service.submit(actor(), request(UUID.randomUUID(), null, "2026001234Z"));
        service.transition(platformStepUpActor(), new TransitionCommand(
            submitted.merchantId(), MerchantCommand.APPROVE, "PROFILE_VERIFIED", 0,
            UUID.randomUUID()));
        MerchantActor platform = platformStepUpActor();
        UUID legacyKey = UUID.randomUUID();
        ProfileUpdateRequest legacy = new ProfileUpdateRequest(1, legacyKey,
            "Legacy Legal", "Legacy Display", null, null, null,
            "legacy note", java.util.List.of("BRA", "PHL"));
        var digest = cryptography.idempotencyDigest("mch-idempotency-v1", 1, java.util.List.of(
            "1", "UPDATE_PROFILE", Long.toString(submitted.merchantId()), "1",
            "Legacy Legal", "Legacy Display", "legacy note", "BRA", "PHL"));
        dsl.execute("""
            INSERT INTO merchant_command_dedup(
              id,actor_account_domain,actor_tenant_id,actor_membership_id,command_type,
              idempotency_key,request_digest,idempotency_hmac_key_id,command_schema_version,
              canonical_digest_scheme_version,required_permission,merchant_id,
              result_merchant_code,result_status,result_row_version)
            VALUES (nextval('iam_id_seq'),'PLATFORM',1,63003,'UPDATE_PROFILE',?,?,?,1,1,
                    'merchant:update',?,?,?,2)
            """, legacyKey, digest.value(), digest.keyId(), submitted.merchantId(),
            submitted.merchantCode(), MerchantStatus.ACTIVE.name());
        long auditCount = value("SELECT count(*) FROM merchant_audit_event", Long.class);
        long dedupCount = value("SELECT count(*) FROM merchant_command_dedup", Long.class);

        assertThatThrownBy(() -> service.updateProfile(platformActor(),
            submitted.merchantId(), legacy))
            .isInstanceOf(MerchantException.PermissionDenied.class);
        assertThat(service.updateProfile(platform, submitted.merchantId(), legacy))
            .isEqualTo(new com.niv.payment.merchant.core.MerchantMutationResult(
                submitted.merchantId(), submitted.merchantCode(), MerchantStatus.ACTIVE, 2));
        assertThatThrownBy(() -> service.updateProfile(platform, submitted.merchantId(),
            new ProfileUpdateRequest(1, legacyKey, "Different", "Legacy Display",
                null, null, null, "legacy note", java.util.List.of("BRA", "PHL"))))
            .isInstanceOf(MerchantException.IdempotencyConflict.class);
        assertThatThrownBy(() -> service.updateProfile(platform, submitted.merchantId(),
            new ProfileUpdateRequest(1, UUID.randomUUID(), "Legacy Legal", "Legacy Display",
                null, null, null, "legacy note", java.util.List.of("BRA", "PHL"))))
            .isInstanceOf(MerchantException.StateConflict.class);
        assertThatThrownBy(() -> service.updateProfile(platform, submitted.merchantId(),
            new ProfileUpdateRequest(1, UUID.randomUUID(), "Legacy Legal", "Legacy Display",
                "DIRECT", null, "ENTERPRISE", "legacy note", java.util.List.of("BRA"))))
            .isInstanceOf(MerchantException.InvalidRequest.class);
        assertThat(value("SELECT count(*) FROM merchant_audit_event", Long.class))
            .isEqualTo(auditCount);
        assertThat(value("SELECT count(*) FROM merchant_command_dedup", Long.class))
            .isEqualTo(dedupCount);
        assertThat(value("SELECT row_version FROM merchant", Long.class)).isEqualTo(1);
    }

    @Test
    void registrationKeyRotationBackfillsAndAtomicallyCutsOverFutureWrites() {
        var submitted = service.submit(actor(), request(UUID.randomUUID(), null, "2026001234Z"));
        byte[] oldCiphertext = value("SELECT registration_ciphertext FROM merchant", byte[].class);

        var result = rotation.rotate(
            "mch-registration-search-v2", "mch-registration-aead-v2");
        assertRotationRoleReset();

        assertThat(result.rotatedMerchantCount()).isEqualTo(1);
        assertThat(value("SELECT registration_search_key_id FROM merchant", String.class))
            .isEqualTo("mch-registration-search-v2");
        assertThat(value("SELECT registration_aead_key_id FROM merchant", String.class))
            .isEqualTo("mch-registration-aead-v2");
        assertThat(value("""
            SELECT count(*) FROM merchant merchant_row
              JOIN merchant_protected_nonce nonce_row
                ON nonce_row.purpose='REGISTRATION_AEAD'
               AND nonce_row.key_id=merchant_row.registration_aead_key_id
               AND nonce_row.nonce=merchant_row.registration_nonce
             WHERE merchant_row.id=?
            """.replace("?", Long.toString(submitted.merchantId())), Long.class)).isOne();
        assertThat(value("SELECT registration_ciphertext FROM merchant", byte[].class))
            .isNotEqualTo(oldCiphertext);
        assertThat(value("SELECT row_version FROM merchant", Long.class)).isZero();
        assertThat(value("SELECT status FROM merchant", String.class)).isEqualTo("PENDING_REVIEW");
        assertThat(value("SELECT count(*) FROM merchant_audit_event", Long.class)).isEqualTo(1);
        assertThat(value("""
            SELECT count(*) FROM merchant_registration_key_metadata
             WHERE active AND key_id IN (
               'mch-registration-search-v2','mch-registration-aead-v2')
            """, Long.class)).isEqualTo(2);

        // v1 metadata sorts before the active v2 rows; switching both directions proves
        // activation does not depend on PostgreSQL's row-update order.
        rotation.rotate("mch-registration-search-v1", "mch-registration-aead-v1");
        rotation.rotate("mch-registration-search-v2", "mch-registration-aead-v2");

        var rejected = service.transition(platformStepUpActor(), new TransitionCommand(
            submitted.merchantId(), MerchantCommand.REJECT, "PROFILE_MISMATCH", 0,
            UUID.randomUUID()));
        service.submit(actor(), request(UUID.randomUUID(), rejected.rowVersion(), "2026009876Z"));
        assertThat(value("SELECT registration_search_key_id FROM merchant", String.class))
            .isEqualTo("mch-registration-search-v2");
        assertThat(value("SELECT registration_aead_key_id FROM merchant", String.class))
            .isEqualTo("mch-registration-aead-v2");
    }

    @Test
    void retainedRegistrationAndLegalIdSurviveAmendmentApprovalAndRemainDecryptable() {
        var createDocuments = uploadDocumentSet();
        var created = onboardingService.create(platformStepUpActor(),
            new MerchantOnboardingModels.CreateRequest(TENANT, UUID.randomUUID(),
                onboardingProfile(createDocuments,
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.REPLACE, "LEGAL-2026-1234"),
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.REPLACE, "REG-2026-5678"),
                    "Initial Display")));
        service.transition(platformReviewerStepUpActor(), new TransitionCommand(
            created.merchantId(), MerchantCommand.APPROVE, "PROFILE_VERIFIED", 0,
            UUID.randomUUID()));

        var amendmentDocuments = uploadDocumentSet();
        var amendment = onboardingService.createAmendment(platformStepUpActor(),
            new MerchantOnboardingModels.AmendmentRequest(created.merchantId(), 1,
                UUID.randomUUID(), onboardingProfile(amendmentDocuments,
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.RETAIN, null),
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.RETAIN, null),
                    "Amended Display")));
        assertThat(service.findPlatform(platformActor(), new MerchantQuery(
            null, null, MerchantStatus.ACTIVE, null, null, null, 1, 20))
            .reviewPendingMerchantIds()).contains(created.merchantId());
        var approved = onboardingService.reviewAmendment(platformReviewerStepUpActor(),
            new MerchantOnboardingModels.AmendmentReviewRequest(created.merchantId(),
                amendment.amendmentId(), 0, UUID.randomUUID(),
                MerchantOnboardingModels.AmendmentDecision.APPROVE,
                "PROFILE_AMENDMENT_VERIFIED"));

        assertThat(service.findPlatform(platformActor(), new MerchantQuery(
            null, null, MerchantStatus.ACTIVE, null, null, null, 1, 20))
            .reviewPendingMerchantIds()).doesNotContain(created.merchantId());

        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(approved.merchantStatus()).isEqualTo(MerchantStatus.ACTIVE);
        assertThat(approved.merchantRowVersion()).isEqualTo(2);
        Record protectedRow = dsl.fetchOne("""
            SELECT tenant_id,registration_country,registration_number_masked,
                   registration_fingerprint,registration_search_key_id,
                   registration_fingerprint_algorithm,registration_normalization_version,
                   registration_ciphertext,registration_nonce,registration_auth_tag,
                   registration_aead_key_id,registration_aead_algorithm,
                   legal_id_type_code,legal_id_no_masked,legal_id_ciphertext,legal_id_nonce,
                   legal_id_auth_tag,legal_id_aead_key_id,legal_id_aead_algorithm,
                   legal_id_aad_scheme_version,legal_id_protection_version
              FROM merchant WHERE id=?
            """, created.merchantId());
        var registration = new com.niv.payment.merchant.core.crypto.ProtectedRegistration(1,
            protectedRow.get(6, Integer.class), protectedRow.get(11, String.class),
            protectedRow.get(10, String.class), protectedRow.get(8, byte[].class),
            protectedRow.get(7, byte[].class), protectedRow.get(9, byte[].class),
            protectedRow.get(2, String.class),
            new com.niv.payment.merchant.core.crypto.RegistrationFingerprint(1,
                protectedRow.get(6, Integer.class), protectedRow.get(5, String.class),
                protectedRow.get(4, String.class), protectedRow.get(3, byte[].class)));
        var legalId = new com.niv.payment.merchant.core.crypto.ProtectedPayload(
            protectedRow.get(19, Integer.class), protectedRow.get(20, Integer.class),
            protectedRow.get(18, String.class), protectedRow.get(17, String.class),
            protectedRow.get(15, byte[].class), protectedRow.get(14, byte[].class),
            protectedRow.get(16, byte[].class), protectedRow.get(13, String.class));
        assertThat(cryptography.decryptNormalized(created.merchantId(), TENANT,
            protectedRow.get(1, String.class), registration)).isEqualTo("REG20265678");
        assertThat(onboardingCryptography.decryptLegalId(created.merchantId(), TENANT,
            protectedRow.get(12, String.class), legalId)).isEqualTo("LEGAL20261234");
        assertThat(value("SELECT display_name FROM merchant", String.class))
            .isEqualTo("Amended Display");
    }

    @Test
    void amendmentRetainsAllCurrentDocumentsWithoutReuploadingThem() {
        var currentDocuments = uploadDocumentSet(42);
        var created = onboardingService.create(platformStepUpActor(),
            new MerchantOnboardingModels.CreateRequest(TENANT, UUID.randomUUID(),
                onboardingProfile(currentDocuments,
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.REPLACE, "LEGAL-2026-1234"),
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.REPLACE, "REG-2026-5678"),
                    "Initial Display")));
        service.transition(platformReviewerStepUpActor(), new TransitionCommand(
            created.merchantId(), MerchantCommand.APPROVE, "PROFILE_VERIFIED", 0,
            UUID.randomUUID()));

        var amendment = onboardingService.createAmendment(platformStepUpActor(),
            new MerchantOnboardingModels.AmendmentRequest(created.merchantId(), 1,
                UUID.randomUUID(), onboardingProfile(currentDocuments,
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.RETAIN, null),
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.RETAIN, null),
                    "Text Only Amendment")));

        assertThat(value("SELECT count(*) FROM merchant_document WHERE amendment_id="
            + amendment.amendmentId(), Long.class)).isZero();
        assertThat(value("SELECT count(*) FROM merchant_amendment_document WHERE amendment_id="
            + amendment.amendmentId() + " AND document_mode='RETAIN'", Long.class)).isEqualTo(5);
        assertThat(onboardingService.pendingAmendment(platformActor(), created.merchantId())
            .profile().brandLogoDocument().documentId())
            .isEqualTo(currentDocuments.get(MerchantOnboardingModels.DocumentKind.BRAND_LOGO));
        assertThat(onboardingService.merchantDocumentContent(platformStepUpActor(),
            created.merchantId(), MerchantOnboardingModels.DocumentKind.BRAND_LOGO,
            amendment.amendmentId()).content()).containsExactly((byte) 1, (byte) 42);
        assertThatThrownBy(() -> dsl.execute("""
            UPDATE merchant_amendment_document SET document_mode='REPLACE'
             WHERE amendment_id=? AND kind='BRAND_LOGO'
            """, amendment.amendmentId()))
            .isInstanceOf(org.jooq.exception.DataAccessException.class)
            .hasMessageContaining("append-only");
        assertThatThrownBy(() -> dsl.execute("""
            DELETE FROM merchant_amendment_document
             WHERE amendment_id=? AND kind='BRAND_LOGO'
            """, amendment.amendmentId()))
            .isInstanceOf(org.jooq.exception.DataAccessException.class)
            .hasMessageContaining("append-only");

        onboardingService.reviewAmendment(platformReviewerStepUpActor(),
            new MerchantOnboardingModels.AmendmentReviewRequest(created.merchantId(),
                amendment.amendmentId(), 0, UUID.randomUUID(),
                MerchantOnboardingModels.AmendmentDecision.APPROVE,
                "PROFILE_AMENDMENT_VERIFIED"));
        for (var entry : currentDocuments.entrySet()) {
            assertThat(value("SELECT document_id FROM merchant_document_binding WHERE merchant_id="
                + created.merchantId() + " AND kind='" + entry.getKey().name() + "'", Long.class))
                .isEqualTo(entry.getValue());
        }
        assertThat(value("SELECT display_name FROM merchant", String.class))
            .isEqualTo("Text Only Amendment");
    }

    @Test
    void amendmentReplacesOneDocumentAndRetainsTheOtherFour() {
        var currentDocuments = uploadDocumentSet(42);
        var created = onboardingService.create(platformStepUpActor(),
            new MerchantOnboardingModels.CreateRequest(TENANT, UUID.randomUUID(),
                onboardingProfile(currentDocuments,
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.REPLACE, "LEGAL-2026-1234"),
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.REPLACE, "REG-2026-5678"),
                    "Initial Display")));
        service.transition(platformReviewerStepUpActor(), new TransitionCommand(
            created.merchantId(), MerchantCommand.APPROVE, "PROFILE_VERIFIED", 0,
            UUID.randomUUID()));
        var replacement = onboardingService.uploadDocument(platformStepUpActor(),
            new MerchantOnboardingModels.DocumentUploadRequest(TENANT,
                MerchantOnboardingModels.DocumentKind.BRAND_LOGO, "image/png",
                new byte[]{1, 77}, 1, 1));
        var proposedDocuments = new java.util.EnumMap<MerchantOnboardingModels.DocumentKind, Long>(
            currentDocuments);
        proposedDocuments.put(MerchantOnboardingModels.DocumentKind.BRAND_LOGO,
            replacement.documentId());

        var amendment = onboardingService.createAmendment(platformStepUpActor(),
            new MerchantOnboardingModels.AmendmentRequest(created.merchantId(), 1,
                UUID.randomUUID(), onboardingProfile(Map.copyOf(proposedDocuments),
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.RETAIN, null),
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.RETAIN, null),
                    "Logo Amendment")));

        assertThat(value("SELECT count(*) FROM merchant_amendment_document WHERE amendment_id="
            + amendment.amendmentId() + " AND document_mode='REPLACE'", Long.class)).isOne();
        assertThat(value("SELECT count(*) FROM merchant_amendment_document WHERE amendment_id="
            + amendment.amendmentId() + " AND document_mode='RETAIN'", Long.class)).isEqualTo(4);
        assertThat(value("SELECT attachment_scope FROM merchant_document WHERE id="
            + replacement.documentId(), String.class)).isEqualTo("AMENDMENT");

        onboardingService.reviewAmendment(platformReviewerStepUpActor(),
            new MerchantOnboardingModels.AmendmentReviewRequest(created.merchantId(),
                amendment.amendmentId(), 0, UUID.randomUUID(),
                MerchantOnboardingModels.AmendmentDecision.APPROVE,
                "PROFILE_AMENDMENT_VERIFIED"));
        assertThat(value("SELECT document_id FROM merchant_document_binding WHERE merchant_id="
            + created.merchantId() + " AND kind='BRAND_LOGO'", Long.class))
            .isEqualTo(replacement.documentId());
        assertThat(value("SELECT superseded_at IS NOT NULL FROM merchant_document WHERE id="
            + currentDocuments.get(MerchantOnboardingModels.DocumentKind.BRAND_LOGO), Boolean.class))
            .isTrue();
        for (var kind : java.util.List.of(
            MerchantOnboardingModels.DocumentKind.BUSINESS_LICENSE,
            MerchantOnboardingModels.DocumentKind.LEGAL_ID_FRONT,
            MerchantOnboardingModels.DocumentKind.LEGAL_ID_BACK,
            MerchantOnboardingModels.DocumentKind.LEGAL_ID_HOLDING)) {
            assertThat(value("SELECT document_id FROM merchant_document_binding WHERE merchant_id="
                + created.merchantId() + " AND kind='" + kind.name() + "'", Long.class))
                .isEqualTo(currentDocuments.get(kind));
        }
    }

    @Test
    void driftedRejectedAmendmentRecordsTheAttemptedDecisionAndReplaysAsStale() {
        var created = onboardingService.create(platformStepUpActor(),
            new MerchantOnboardingModels.CreateRequest(TENANT, UUID.randomUUID(),
                onboardingProfile(uploadDocumentSet(),
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.REPLACE, "LEGAL-STALE-1"),
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.REPLACE, "REG-STALE-1"),
                    "Initial Display")));
        service.transition(platformReviewerStepUpActor(), new TransitionCommand(
            created.merchantId(), MerchantCommand.APPROVE, "PROFILE_VERIFIED", 0,
            UUID.randomUUID()));
        var amendment = onboardingService.createAmendment(platformStepUpActor(),
            new MerchantOnboardingModels.AmendmentRequest(created.merchantId(), 1,
                UUID.randomUUID(), onboardingProfile(uploadDocumentSet(77),
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.RETAIN, null),
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.RETAIN, null),
                    "Stale Display")));
        service.transition(platformReviewerStepUpActor(), new TransitionCommand(
            created.merchantId(), MerchantCommand.DISABLE, "RISK_CONTROL", 1,
            UUID.randomUUID()));
        UUID reviewKey = UUID.randomUUID();
        var request = new MerchantOnboardingModels.AmendmentReviewRequest(created.merchantId(),
            amendment.amendmentId(), 0, reviewKey,
            MerchantOnboardingModels.AmendmentDecision.REJECT, "COMPLIANCE_REJECTED");

        assertThatThrownBy(() -> onboardingService.reviewAmendment(
            platformReviewerStepUpActor(), request))
            .isInstanceOf(MerchantException.OptimisticLockConflict.class);
        Record stale = dsl.fetchOne("""
            SELECT status,decision,decision_reason_code,row_version
              FROM merchant_amendment WHERE id=?
            """, amendment.amendmentId());
        assertThat(stale.get("status", String.class)).isEqualTo("STALE");
        assertThat(stale.get("decision", String.class)).isEqualTo("REJECT");
        assertThat(stale.get("decision_reason_code", String.class))
            .isEqualTo("PLATFORM_AMENDMENT_STALE");
        assertThat(stale.get("row_version", Long.class)).isOne();
        assertThat(value("SELECT action_code FROM merchant_amendment_audit_event "
            + "WHERE amendment_id=" + amendment.amendmentId() + " AND amendment_version=1",
            String.class)).isEqualTo("STALE");
        assertThat(value("SELECT result_status FROM merchant_amendment_command_dedup "
            + "WHERE command_type='REVIEW' AND idempotency_key='" + reviewKey + "'",
            String.class)).isEqualTo("STALE");
        assertThatThrownBy(() -> onboardingService.reviewAmendment(
            platformReviewerStepUpActor(), request))
            .isInstanceOf(MerchantException.OptimisticLockConflict.class);
    }

    @Test
    void duplicateRegistrationDuringAmendmentApprovalMapsConflictAndRollsBackEverything() {
        var created = onboardingService.create(platformStepUpActor(),
            new MerchantOnboardingModels.CreateRequest(TENANT, UUID.randomUUID(),
                onboardingProfile(uploadDocumentSet(),
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.REPLACE, "LEGAL-CONFLICT-A"),
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.REPLACE, "REG-CONFLICT-A"),
                    "Merchant A")));
        service.transition(platformReviewerStepUpActor(), new TransitionCommand(
            created.merchantId(), MerchantCommand.APPROVE, "PROFILE_VERIFIED", 0,
            UUID.randomUUID()));
        var amendment = onboardingService.createAmendment(platformStepUpActor(),
            new MerchantOnboardingModels.AmendmentRequest(created.merchantId(), 1,
                UUID.randomUUID(), onboardingProfile(uploadDocumentSet(77),
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.RETAIN, null),
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.REPLACE, "REG-CONFLICT-B"),
                    "Merchant A Amended")));
        onboardingService.create(platformStepUpActor(),
            new MerchantOnboardingModels.CreateRequest(SECOND_TENANT, UUID.randomUUID(),
                onboardingProfile(uploadDocumentSet(SECOND_TENANT, 88),
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.REPLACE, "LEGAL-CONFLICT-B"),
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.REPLACE, "REG-CONFLICT-B"),
                    "Merchant B")));
        long merchantAuditBefore = value("SELECT count(*) FROM merchant_audit_event", Long.class);
        long amendmentAuditBefore = value(
            "SELECT count(*) FROM merchant_amendment_audit_event", Long.class);
        long receiptBefore = value(
            "SELECT count(*) FROM merchant_amendment_command_dedup", Long.class);
        String maskedBefore = value("SELECT registration_number_masked FROM merchant WHERE id="
            + created.merchantId(), String.class);

        assertThatThrownBy(() -> onboardingService.reviewAmendment(
            platformReviewerStepUpActor(),
            new MerchantOnboardingModels.AmendmentReviewRequest(created.merchantId(),
                amendment.amendmentId(), 0, UUID.randomUUID(),
                MerchantOnboardingModels.AmendmentDecision.APPROVE,
                "PROFILE_AMENDMENT_VERIFIED")))
            .isInstanceOf(MerchantException.DataConflict.class);

        Record pending = dsl.fetchOne("""
            SELECT status,decision,row_version FROM merchant_amendment WHERE id=?
            """, amendment.amendmentId());
        assertThat(pending.get("status", String.class)).isEqualTo("PENDING_REVIEW");
        assertThat(pending.get("decision", String.class)).isNull();
        assertThat(pending.get("row_version", Long.class)).isZero();
        assertThat(value("SELECT registration_number_masked FROM merchant WHERE id="
            + created.merchantId(), String.class)).isEqualTo(maskedBefore);
        assertThat(value("SELECT row_version FROM merchant WHERE id=" + created.merchantId(),
            Long.class)).isOne();
        assertThat(value("SELECT count(*) FROM merchant_audit_event", Long.class))
            .isEqualTo(merchantAuditBefore);
        assertThat(value("SELECT count(*) FROM merchant_amendment_audit_event", Long.class))
            .isEqualTo(amendmentAuditBefore);
        assertThat(value("SELECT count(*) FROM merchant_amendment_command_dedup", Long.class))
            .isEqualTo(receiptBefore);
    }

    @Test
    void equivalentFormattedReplacementSecretsReplayCreateAndAmendmentPermanently() {
        var createDocuments = uploadDocumentSet();
        UUID createKey = UUID.randomUUID();
        var created = onboardingService.create(platformStepUpActor(),
            new MerchantOnboardingModels.CreateRequest(TENANT, createKey,
                onboardingProfile(createDocuments,
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.REPLACE, " Legal/Id-0001 "),
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.REPLACE, " Reg/No-0001 "),
                    "Canonical Create")));
        var createReplay = onboardingService.create(platformStepUpActor(),
            new MerchantOnboardingModels.CreateRequest(TENANT, createKey,
                onboardingProfile(createDocuments,
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.REPLACE, "legal.id 0001"),
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.REPLACE, "reg.no 0001"),
                    "Canonical Create")));
        assertThat(createReplay).isEqualTo(created);
        assertThat(value("SELECT count(*) FROM merchant_command_dedup WHERE command_type='CREATE'",
            Long.class)).isOne();
        service.transition(platformReviewerStepUpActor(), new TransitionCommand(
            created.merchantId(), MerchantCommand.APPROVE, "PROFILE_VERIFIED", 0,
            UUID.randomUUID()));

        var amendmentDocuments = uploadDocumentSet(77);
        UUID amendmentKey = UUID.randomUUID();
        var amendment = onboardingService.createAmendment(platformStepUpActor(),
            new MerchantOnboardingModels.AmendmentRequest(created.merchantId(), 1,
                amendmentKey, onboardingProfile(amendmentDocuments,
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.REPLACE, " New/Legal-777 "),
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.REPLACE, " New/Reg-777 "),
                    "Canonical Amendment")));
        var amendmentReplay = onboardingService.createAmendment(platformStepUpActor(),
            new MerchantOnboardingModels.AmendmentRequest(created.merchantId(), 1,
                amendmentKey, onboardingProfile(amendmentDocuments,
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.REPLACE, "new.legal 777"),
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.REPLACE, "new.reg 777"),
                    "Canonical Amendment")));
        assertThat(amendmentReplay).isEqualTo(amendment);
        assertThat(value("SELECT count(*) FROM merchant_amendment_command_dedup "
            + "WHERE command_type='AMEND'", Long.class)).isOne();
        assertThat(value("SELECT count(*) FROM merchant_amendment_audit_event "
            + "WHERE amendment_id=" + amendment.amendmentId(), Long.class)).isOne();
    }

    @Test
    void amendmentRotationAclAllowsOnlyGuardedCryptoColumnsForTheIsolatedPrincipal() {
        var created = onboardingService.create(platformStepUpActor(),
            new MerchantOnboardingModels.CreateRequest(TENANT, UUID.randomUUID(),
                onboardingProfile(uploadDocumentSet(),
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.REPLACE, "LEGAL-ACL-1"),
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.REPLACE, "REG-ACL-1"),
                    "ACL Merchant")));
        service.transition(platformReviewerStepUpActor(), new TransitionCommand(
            created.merchantId(), MerchantCommand.APPROVE, "PROFILE_VERIFIED", 0,
            UUID.randomUUID()));
        var amendment = onboardingService.createAmendment(platformStepUpActor(),
            new MerchantOnboardingModels.AmendmentRequest(created.merchantId(), 1,
                UUID.randomUUID(), onboardingProfile(uploadDocumentSet(77),
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.REPLACE, "LEGAL-ACL-2"),
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.REPLACE, "REG-ACL-2"),
                    "ACL Amendment")));

        assertThatThrownBy(() -> dsl.execute("""
            UPDATE merchant_amendment SET registration_ciphertext=decode('01','hex') WHERE id=?
            """, amendment.amendmentId())).isInstanceOf(org.jooq.exception.DataAccessException.class)
            .hasMessageContaining("immutable outside rotation");
        executeAsRotationOperator("""
            INSERT INTO merchant_protected_nonce(purpose,key_id,algorithm,nonce)
            SELECT 'REGISTRATION_AEAD',registration_aead_key_id,
                   registration_aead_algorithm,decode(repeat('a7',12),'hex')
              FROM merchant_amendment WHERE id=%d;
            UPDATE merchant_amendment SET registration_nonce=decode(repeat('a7',12),'hex')
             WHERE id=%d
            """.formatted(amendment.amendmentId(), amendment.amendmentId()));
        assertThat(value("SELECT encode(registration_nonce,'hex') FROM merchant_amendment WHERE id="
            + amendment.amendmentId(), String.class)).isEqualTo("a7".repeat(12));
        assertThatThrownBy(() -> executeAsRotationOperator("""
            UPDATE merchant_amendment SET display_name='FORGED' WHERE id=%d
            """.formatted(amendment.amendmentId())))
            .isInstanceOf(org.jooq.exception.DataAccessException.class);
        assertThatThrownBy(() -> executeAsRotationOperator("""
            UPDATE merchant_amendment SET status='REJECTED' WHERE id=%d
            """.formatted(amendment.amendmentId())))
            .isInstanceOf(org.jooq.exception.DataAccessException.class);
        assertThatThrownBy(() -> executeAsRotationOperator("""
            UPDATE merchant_amendment SET author_membership_id=63013 WHERE id=%d
            """.formatted(amendment.amendmentId())))
            .isInstanceOf(org.jooq.exception.DataAccessException.class);
        assertThat(value("SELECT display_name FROM merchant_amendment WHERE id="
            + amendment.amendmentId(), String.class)).isEqualTo("ACL Amendment");
        assertThat(value("SELECT status FROM merchant_amendment WHERE id="
            + amendment.amendmentId(), String.class)).isEqualTo("PENDING_REVIEW");
        assertThat(value("SELECT author_membership_id FROM merchant_amendment WHERE id="
            + amendment.amendmentId(), Long.class)).isEqualTo(63003L);
    }

    @Test
    void fullRotationCoversAllRetainedEvidenceAndApprovalCannotRestoreRetiredKeys() {
        var created = onboardingService.create(platformStepUpActor(),
            new MerchantOnboardingModels.CreateRequest(TENANT, UUID.randomUUID(),
                onboardingProfile(uploadDocumentSet(42),
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.REPLACE, "LEGAL-ROTATE-1"),
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.REPLACE, "REG-ROTATE-1"),
                    "Rotation Merchant")));
        service.transition(platformReviewerStepUpActor(), new TransitionCommand(
            created.merchantId(), MerchantCommand.APPROVE, "PROFILE_VERIFIED", 0,
            UUID.randomUUID()));
        var rejected = onboardingService.createAmendment(platformStepUpActor(),
            new MerchantOnboardingModels.AmendmentRequest(created.merchantId(), 1,
                UUID.randomUUID(), onboardingProfile(uploadDocumentSet(77),
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.REPLACE, "LEGAL-ROTATE-2"),
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.REPLACE, "REG-ROTATE-2"),
                    "Rejected Rotation")));
        onboardingService.reviewAmendment(platformReviewerStepUpActor(),
            new MerchantOnboardingModels.AmendmentReviewRequest(created.merchantId(),
                rejected.amendmentId(), 0, UUID.randomUUID(),
                MerchantOnboardingModels.AmendmentDecision.REJECT, "COMPLIANCE_REJECTED"));
        var pending = onboardingService.createAmendment(platformStepUpActor(),
            new MerchantOnboardingModels.AmendmentRequest(created.merchantId(), 1,
                UUID.randomUUID(), onboardingProfile(uploadDocumentSet(88),
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.REPLACE, "LEGAL-ROTATE-3"),
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.REPLACE, "REG-ROTATE-3"),
                    "Pending Rotation")));

        var rotated = rotation.rotateAll("mch-registration-search-v2",
            "mch-registration-aead-v2", "mch-legal-id-aead-v2", "mch-document-aead-v2");

        assertThat(rotated.rotatedRegistrationMerchantCount()).isOne();
        assertThat(rotated.rotatedRegistrationAmendmentCount()).isEqualTo(2);
        assertThat(rotated.rotatedLegalIdMerchantCount()).isOne();
        assertThat(rotated.rotatedLegalIdAmendmentCount()).isEqualTo(2);
        assertThat(rotated.rotatedDocumentCount()).isEqualTo(15);
        assertThat(value("SELECT count(*) FROM merchant WHERE registration_search_key_id="
            + "'mch-registration-search-v2' AND registration_aead_key_id="
            + "'mch-registration-aead-v2' AND legal_id_aead_key_id='mch-legal-id-aead-v2'",
            Long.class)).isOne();
        assertThat(value("SELECT count(*) FROM merchant_amendment WHERE registration_search_key_id="
            + "'mch-registration-search-v2' AND registration_aead_key_id="
            + "'mch-registration-aead-v2' AND legal_id_aead_key_id='mch-legal-id-aead-v2'",
            Long.class)).isEqualTo(2);
        assertThat(value("SELECT count(*) FROM merchant_document WHERE aead_key_id="
            + "'mch-document-aead-v2'", Long.class)).isEqualTo(15);
        assertThat(value("SELECT count(*) FROM merchant_registration_key_metadata WHERE active "
            + "AND key_id IN ('mch-registration-search-v2','mch-registration-aead-v2',"
            + "'mch-legal-id-aead-v2','mch-document-aead-v2')", Long.class)).isEqualTo(4);

        var approved = onboardingService.reviewAmendment(platformReviewerStepUpActor(),
            new MerchantOnboardingModels.AmendmentReviewRequest(created.merchantId(),
                pending.amendmentId(), 0, UUID.randomUUID(),
                MerchantOnboardingModels.AmendmentDecision.APPROVE,
                "PROFILE_AMENDMENT_VERIFIED"));
        assertThat(approved.status()).isEqualTo("APPROVED");
        assertThat(value("SELECT registration_search_key_id FROM merchant WHERE id="
            + created.merchantId(), String.class)).isEqualTo("mch-registration-search-v2");
        assertThat(value("SELECT registration_aead_key_id FROM merchant WHERE id="
            + created.merchantId(), String.class)).isEqualTo("mch-registration-aead-v2");
        assertThat(value("SELECT legal_id_aead_key_id FROM merchant WHERE id="
            + created.merchantId(), String.class)).isEqualTo("mch-legal-id-aead-v2");
        assertThat(onboardingService.merchantDocumentContent(platformStepUpActor(),
            created.merchantId(), MerchantOnboardingModels.DocumentKind.BRAND_LOGO, null).content())
            .containsExactly((byte) 1, (byte) 88);
    }

    @Test
    void legacyRegistrationRotationAlsoCoversPendingAmendmentBeforeApproval() {
        var created = onboardingService.create(platformStepUpActor(),
            new MerchantOnboardingModels.CreateRequest(TENANT, UUID.randomUUID(),
                onboardingProfile(uploadDocumentSet(42),
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.REPLACE, "LEGAL-OLD-ROTATE"),
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.REPLACE, "REG-OLD-ROTATE"),
                    "Old API Rotation")));
        service.transition(platformReviewerStepUpActor(), new TransitionCommand(
            created.merchantId(), MerchantCommand.APPROVE, "PROFILE_VERIFIED", 0,
            UUID.randomUUID()));
        var pending = onboardingService.createAmendment(platformStepUpActor(),
            new MerchantOnboardingModels.AmendmentRequest(created.merchantId(), 1,
                UUID.randomUUID(), onboardingProfile(uploadDocumentSet(77),
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.RETAIN, null),
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.REPLACE, "REG-PENDING-ROTATE"),
                    "Old API Pending")));

        rotation.rotate("mch-registration-search-v2", "mch-registration-aead-v2");

        assertThat(value("SELECT registration_search_key_id FROM merchant_amendment WHERE id="
            + pending.amendmentId(), String.class)).isEqualTo("mch-registration-search-v2");
        assertThat(value("SELECT registration_aead_key_id FROM merchant_amendment WHERE id="
            + pending.amendmentId(), String.class)).isEqualTo("mch-registration-aead-v2");
        onboardingService.reviewAmendment(platformReviewerStepUpActor(),
            new MerchantOnboardingModels.AmendmentReviewRequest(created.merchantId(),
                pending.amendmentId(), 0, UUID.randomUUID(),
                MerchantOnboardingModels.AmendmentDecision.APPROVE,
                "PROFILE_AMENDMENT_VERIFIED"));
        assertThat(value("SELECT registration_search_key_id FROM merchant WHERE id="
            + created.merchantId(), String.class)).isEqualTo("mch-registration-search-v2");
        assertThat(value("SELECT registration_aead_key_id FROM merchant WHERE id="
            + created.merchantId(), String.class)).isEqualTo("mch-registration-aead-v2");
    }

    @Test
    void fullRotationSeparatesLegacyNullLegalCoverageFromRegistrationCoverage() {
        var legacy = service.submit(actor(), request(UUID.randomUUID(), null, "LEGACY-REG-1"));
        var created = onboardingService.create(platformStepUpActor(),
            new MerchantOnboardingModels.CreateRequest(SECOND_TENANT, UUID.randomUUID(),
                onboardingProfile(uploadDocumentSet(SECOND_TENANT, 42),
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.REPLACE, "LEGAL-MIXED-1"),
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.REPLACE, "REG-MIXED-1"),
                    "Mixed Rotation")));
        service.transition(platformReviewerStepUpActor(), new TransitionCommand(
            created.merchantId(), MerchantCommand.APPROVE, "PROFILE_VERIFIED", 0,
            UUID.randomUUID()));
        onboardingService.createAmendment(platformStepUpActor(),
            new MerchantOnboardingModels.AmendmentRequest(created.merchantId(), 1,
                UUID.randomUUID(), onboardingProfile(uploadDocumentSet(SECOND_TENANT, 77),
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.REPLACE, "LEGAL-MIXED-2"),
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.REPLACE, "REG-MIXED-2"),
                    "Mixed Pending")));

        var rotated = rotation.rotateAll("mch-registration-search-v2",
            "mch-registration-aead-v2", "mch-legal-id-aead-v2", "mch-document-aead-v2");

        assertThat(rotated.rotatedRegistrationMerchantCount()).isEqualTo(2);
        assertThat(rotated.rotatedRegistrationAmendmentCount()).isOne();
        assertThat(rotated.rotatedLegalIdMerchantCount()).isOne();
        assertThat(rotated.rotatedLegalIdAmendmentCount()).isOne();
        assertThat(rotated.rotatedDocumentCount()).isEqualTo(10);
        Record historical = dsl.fetchOne("""
            SELECT registration_search_key_id,registration_aead_key_id,
                   legal_id_ciphertext,legal_id_nonce,legal_id_aead_key_id
              FROM merchant WHERE id=?
            """, legacy.merchantId());
        assertThat(historical.get(0, String.class)).isEqualTo("mch-registration-search-v2");
        assertThat(historical.get(1, String.class)).isEqualTo("mch-registration-aead-v2");
        assertThat(historical.get(2)).isNull();
        assertThat(historical.get(3)).isNull();
        assertThat(historical.get(4)).isNull();
    }

    @Test
    void fullRotationAuthenticationFailureRollsBackRowsNoncesAndFourKeyCutover() {
        var created = onboardingService.create(platformStepUpActor(),
            new MerchantOnboardingModels.CreateRequest(TENANT, UUID.randomUUID(),
                onboardingProfile(uploadDocumentSet(42),
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.REPLACE, "LEGAL-ROLLBACK-1"),
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.REPLACE, "REG-ROLLBACK-1"),
                    "Rollback Rotation")));
        service.transition(platformReviewerStepUpActor(), new TransitionCommand(
            created.merchantId(), MerchantCommand.APPROVE, "PROFILE_VERIFIED", 0,
            UUID.randomUUID()));
        var pending = onboardingService.createAmendment(platformStepUpActor(),
            new MerchantOnboardingModels.AmendmentRequest(created.merchantId(), 1,
                UUID.randomUUID(), onboardingProfile(uploadDocumentSet(77),
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.REPLACE, "LEGAL-ROLLBACK-2"),
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.REPLACE, "REG-ROLLBACK-2"),
                    "Rollback Pending")));
        executeAsRotationOperator("""
            UPDATE merchant_amendment SET legal_id_auth_tag=decode(repeat('00',16),'hex')
             WHERE id=%d
            """.formatted(pending.amendmentId()));
        long nonceCountBefore = value("SELECT count(*) FROM merchant_protected_nonce", Long.class);

        assertThatThrownBy(() -> rotation.rotateAll("mch-registration-search-v2",
            "mch-registration-aead-v2", "mch-legal-id-aead-v2", "mch-document-aead-v2"))
            .isInstanceOf(MerchantException.ProtectedFieldUnavailable.class);
        assertRotationRoleReset();

        assertThat(value("SELECT count(*) FROM merchant WHERE registration_search_key_id="
            + "'mch-registration-search-v1' AND registration_aead_key_id="
            + "'mch-registration-aead-v1' AND legal_id_aead_key_id='mch-legal-id-aead-v1'",
            Long.class)).isOne();
        assertThat(value("SELECT count(*) FROM merchant_amendment WHERE registration_search_key_id="
            + "'mch-registration-search-v1' AND registration_aead_key_id="
            + "'mch-registration-aead-v1' AND legal_id_aead_key_id='mch-legal-id-aead-v1'",
            Long.class)).isOne();
        assertThat(value("SELECT count(*) FROM merchant_document WHERE aead_key_id="
            + "'mch-document-aead-v1'", Long.class)).isEqualTo(10);
        assertThat(value("SELECT count(*) FROM merchant_registration_key_metadata WHERE active "
            + "AND key_id IN ('mch-registration-search-v1','mch-registration-aead-v1',"
            + "'mch-legal-id-aead-v1','mch-document-aead-v1')", Long.class)).isEqualTo(4);
        assertThat(value("SELECT count(*) FROM merchant_registration_key_metadata WHERE "
            + "key_id IN ('mch-registration-search-v2','mch-registration-aead-v2',"
            + "'mch-legal-id-aead-v2','mch-document-aead-v2')", Long.class)).isZero();
        assertThat(value("SELECT count(*) FROM merchant_protected_nonce", Long.class))
            .isEqualTo(nonceCountBefore);
    }

    @Test
    void pendingAmendmentDocumentPreviewIsExactAndNeverFallsBackToCurrentBinding() {
        var createDocuments = uploadDocumentSet(42);
        var created = onboardingService.create(platformStepUpActor(),
            new MerchantOnboardingModels.CreateRequest(TENANT, UUID.randomUUID(),
                onboardingProfile(createDocuments,
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.REPLACE, "LEGAL-2026-1234"),
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.REPLACE, "REG-2026-5678"),
                    "Initial Display")));
        service.transition(platformReviewerStepUpActor(), new TransitionCommand(
            created.merchantId(), MerchantCommand.APPROVE, "PROFILE_VERIFIED", 0,
            UUID.randomUUID()));
        var amendmentDocuments = uploadDocumentSet(77);
        var amendment = onboardingService.createAmendment(platformStepUpActor(),
            new MerchantOnboardingModels.AmendmentRequest(created.merchantId(), 1,
                UUID.randomUUID(), onboardingProfile(amendmentDocuments,
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.RETAIN, null),
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.RETAIN, null),
                    "Amended Display")));

        assertThat(onboardingService.merchantDocumentContent(platformStepUpActor(),
            created.merchantId(), MerchantOnboardingModels.DocumentKind.BRAND_LOGO, null).content())
            .containsExactly((byte) 1, (byte) 42);
        assertThat(onboardingService.merchantDocumentContent(platformStepUpActor(),
            created.merchantId(), MerchantOnboardingModels.DocumentKind.BRAND_LOGO,
            amendment.amendmentId()).content()).containsExactly((byte) 1, (byte) 77);
        assertThatThrownBy(() -> onboardingService.merchantDocumentContent(platformStepUpActor(),
            created.merchantId() + 1, MerchantOnboardingModels.DocumentKind.BRAND_LOGO,
            amendment.amendmentId())).isInstanceOf(MerchantException.ResourceNotFound.class);
        assertThatThrownBy(() -> onboardingService.merchantDocumentContent(platformStepUpActor(),
            created.merchantId(), MerchantOnboardingModels.DocumentKind.BRAND_LOGO,
            amendment.amendmentId() + 1)).isInstanceOf(MerchantException.ResourceNotFound.class);

        onboardingService.reviewAmendment(platformReviewerStepUpActor(),
            new MerchantOnboardingModels.AmendmentReviewRequest(created.merchantId(),
                amendment.amendmentId(), 0, UUID.randomUUID(),
                MerchantOnboardingModels.AmendmentDecision.APPROVE,
                "PROFILE_AMENDMENT_VERIFIED"));
        assertThatThrownBy(() -> onboardingService.merchantDocumentContent(platformStepUpActor(),
            created.merchantId(), MerchantOnboardingModels.DocumentKind.BRAND_LOGO,
            amendment.amendmentId())).isInstanceOf(MerchantException.ResourceNotFound.class);
    }

    @Test
    void temporaryDocumentDeletionAndCleanupAreBoundedAndPreserveAllAttachedEvidence() {
        var createDocuments = uploadDocumentSet(42);
        var created = onboardingService.create(platformStepUpActor(),
            new MerchantOnboardingModels.CreateRequest(TENANT, UUID.randomUUID(),
                onboardingProfile(createDocuments,
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.REPLACE, "LEGAL-2026-1234"),
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.REPLACE, "REG-2026-5678"),
                    "Initial Display")));
        service.transition(platformReviewerStepUpActor(), new TransitionCommand(
            created.merchantId(), MerchantCommand.APPROVE, "PROFILE_VERIFIED", 0,
            UUID.randomUUID()));
        var amendmentDocuments = uploadDocumentSet(77);
        var amendment = onboardingService.createAmendment(platformStepUpActor(),
            new MerchantOnboardingModels.AmendmentRequest(created.merchantId(), 1,
                UUID.randomUUID(), onboardingProfile(amendmentDocuments,
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.RETAIN, null),
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.RETAIN, null),
                    "Rejected Display")));
        onboardingService.reviewAmendment(platformReviewerStepUpActor(),
            new MerchantOnboardingModels.AmendmentReviewRequest(created.merchantId(),
                amendment.amendmentId(), 0, UUID.randomUUID(),
                MerchantOnboardingModels.AmendmentDecision.REJECT, "COMPLIANCE_REJECTED"));
        dsl.execute("UPDATE merchant_document SET expires_at=statement_timestamp()-INTERVAL '1 minute' "
            + "WHERE merchant_id=?", created.merchantId());

        var deleted = onboardingService.uploadDocument(platformStepUpActor(),
            new MerchantOnboardingModels.DocumentUploadRequest(TENANT,
                MerchantOnboardingModels.DocumentKind.BRAND_LOGO, "image/png",
                new byte[]{9, 9}, 1, 1));
        String deletedNonce = value("SELECT encode(nonce,'hex') FROM merchant_document WHERE id="
            + deleted.documentId(), String.class);
        onboardingService.deleteDocument(platformStepUpActor(), deleted.documentId());
        assertThat(value("SELECT count(*) FROM merchant_document WHERE id=" + deleted.documentId(),
            Long.class)).isZero();
        assertThat(value("SELECT count(*) FROM merchant_protected_nonce WHERE purpose='DOCUMENT_AEAD' "
            + "AND encode(nonce,'hex')='" + deletedNonce + "'", Long.class)).isOne();

        for (int marker : new int[]{81, 82, 83}) {
            var temporary = onboardingService.uploadDocument(platformStepUpActor(),
                new MerchantOnboardingModels.DocumentUploadRequest(TENANT,
                    MerchantOnboardingModels.DocumentKind.BRAND_LOGO, "image/png",
                    new byte[]{9, (byte) marker}, 1, 1));
            dsl.execute("UPDATE merchant_document SET expires_at=statement_timestamp()-INTERVAL '1 minute' "
                + "WHERE id=?", temporary.documentId());
        }

        assertThat(documentMaintenance.deleteExpiredTemporaryDocuments(2)).isEqualTo(2);
        assertThat(documentMaintenance.deleteExpiredTemporaryDocuments(2)).isOne();
        assertThat(documentMaintenance.deleteExpiredTemporaryDocuments(2)).isZero();
        assertThat(value("SELECT count(*) FROM merchant_document WHERE merchant_id="
            + created.merchantId(), Long.class)).isEqualTo(10);
        assertThat(value("SELECT count(*) FROM merchant_document WHERE amendment_id="
            + amendment.amendmentId() + " AND attachment_scope='AMENDMENT'", Long.class))
            .isEqualTo(5);
    }

    @Test
    void registrationRotationFailureRollsBackRowsAndActiveMetadata() {
        service.submit(actor(), request(UUID.randomUUID(), null, "2026001234Z"));
        executeAsRotationOperator("""
            UPDATE merchant SET registration_auth_tag=decode(repeat('00',16),'hex')
             WHERE id=(SELECT id FROM merchant)
            """);
        byte[] corruptedCiphertext = value("SELECT registration_ciphertext FROM merchant", byte[].class);
        byte[] corruptedTag = value("SELECT registration_auth_tag FROM merchant", byte[].class);

        assertThatThrownBy(() -> rotation.rotate(
            "mch-registration-search-v2", "mch-registration-aead-v2"))
            .isInstanceOf(MerchantException.ProtectedFieldUnavailable.class);
        assertRotationRoleReset();

        assertThat(value("SELECT registration_search_key_id FROM merchant", String.class))
            .isEqualTo("mch-registration-search-v1");
        assertThat(value("SELECT registration_aead_key_id FROM merchant", String.class))
            .isEqualTo("mch-registration-aead-v1");
        assertThat(value("SELECT registration_ciphertext FROM merchant", byte[].class))
            .isEqualTo(corruptedCiphertext);
        assertThat(value("SELECT registration_auth_tag FROM merchant", byte[].class))
            .isEqualTo(corruptedTag);
        assertThat(value("SELECT count(*) FROM merchant_registration_key_metadata WHERE active "
            + "AND key_id LIKE '%-v1'", Long.class)).isEqualTo(4);
    }

    @Test
    void localIdentityVersionAndSensitivePermissionStepUpFailClosed() {
        var submitted = service.submit(actor(), request(UUID.randomUUID(), null, "2026001234Z"));

        assertThatThrownBy(() -> service.transition(platformActor(), new TransitionCommand(
            submitted.merchantId(), MerchantCommand.APPROVE, "PROFILE_VERIFIED", 0,
            UUID.randomUUID())))
            .isInstanceOf(MerchantException.PermissionDenied.class);
        assertThat(value("SELECT count(*) FROM merchant_audit_event", Long.class)).isEqualTo(1);

        MerchantActor stale = actor();
        dsl.execute("UPDATE iam_user SET identity_version=identity_version+1 WHERE id=?", USER);
        assertThatThrownBy(() -> service.findSelf(stale))
            .isInstanceOf(MerchantException.PermissionDenied.class);
        assertThatThrownBy(() -> service.submit(stale,
            request(UUID.randomUUID(), null, "2026009876Z")))
            .isInstanceOf(MerchantException.PermissionDenied.class);
        assertThat(value("SELECT count(*) FROM merchant_command_dedup", Long.class)).isEqualTo(1);
        assertThat(value("SELECT count(*) FROM merchant_audit_event", Long.class)).isEqualTo(1);
    }

    @Test
    void registrationWritesWaitForRotationFence() throws Exception {
        var lockHeld = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var fence = executor.submit(() -> dsl.transaction(configuration -> {
                configuration.dsl().fetchOne(
                    "SELECT pg_advisory_xact_lock(hashtext('mch-registration-key-rotation'))");
                lockHeld.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            assertThat(lockHeld.await(5, TimeUnit.SECONDS)).isTrue();
            var submit = executor.submit(() -> service.submit(actor(),
                request(UUID.randomUUID(), null, "2026001234Z")));
            assertThatThrownBy(() -> submit.get(250, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);
            release.countDown();
            fence.get(5, TimeUnit.SECONDS);
            assertThat(submit.get(5, TimeUnit.SECONDS).status())
                .isEqualTo(MerchantStatus.PENDING_REVIEW);
        } finally {
            release.countDown();
        }
    }

    @Test
    void onboardingCreateWaitsForLegalIdRotationFence() throws Exception {
        var documents = uploadDocumentSet(42);
        var lockHeld = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var fence = executor.submit(() -> dsl.transaction(configuration -> {
                configuration.dsl().fetchOne(
                    "SELECT pg_advisory_xact_lock(hashtext('mch-legal-id-key-rotation'))");
                lockHeld.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            assertThat(lockHeld.await(5, TimeUnit.SECONDS)).isTrue();
            var create = executor.submit(() -> onboardingService.create(platformStepUpActor(),
                new MerchantOnboardingModels.CreateRequest(TENANT, UUID.randomUUID(),
                    onboardingProfile(documents,
                        new MerchantOnboardingModels.SensitiveValue(
                            MerchantOnboardingModels.SensitiveMode.REPLACE, "LEGAL-FENCE-1"),
                        new MerchantOnboardingModels.SensitiveValue(
                            MerchantOnboardingModels.SensitiveMode.REPLACE, "REG-FENCE-1"),
                        "Legal Fence Merchant"))));
            assertThatThrownBy(() -> create.get(250, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);
            var revoke = executor.submit(() -> dsl.execute("""
                UPDATE iam_membership SET permission_version=permission_version+1
                 WHERE tenant_id=1 AND id=63003
                """));
            assertThatThrownBy(() -> revoke.get(250, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);
            release.countDown();
            fence.get(5, TimeUnit.SECONDS);
            assertThat(create.get(5, TimeUnit.SECONDS).status())
                .isEqualTo(MerchantStatus.PENDING_REVIEW);
            assertThat(revoke.get(5, TimeUnit.SECONDS)).isOne();
        } finally {
            release.countDown();
        }
    }

    @Test
    void documentUploadWaitsForDocumentRotationFence() throws Exception {
        var lockHeld = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var fence = executor.submit(() -> dsl.transaction(configuration -> {
                configuration.dsl().fetchOne(
                    "SELECT pg_advisory_xact_lock(hashtext('mch-document-key-rotation'))");
                lockHeld.countDown();
                try {
                    if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("test timeout");
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(exception);
                }
            }));
            assertThat(lockHeld.await(5, TimeUnit.SECONDS)).isTrue();
            var upload = executor.submit(() -> onboardingService.uploadDocument(platformStepUpActor(),
                new MerchantOnboardingModels.DocumentUploadRequest(TENANT,
                    MerchantOnboardingModels.DocumentKind.BRAND_LOGO, "image/png",
                    new byte[]{9, 42}, 1, 1)));
            assertThatThrownBy(() -> upload.get(250, TimeUnit.MILLISECONDS))
                .isInstanceOf(TimeoutException.class);
            release.countDown();
            fence.get(5, TimeUnit.SECONDS);
            assertThat(upload.get(5, TimeUnit.SECONDS).kind())
                .isEqualTo(MerchantOnboardingModels.DocumentKind.BRAND_LOGO);
        } finally {
            release.countDown();
        }
    }

    @Test
    void replacementAmendmentAndFullRotationSerializeWithoutRestoringRetiredKeys() throws Exception {
        var created = onboardingService.create(platformStepUpActor(),
            new MerchantOnboardingModels.CreateRequest(TENANT, UUID.randomUUID(),
                onboardingProfile(uploadDocumentSet(42),
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.REPLACE, "LEGAL-CONCURRENT-1"),
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.REPLACE, "REG-CONCURRENT-1"),
                    "Concurrent Base")));
        service.transition(platformReviewerStepUpActor(), new TransitionCommand(
            created.merchantId(), MerchantCommand.APPROVE, "PROFILE_VERIFIED", 0,
            UUID.randomUUID()));
        var documents = uploadDocumentSet(77);
        var start = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var amendment = executor.submit(() -> {
                start.await();
                return onboardingService.createAmendment(platformStepUpActor(),
                    new MerchantOnboardingModels.AmendmentRequest(created.merchantId(), 1,
                        UUID.randomUUID(), onboardingProfile(documents,
                            new MerchantOnboardingModels.SensitiveValue(
                                MerchantOnboardingModels.SensitiveMode.REPLACE,
                                "LEGAL-CONCURRENT-2"),
                            new MerchantOnboardingModels.SensitiveValue(
                                MerchantOnboardingModels.SensitiveMode.REPLACE,
                                "REG-CONCURRENT-2"),
                            "Concurrent Amendment")));
            });
            var rotate = executor.submit(() -> {
                start.await();
                return rotation.rotateAll("mch-registration-search-v2",
                    "mch-registration-aead-v2", "mch-legal-id-aead-v2",
                    "mch-document-aead-v2");
            });
            start.countDown();
            var pending = amendment.get(15, TimeUnit.SECONDS);
            rotate.get(15, TimeUnit.SECONDS);

            assertThat(pending.status()).isEqualTo("PENDING_REVIEW");
            assertThat(value("SELECT count(*) FROM merchant_amendment WHERE id="
                + pending.amendmentId() + " AND registration_aead_key_id="
                + "'mch-registration-aead-v2' AND legal_id_aead_key_id="
                + "'mch-legal-id-aead-v2'", Long.class)).isOne();
            assertThat(value("SELECT count(*) FROM merchant_document WHERE amendment_id="
                + pending.amendmentId() + " AND aead_key_id='mch-document-aead-v2'", Long.class))
                .isEqualTo(5);
        }
    }

    @Test
    void amendmentApprovalAndFullRotationSerializeWithoutRestoringRetiredKeys() throws Exception {
        var created = onboardingService.create(platformStepUpActor(),
            new MerchantOnboardingModels.CreateRequest(TENANT, UUID.randomUUID(),
                onboardingProfile(uploadDocumentSet(42),
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.REPLACE, "LEGAL-APPROVE-RACE-1"),
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.REPLACE, "REG-APPROVE-RACE-1"),
                    "Concurrent Approval Base")));
        service.transition(platformReviewerStepUpActor(), new TransitionCommand(
            created.merchantId(), MerchantCommand.APPROVE, "PROFILE_VERIFIED", 0,
            UUID.randomUUID()));
        var pending = onboardingService.createAmendment(platformStepUpActor(),
            new MerchantOnboardingModels.AmendmentRequest(created.merchantId(), 1,
                UUID.randomUUID(), onboardingProfile(uploadDocumentSet(77),
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.REPLACE,
                        "LEGAL-APPROVE-RACE-2"),
                    new MerchantOnboardingModels.SensitiveValue(
                        MerchantOnboardingModels.SensitiveMode.REPLACE,
                        "REG-APPROVE-RACE-2"),
                    "Concurrent Approval")));
        var start = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var approve = executor.submit(() -> {
                start.await();
                return onboardingService.reviewAmendment(platformReviewerStepUpActor(),
                    new MerchantOnboardingModels.AmendmentReviewRequest(created.merchantId(),
                        pending.amendmentId(), 0, UUID.randomUUID(),
                        MerchantOnboardingModels.AmendmentDecision.APPROVE,
                        "PROFILE_AMENDMENT_VERIFIED"));
            });
            var rotate = executor.submit(() -> {
                start.await();
                return rotation.rotateAll("mch-registration-search-v2",
                    "mch-registration-aead-v2", "mch-legal-id-aead-v2",
                    "mch-document-aead-v2");
            });
            start.countDown();
            assertThat(approve.get(15, TimeUnit.SECONDS).status()).isEqualTo("APPROVED");
            rotate.get(15, TimeUnit.SECONDS);

            assertThat(value("SELECT count(*) FROM merchant WHERE id=" + created.merchantId()
                + " AND registration_aead_key_id='mch-registration-aead-v2'"
                + " AND legal_id_aead_key_id='mch-legal-id-aead-v2'", Long.class)).isOne();
            assertThat(value("SELECT count(*) FROM merchant_document document JOIN "
                + "merchant_document_binding binding ON binding.document_id=document.id WHERE "
                + "binding.merchant_id=" + created.merchantId()
                + " AND document.aead_key_id='mch-document-aead-v2'", Long.class))
                .isEqualTo(5);
        }
    }

    @Test
    void onboardingCreateAndFullRotationSerializeToActiveKeys() throws Exception {
        var documents = uploadDocumentSet(42);
        var start = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var create = executor.submit(() -> {
                start.await();
                return onboardingService.create(platformStepUpActor(),
                    new MerchantOnboardingModels.CreateRequest(TENANT, UUID.randomUUID(),
                        onboardingProfile(documents,
                            new MerchantOnboardingModels.SensitiveValue(
                                MerchantOnboardingModels.SensitiveMode.REPLACE,
                                "LEGAL-CREATE-RACE-1"),
                            new MerchantOnboardingModels.SensitiveValue(
                                MerchantOnboardingModels.SensitiveMode.REPLACE,
                                "REG-CREATE-RACE-1"),
                            "Concurrent Create")));
            });
            var rotate = executor.submit(() -> {
                start.await();
                return rotation.rotateAll("mch-registration-search-v2",
                    "mch-registration-aead-v2", "mch-legal-id-aead-v2",
                    "mch-document-aead-v2");
            });
            start.countDown();
            var created = create.get(15, TimeUnit.SECONDS);
            rotate.get(15, TimeUnit.SECONDS);

            assertThat(created.status()).isEqualTo(MerchantStatus.PENDING_REVIEW);
            assertThat(value("SELECT count(*) FROM merchant WHERE id=" + created.merchantId()
                + " AND registration_search_key_id='mch-registration-search-v2'"
                + " AND registration_aead_key_id='mch-registration-aead-v2'"
                + " AND legal_id_aead_key_id='mch-legal-id-aead-v2'", Long.class)).isOne();
            assertThat(value("SELECT count(*) FROM merchant_document WHERE merchant_id="
                + created.merchantId() + " AND aead_key_id='mch-document-aead-v2'", Long.class))
                .isEqualTo(5);
        }
    }

    @Test
    void uploadCleanupAndFullRotationSerializeWithoutDeletingLiveEvidence() throws Exception {
        for (int marker = 1; marker <= 3; marker++) {
            var expired = onboardingService.uploadDocument(platformStepUpActor(),
                new MerchantOnboardingModels.DocumentUploadRequest(TENANT,
                    MerchantOnboardingModels.DocumentKind.BRAND_LOGO, "image/png",
                    new byte[]{9, (byte) marker}, 1, 1));
            dsl.execute("UPDATE merchant_document SET expires_at=statement_timestamp()-INTERVAL "
                + "'1 minute' WHERE id=?", expired.documentId());
        }
        var start = new CountDownLatch(1);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var upload = executor.submit(() -> {
                start.await();
                return onboardingService.uploadDocument(platformStepUpActor(),
                    new MerchantOnboardingModels.DocumentUploadRequest(TENANT,
                        MerchantOnboardingModels.DocumentKind.BUSINESS_LICENSE, "image/png",
                        new byte[]{9, 99}, 1, 1));
            });
            var cleanup = executor.submit(() -> {
                start.await();
                return documentMaintenance.deleteExpiredTemporaryDocuments(10);
            });
            var rotate = executor.submit(() -> {
                start.await();
                return rotation.rotateAll("mch-registration-search-v2",
                    "mch-registration-aead-v2", "mch-legal-id-aead-v2",
                    "mch-document-aead-v2");
            });
            start.countDown();
            var uploaded = upload.get(15, TimeUnit.SECONDS);
            assertThat(cleanup.get(15, TimeUnit.SECONDS)).isEqualTo(3);
            rotate.get(15, TimeUnit.SECONDS);

            assertThat(value("SELECT aead_key_id FROM merchant_document WHERE id="
                + uploaded.documentId(), String.class)).isEqualTo("mch-document-aead-v2");
            assertThat(value("SELECT count(*) FROM merchant_document", Long.class)).isOne();
            assertThat(value("SELECT count(*) FROM merchant_registration_key_metadata WHERE "
                + "key_purpose='DOCUMENT_AEAD' AND active AND key_id="
                + "'mch-document-aead-v2'", Long.class)).isOne();
        }
    }

    @Test
    void runtimePrincipalCannotForgeRotationModeOrInvokeOfflineRotation() {
        service.submit(actor(), request(UUID.randomUUID(), null, "2026001234Z"));

        Record isolation = dsl.fetchOne("""
            SELECT pg_has_role(current_user, 'payment_merchant_registration_rotation', 'MEMBER'),
                   pg_has_role(current_user, 'payment_merchant_registration_rotation', 'USAGE'),
                   pg_has_role(current_user, 'payment_merchant_registration_rotation', 'SET')
            """);
        assertThat(isolation.get(0, Boolean.class)).isFalse();
        assertThat(isolation.get(1, Boolean.class)).isFalse();
        assertThat(isolation.get(2, Boolean.class)).isFalse();
        Record ownership = dsl.fetchOne("""
            SELECT current_user,
                   (SELECT tableowner FROM pg_tables
                     WHERE schemaname='public' AND tablename='merchant'),
                   (SELECT owner.rolname FROM pg_proc routine
                     JOIN pg_roles owner ON owner.oid=routine.proowner
                    WHERE routine.oid='merchant_enforce_lifecycle()'::regprocedure),
                   (SELECT owner.rolname FROM pg_namespace namespace
                     JOIN pg_roles owner ON owner.oid=namespace.nspowner
                    WHERE namespace.nspname='public'),
                   has_table_privilege(current_user, 'merchant', 'TRIGGER'),
                   has_schema_privilege(current_user, 'public', 'CREATE'),
                   (SELECT rolcreaterole FROM pg_roles WHERE rolname=current_user)
            """);
        assertThat(ownership.get(0, String.class))
            .isNotEqualTo(ownership.get(1, String.class))
            .isNotEqualTo(ownership.get(2, String.class))
            .isNotEqualTo(ownership.get(3, String.class));
        assertThat(ownership.get(4, Boolean.class)).isFalse();
        assertThat(ownership.get(5, Boolean.class)).isFalse();
        assertThat(ownership.get(6, Boolean.class)).isFalse();

        dsl.execute("SELECT set_config('payment.merchant_registration_rotation','on',false)");
        assertThatThrownBy(() -> dsl.execute(
            "SET ROLE payment_merchant_registration_rotation"))
            .isInstanceOf(org.jooq.exception.DataAccessException.class);
        assertThatThrownBy(() -> dsl.execute("""
            UPDATE merchant SET registration_auth_tag=decode(repeat('00',16),'hex')
             WHERE id=(SELECT id FROM merchant)
            """))
            .isInstanceOf(org.jooq.exception.DataAccessException.class)
            .hasMessageContaining("row_version must advance exactly once");
        assertThatThrownBy(() -> dsl.execute("""
            INSERT INTO merchant_registration_key_metadata(
                key_purpose,key_id,algorithm,active,activated_at)
            VALUES ('REGISTRATION_AEAD','runtime-forged','AES-256-GCM',false,NULL)
            """))
            .isInstanceOf(org.jooq.exception.DataAccessException.class);
        assertThatThrownBy(() -> dsl.execute("""
            UPDATE merchant_registration_key_metadata SET active=false WHERE active
            """))
            .isInstanceOf(org.jooq.exception.DataAccessException.class);
        assertThatThrownBy(() -> dsl.execute(
            "ALTER TABLE merchant DISABLE TRIGGER trg_merchant_lifecycle"))
            .isInstanceOf(org.jooq.exception.DataAccessException.class);
        assertThatThrownBy(() -> new JooqMerchantRegistrationKeyRotation(
            dsl, new JdkMerchantCryptography(ThreePurposeKeyRing.fromBase64(
                Map.of("mch-registration-search-v2", key(12)),
                Map.of("mch-idempotency-v1", key(22)),
                Map.of("mch-registration-aead-v2", key(34)))))
            .rotate("mch-registration-search-v2", "mch-registration-aead-v2"))
            .isInstanceOf(MerchantException.ProtectedFieldUnavailable.class);
    }

    private void executeAsRotationOperator(String sql) {
        rotationDsl.transaction(configuration -> {
            DSLContext tx = configuration.dsl();
            tx.execute("SET LOCAL ROLE payment_merchant_registration_rotation");
            tx.execute(sql);
        });
        assertRotationRoleReset();
    }

    private void assertRotationRoleReset() {
        Record principal = rotationDsl.fetchOne("SELECT current_user,session_user");
        assertThat(principal.get(0, String.class)).isEqualTo("mch_rotation_ops");
        assertThat(principal.get(1, String.class)).isEqualTo("mch_rotation_ops");
    }

    private MerchantActor platformActor() {
        Record versions = dsl.fetchOne("""
            SELECT membership.permission_version,membership.session_version,user_row.identity_version
              FROM iam_membership membership JOIN iam_user user_row ON user_row.id=membership.user_id
             WHERE membership.tenant_id=1 AND membership.id=63003
            """);
        return MerchantActor.local(63002, 63003, 1, AccountDomain.PLATFORM,
            versions.get(0, Long.class), versions.get(1, Long.class), versions.get(2, Long.class));
    }

    private MerchantActor platformStepUpActor() {
        Record versions = dsl.fetchOne("""
            SELECT membership.permission_version,membership.session_version,user_row.identity_version
              FROM iam_membership membership JOIN iam_user user_row ON user_row.id=membership.user_id
             WHERE membership.tenant_id=1 AND membership.id=63003
            """);
        return MerchantActor.localStepUp(63002, 63003, 1, AccountDomain.PLATFORM,
            versions.get(0, Long.class), versions.get(1, Long.class), versions.get(2, Long.class));
    }

    private MerchantActor platformReviewerStepUpActor() {
        Record versions = dsl.fetchOne("""
            SELECT membership.permission_version,membership.session_version,user_row.identity_version
              FROM iam_membership membership JOIN iam_user user_row ON user_row.id=membership.user_id
             WHERE membership.tenant_id=1 AND membership.id=63013
            """);
        return MerchantActor.localStepUp(63012, 63013, 1, AccountDomain.PLATFORM,
            versions.get(0, Long.class), versions.get(1, Long.class), versions.get(2, Long.class));
    }

    private MerchantActor actor() {
        Record versions = dsl.fetchOne("""
            SELECT membership.permission_version,membership.session_version,user_row.identity_version
              FROM iam_membership membership JOIN iam_user user_row ON user_row.id=membership.user_id
             WHERE membership.tenant_id=? AND membership.id=?
            """, TENANT, MEMBERSHIP);
        return MerchantActor.local(USER, MEMBERSHIP, TENANT, AccountDomain.MERCHANT,
            versions.get(0, Long.class), versions.get(1, Long.class), versions.get(2, Long.class));
    }

    private static SubmissionRequest request(UUID key, Long version, String registrationNumber) {
        return new SubmissionRequest(key, version, "Example Legal", "Example Display", "SG",
            registrationNumber, true);
    }

    private Map<MerchantOnboardingModels.DocumentKind, Long> uploadDocumentSet() {
        return uploadDocumentSet(42);
    }

    private Map<MerchantOnboardingModels.DocumentKind, Long> uploadDocumentSet(int marker) {
        return uploadDocumentSet(TENANT, marker);
    }

    private Map<MerchantOnboardingModels.DocumentKind, Long> uploadDocumentSet(
        long targetTenantId, int marker
    ) {
        var result = new java.util.EnumMap<MerchantOnboardingModels.DocumentKind, Long>(
            MerchantOnboardingModels.DocumentKind.class);
        for (var kind : MerchantOnboardingModels.DocumentKind.values()) {
            byte[] content = new byte[]{(byte) (kind.ordinal() + 1), (byte) marker};
            var document = onboardingService.uploadDocument(platformStepUpActor(),
                new MerchantOnboardingModels.DocumentUploadRequest(
                    targetTenantId, kind, "image/png", content, 1, 1));
            result.put(kind, document.documentId());
        }
        return Map.copyOf(result);
    }

    private static MerchantOnboardingModels.Profile onboardingProfile(
        Map<MerchantOnboardingModels.DocumentKind, Long> documents,
        MerchantOnboardingModels.SensitiveValue legalId,
        MerchantOnboardingModels.SensitiveValue registration,
        String displayName
    ) {
        return new MerchantOnboardingModels.Profile(displayName, "Example Brand", "ENTERPRISE",
            "PLATFORM", "ECOMMERCE",
            documents.get(MerchantOnboardingModels.DocumentKind.BRAND_LOGO),
            "Example Legal", "BR", java.util.List.of("BRA"),
            "Registered Address", "Operating Address",
            documents.get(MerchantOnboardingModels.DocumentKind.BUSINESS_LICENSE),
            "Example Director", "merchant@example.test", "+5511999999998",
            "NATIONAL_ID", legalId,
            new MerchantOnboardingModels.LegalIdValidity(
                LocalDate.of(2020, 1, 1), LocalDate.of(2030, 1, 1)),
            documents.get(MerchantOnboardingModels.DocumentKind.LEGAL_ID_FRONT),
            documents.get(MerchantOnboardingModels.DocumentKind.LEGAL_ID_BACK),
            documents.get(MerchantOnboardingModels.DocumentKind.LEGAL_ID_HOLDING),
            "", registration);
    }

    private <T> T value(String sql, Class<T> type) {
        return dsl.fetchOne(sql).get(0, type);
    }

    private void seedMerchantActor() {
        dsl.execute("""
            INSERT INTO iam_tenant(id,tenant_code,tenant_name,tenant_type,status,account_domain)
            VALUES (62001,'mch-it','Merchant Integration','DIRECT_MERCHANT','ACTIVE','MERCHANT'),
                   (1,'platform-it','Platform Integration','PLATFORM','ACTIVE','PLATFORM');
            INSERT INTO iam_role(id,tenant_id,role_code,role_name,applicable_tenant_type,
                                 assignable,system_role,status)
            VALUES (62004,62001,'mch-it-admin','Merchant IT Admin','DIRECT_MERCHANT',false,true,'ACTIVE');
            INSERT INTO iam_role_grant(id,tenant_id,role_id,permission_id,grant_key,status)
            VALUES (62005,62001,62004,
              (SELECT id FROM iam_permission WHERE permission_code='backoffice:merchant-access'),
              'system-backoffice-access','ACTIVE');
            INSERT INTO iam_grant_dimension(id,grant_id,dimension_code,scope_mode)
            VALUES (62006,62005,'TENANT','TENANT_ALL');
            INSERT INTO iam_role(id,tenant_id,role_code,role_name,applicable_tenant_type,
                                 assignable,system_role,status)
            VALUES (63004,1,'platform-it-admin','Platform IT Admin','PLATFORM',false,true,'ACTIVE');
            INSERT INTO iam_role_grant(id,tenant_id,role_id,permission_id,grant_key,status)
            VALUES (63005,1,63004,
              (SELECT id FROM iam_permission WHERE permission_code='backoffice:platform-access'),
              'system-backoffice-access','ACTIVE');
            INSERT INTO iam_grant_dimension(id,grant_id,dimension_code,scope_mode)
            VALUES (63006,63005,'TENANT','TENANT_ALL');
            INSERT INTO iam_user(id,idp_issuer,idp_subject,display_name,status,account_domain,
                                 identity_version,idp_provisioning_status)
            VALUES (62002,'local://merchant','merchant-it','Merchant IT','ACTIVE','MERCHANT',0,'LOCAL_ONLY');
            INSERT INTO iam_authentication_credential(user_id,username,password_hash,status,
                                                      row_version,account_domain)
            VALUES (62002,'merchant-it@example.test',
              '$2a$12$6b5wzkJZwgLQzFj9RsykNO7V1JYjW/FZyP1jO6YkRZ2YDwRJhQxaq',
              'ACTIVE',0,'MERCHANT');
            INSERT INTO iam_membership(id,tenant_id,user_id,status,account_domain,
                                       permission_version,session_version)
            VALUES (62003,62001,62002,'ACTIVE','MERCHANT',0,0);
            INSERT INTO iam_membership_role(tenant_id,membership_id,role_id)
            VALUES (62001,62003,62004);
            INSERT INTO iam_user(id,idp_issuer,idp_subject,display_name,status,account_domain,
                                 identity_version,idp_provisioning_status)
            VALUES (63002,'local://platform','platform-it','Platform IT','ACTIVE','PLATFORM',0,'LOCAL_ONLY');
            INSERT INTO iam_authentication_credential(user_id,username,password_hash,status,
                                                      row_version,account_domain)
            VALUES (63002,'platform-it@example.test',
              '$2a$12$6b5wzkJZwgLQzFj9RsykNO7V1JYjW/FZyP1jO6YkRZ2YDwRJhQxaq',
              'ACTIVE',0,'PLATFORM');
            INSERT INTO iam_membership(id,tenant_id,user_id,status,account_domain,
                                       permission_version,session_version)
            VALUES (63003,1,63002,'ACTIVE','PLATFORM',0,0);
            INSERT INTO iam_membership_role(tenant_id,membership_id,role_id)
            VALUES (1,63003,63004);
            INSERT INTO iam_user(id,idp_issuer,idp_subject,display_name,status,account_domain,
                                 identity_version,idp_provisioning_status)
            VALUES (63012,'local://platform','platform-reviewer-it','Platform Reviewer IT',
                    'ACTIVE','PLATFORM',0,'LOCAL_ONLY');
            INSERT INTO iam_authentication_credential(user_id,username,password_hash,status,
                                                      row_version,account_domain)
            VALUES (63012,'platform-reviewer-it@example.test',
              '$2a$12$6b5wzkJZwgLQzFj9RsykNO7V1JYjW/FZyP1jO6YkRZ2YDwRJhQxaq',
              'ACTIVE',0,'PLATFORM');
            INSERT INTO iam_membership(id,tenant_id,user_id,status,account_domain,
                                       permission_version,session_version)
            VALUES (63013,1,63012,'ACTIVE','PLATFORM',0,0);
            INSERT INTO iam_membership_role(tenant_id,membership_id,role_id)
            VALUES (1,63013,63004)
            """);
    }

    private static void seedSecondEligibleTenant(DSLContext migrationDsl) {
        migrationDsl.execute("""
            INSERT INTO iam_tenant(id,tenant_code,tenant_name,tenant_type,status,account_domain)
            VALUES (62011,'mch-it-2','Merchant Integration 2','DIRECT_MERCHANT','ACTIVE','MERCHANT')
            """);
    }

    private static PGSimpleDataSource dataSource(String username, String password) {
        var result = new PGSimpleDataSource();
        result.setURL(POSTGRES.getJdbcUrl());
        result.setUser(username);
        result.setPassword(password);
        return result;
    }

    private static void createSeparatedDatabasePrincipals(DSLContext migrationDsl) {
        migrationDsl.execute("""
            CREATE ROLE mch_runtime_app LOGIN PASSWORD 'mch-runtime-test'
              NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
            CREATE ROLE mch_rotation_ops LOGIN PASSWORD 'mch-rotation-test'
              NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION NOBYPASSRLS;
            GRANT USAGE ON SCHEMA public TO mch_runtime_app;
            GRANT SELECT, INSERT, UPDATE, DELETE ON ALL TABLES IN SCHEMA public TO mch_runtime_app;
            REVOKE INSERT, UPDATE, DELETE, TRUNCATE, REFERENCES, TRIGGER
              ON merchant_registration_key_metadata FROM mch_runtime_app;
            GRANT USAGE, SELECT ON ALL SEQUENCES IN SCHEMA public TO mch_runtime_app;
            GRANT payment_merchant_registration_rotation TO mch_rotation_ops
              WITH ADMIN FALSE, INHERIT FALSE, SET TRUE;
            """);
        Record membership = migrationDsl.fetchOne("""
            SELECT member.admin_option, member.inherit_option, member.set_option
              FROM pg_auth_members member
              JOIN pg_roles capability ON capability.oid=member.roleid
              JOIN pg_roles operator ON operator.oid=member.member
             WHERE capability.rolname='payment_merchant_registration_rotation'
               AND operator.rolname='mch_rotation_ops'
            """);
        assertThat(membership).isNotNull();
        assertThat(membership.get(0, Boolean.class)).isFalse();
        assertThat(membership.get(1, Boolean.class)).isFalse();
        assertThat(membership.get(2, Boolean.class)).isTrue();
    }

    private static void resetTestDatabaseRoles() {
        DSLContext admin = DSL.using(dataSource(
            POSTGRES.getUsername(), POSTGRES.getPassword()), SQLDialect.POSTGRES);
        admin.execute("""
            DO $reset$
            BEGIN
              IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='mch_rotation_ops') THEN
                REVOKE payment_merchant_registration_rotation FROM mch_rotation_ops;
                EXECUTE 'DROP OWNED BY mch_rotation_ops';
                DROP ROLE mch_rotation_ops;
              END IF;
              IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='mch_runtime_app') THEN
                EXECUTE 'DROP OWNED BY mch_runtime_app';
                DROP ROLE mch_runtime_app;
              END IF;
            END
            $reset$
            """);
    }

    private static String key(int value) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, (byte) value);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private static Flyway flyway(String target) {
        var configuration = Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations(
                filesystem(BACKEND_ROOT.resolve("modules/identity/persistence-postgres/src/main/resources/db/migration")),
                filesystem(BACKEND_ROOT.resolve("modules/system-dictionary/src/main/resources/db/migration")),
                filesystem(BACKEND_ROOT.resolve("modules/merchant/persistence-postgres/src/main/resources/db/migration")))
            .cleanDisabled(false);
        configuration.getConfigurationExtension(PostgreSQLConfigurationExtension.class)
            .setTransactionalLock(false);
        if (target != null) configuration.target(target);
        return configuration.load();
    }

    private static String filesystem(Path path) {
        return "filesystem:" + path.toAbsolutePath().normalize();
    }

    private static Path locateBackendRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            if (Files.isDirectory(current.resolve("modules/identity/persistence-postgres"))) return current;
            if (Files.isDirectory(current.resolve("backend/modules/identity/persistence-postgres"))) {
                return current.resolve("backend");
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate backend root");
    }
}
