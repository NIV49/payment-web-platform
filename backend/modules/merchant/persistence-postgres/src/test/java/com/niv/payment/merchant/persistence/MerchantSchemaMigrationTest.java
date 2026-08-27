package com.niv.payment.merchant.persistence;

import org.flywaydb.core.Flyway;
import org.flywaydb.database.postgresql.PostgreSQLConfigurationExtension;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.HexFormat;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.zip.CRC32;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class MerchantSchemaMigrationTest {
    private static final Path BACKEND_ROOT = locateBackendRoot();
    private static final Path V34_BRIDGE_CALLBACK = BACKEND_ROOT.resolve(
        "modules/merchant/persistence-postgres/src/main/resources/db/migration/"
            + "beforeEachMigrate__prepare_v34_status_reason_backfill.sql");
    private static final Path V34_AUDIT_PREFLIGHT_CALLBACK = BACKEND_ROOT.resolve(
        "modules/merchant/persistence-postgres/src/main/resources/db/migration/"
            + "beforeEachMigrate__reject_ambiguous_v34_status_reason_evidence.sql");
    private static final Path V37_MIGRATION = BACKEND_ROOT.resolve(
        "modules/merchant/persistence-postgres/src/main/resources/db/migration/"
            + "V37__add_platform_onboarding_amendments_and_documents.sql");
    private static final Path V37_BRIDGE_CALLBACK = BACKEND_ROOT.resolve(
        "modules/merchant/persistence-postgres/src/main/resources/db/migration/"
            + "beforeEachMigrate__prepare_v37_direct_canonicalization.sql");
    private static final Path V37_AUTHOR_BRIDGE_CALLBACK = BACKEND_ROOT.resolve(
        "modules/merchant/persistence-postgres/src/main/resources/db/migration/"
            + "beforeEachMigrate__prepare_v37_submit_author_backfill.sql");
    private static final Path V38_MIGRATION = BACKEND_ROOT.resolve(
        "modules/merchant/persistence-postgres/src/main/resources/db/migration/"
            + "V38__close_mch003_onboarding_security_boundaries.sql");
    private static final Path V39_MIGRATION = BACKEND_ROOT.resolve(
        "modules/merchant/persistence-postgres/src/main/resources/db/migration/"
            + "V39__allow_guarded_retained_amendment_rotation.sql");
    private static final Path V40_MIGRATION = BACKEND_ROOT.resolve(
        "modules/merchant/persistence-postgres/src/main/resources/db/migration/"
            + "V40__canonicalize_merchant_button_i18n_titles.sql");
    private static final Path V40_PREFLIGHT_CALLBACK = BACKEND_ROOT.resolve(
        "modules/merchant/persistence-postgres/src/main/resources/db/migration/"
            + "beforeEachMigrate__lock_v40_merchant_button_i18n.sql");
    private static final Path V41_MIGRATION = BACKEND_ROOT.resolve(
        "modules/merchant/persistence-postgres/src/main/resources/db/migration/"
            + "V41__verify_canonical_merchant_button_i18n_titles.sql");
    private static final Path V42_MIGRATION = BACKEND_ROOT.resolve(
        "modules/merchant/persistence-postgres/src/main/resources/db/migration/"
            + "V42__add_merchant_amendment_document_references.sql");
    private static final Path V43_MIGRATION = BACKEND_ROOT.resolve(
        "modules/merchant/persistence-postgres/src/main/resources/db/migration/"
            + "V43__guard_merchant_amendment_document_references.sql");

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse(
        "postgres:18.4-alpine@sha256:9a8afca54e7861fd90fab5fdf4c42477a6b1cb7d293595148e674e0a3181de15")
        .asCompatibleSubstituteFor("postgres"))
        .withDatabaseName("payment_platform")
        .withUsername("payment_dev")
        .withPassword("payment_dev");

    @BeforeEach
    void cleanDatabase() {
        executeUnchecked("""
            DO $reset$
            BEGIN
              IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname='mch_hostile_runtime') THEN
                IF EXISTS (SELECT 1 FROM pg_roles
                            WHERE rolname='payment_merchant_registration_rotation') THEN
                  REVOKE payment_merchant_registration_rotation FROM mch_hostile_runtime;
                END IF;
                EXECUTE 'DROP OWNED BY mch_hostile_runtime';
                DROP ROLE mch_hostile_runtime;
              END IF;
            END
            $reset$
            """);
        flyway(null).clean();
    }

    @Test
    void freshMigrationCreatesTheCompleteMerchantPersistenceBoundary() throws Exception {
        flyway(null).migrate();

        assertThat(currentSuccessfulVersion()).isEqualTo("43");
        assertThat(strings("""
            SELECT table_name
              FROM information_schema.tables
             WHERE table_schema = 'public'
               AND table_name IN (
                   'merchant', 'merchant_command_dedup', 'merchant_audit_event',
                   'merchant_registration_key_metadata', 'merchant_operating_market',
                   'merchant_document','merchant_document_binding','merchant_amendment',
                   'merchant_amendment_market','merchant_amendment_command_dedup',
                   'merchant_amendment_audit_event','merchant_amendment_document',
                   'merchant_protected_nonce')
             ORDER BY table_name
            """)).containsExactly(
                "merchant",
                "merchant_amendment",
                "merchant_amendment_audit_event",
                "merchant_amendment_command_dedup",
                "merchant_amendment_document",
                "merchant_amendment_market",
                "merchant_audit_event",
                "merchant_command_dedup",
                "merchant_document",
                "merchant_document_binding",
                "merchant_operating_market",
                "merchant_protected_nonce",
                "merchant_registration_key_metadata");

        assertThat(singleLong("""
            SELECT count(*) FROM iam_permission
             WHERE permission_code IN (
                 'merchant:self-view', 'merchant:submit', 'merchant:resubmit',
                 'merchant:view', 'merchant:review', 'merchant:disable',
                 'merchant:enable', 'merchant:update', 'merchant:terminate',
                 'merchant:create','merchant:amend','merchant:document:upload',
                 'merchant:document:view')
               AND status = 'ACTIVE'
               AND cross_tenant_mode = 'SAME_TENANT_ONLY'
            """)).isEqualTo(13L);
        assertThat(singleLong("""
            SELECT count(*) FROM iam_permission
             WHERE permission_code IN ('merchant:create','merchant:amend',
                   'merchant:document:upload','merchant:document:view')
               AND requires_step_up AND NOT requires_approval
               AND risk_level='SENSITIVE' AND required_dimensions=ARRAY['TENANT']::varchar(32)[]
            """)).isEqualTo(4L);
        assertThat(singleLong("""
            SELECT count(*) FROM pg_constraint
             WHERE conname IN (
                 'fk_merchant_tenant_domain', 'ck_merchant_account_domain',
                 'ck_merchant_status', 'ck_merchant_row_version',
                 'uk_merchant_tenant', 'uk_merchant_code',
                 'uk_merchant_registration_fingerprint',
                 'uk_merchant_registration_aead_nonce',
                 'uk_merchant_audit_merchant_version')
            """)).isEqualTo(9L);
        assertThat(singleLong("""
            SELECT count(*) FROM information_schema.columns
             WHERE table_schema = 'public'
               AND table_name IN ('merchant_audit_event', 'merchant_command_dedup')
               AND (column_name LIKE '%cipher%'
                    OR column_name LIKE '%nonce%'
                    OR column_name LIKE '%fingerprint%'
                    OR column_name LIKE '%plaintext%'
                    OR column_name LIKE '%payload%')
            """)).isZero();
        assertThat(singleLong("""
            SELECT count(*) FROM merchant_registration_key_metadata
             WHERE active AND (key_purpose, algorithm) IN (
                 ('REGISTRATION_SEARCH_HMAC', 'HMAC-SHA-256'),
                 ('REGISTRATION_AEAD', 'AES-256-GCM'),
                 ('LEGAL_ID_AEAD', 'AES-256-GCM'),
                 ('DOCUMENT_AEAD', 'AES-256-GCM'))
            """)).isEqualTo(4L);
        assertThat(singleLong("""
            SELECT count(*) FROM pg_roles
             WHERE rolname='payment_merchant_registration_rotation'
               AND NOT rolcanlogin AND NOT rolsuper AND NOT rolcreaterole
               AND NOT rolcreatedb AND NOT rolreplication AND NOT rolbypassrls
            """)).isEqualTo(1L);
        assertThat(strings("""
            SELECT column_name FROM information_schema.columns
             WHERE table_schema='public' AND table_name='merchant'
               AND column_name IN (
                   'merchant_type_code','legal_person_name','authentication_type')
             ORDER BY column_name
            """)).containsExactly(
                "authentication_type", "legal_person_name", "merchant_type_code");
        assertThat(strings("""
            SELECT type_row.dict_type || ':' || data_row.value || ':' || data_row.label
              FROM sys_dictionary_type type_row
              JOIN sys_dictionary_data data_row
                ON data_row.dictionary_type_id=type_row.id
             WHERE type_row.dict_type IN ('MERCHANT_TYPE_CODE','MERCHANT_AUTH_TYPE')
               AND data_row.deleted_at IS NULL
             ORDER BY type_row.dict_type,data_row.sort_order
            """)).containsExactly(
                "MERCHANT_AUTH_TYPE:ENTERPRISE:企业",
                "MERCHANT_AUTH_TYPE:NON_PROFIT_ORGANIZATIONS:非盈利组织",
                "MERCHANT_AUTH_TYPE:CLIQUE:集团",
                "MERCHANT_AUTH_TYPE:INDIVIDUAL:个人",
                "MERCHANT_AUTH_TYPE:INDIVIDUAL_HOUSEHOLD:个体户",
                "MERCHANT_TYPE_CODE:PLATFORM:平台商户",
                "MERCHANT_TYPE_CODE:INDIRECT:间连商户",
                "MERCHANT_TYPE_CODE:COMMISSION:分佣商户",
                "MERCHANT_TYPE_CODE:SALES:销售商户");
        assertThat(singleLong(
            "SELECT revision FROM sys_dictionary_catalog_revision WHERE singleton_id=1"))
            .isEqualTo(4L);
        assertThat(singleLong("""
            SELECT count(*)
              FROM pg_proc routine
              JOIN pg_roles owner ON owner.oid=routine.proowner
             WHERE routine.oid='merchant_enforce_lifecycle()'::regprocedure
               AND owner.rolname <> 'payment_merchant_registration_rotation'
            """)).isEqualTo(1L);
        assertThat(singleLong("""
            SELECT count(*) FROM pg_trigger
             WHERE tgrelid='merchant_amendment'::regclass
               AND tgname='trg_merchant_amendment_rotation_guard'
               AND NOT tgisinternal
            """)).isEqualTo(1L);
        assertThat(singleLong("""
            SELECT count(*) FROM information_schema.table_privileges
             WHERE table_schema='public' AND table_name='merchant_amendment'
               AND grantee='payment_merchant_registration_rotation'
               AND privilege_type='SELECT'
            """)).isEqualTo(1L);
        assertThat(strings("""
            SELECT column_name FROM information_schema.column_privileges
             WHERE table_schema='public' AND table_name='merchant_amendment'
               AND grantee='payment_merchant_registration_rotation'
               AND privilege_type='UPDATE'
             ORDER BY column_name
            """)).containsExactly(
                "legal_id_aad_scheme_version", "legal_id_aead_algorithm",
                "legal_id_aead_key_id", "legal_id_auth_tag", "legal_id_ciphertext",
                "legal_id_nonce", "legal_id_protection_version",
                "registration_aead_algorithm", "registration_aead_key_id",
                "registration_auth_tag", "registration_ciphertext",
                "registration_fingerprint", "registration_fingerprint_algorithm",
                "registration_nonce", "registration_search_key_id");
    }

    @Test
    void v37IsFrozenAtItsFirstSuccessfullyExecutedContent() throws Exception {
        byte[] bytes = Files.readAllBytes(V37_MIGRATION);
        assertThat(bytes).hasSize(30_457);
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
            .isEqualTo("3dcb6254b25fd911fb9c81d363ae4162560412db4e6848d6ca0eb17829a3623c");
        CRC32 checksum = new CRC32();
        for (String line : Files.readAllLines(V37_MIGRATION, StandardCharsets.UTF_8)) {
            checksum.update(line.getBytes(StandardCharsets.UTF_8));
        }
        assertThat((int) checksum.getValue()).isEqualTo(1_254_522_631);
    }

    @Test
    void v38IsFrozenAtItsFirstSuccessfullyExecutedContent() throws Exception {
        byte[] bytes = Files.readAllBytes(V38_MIGRATION);
        assertThat(bytes).hasSize(25_325);
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
            .isEqualTo("07517085d82a555be47fad3c40f8bfea80414eef16d05610000932009a0ff3cc");
        CRC32 checksum = new CRC32();
        for (String line : Files.readAllLines(V38_MIGRATION, StandardCharsets.UTF_8)) {
            checksum.update(line.getBytes(StandardCharsets.UTF_8));
        }
        assertThat((int) checksum.getValue()).isEqualTo(1_630_466_861);
    }

    @Test
    void v39IsFrozenAtItsFirstSuccessfullyExecutedContent() throws Exception {
        byte[] bytes = Files.readAllBytes(V39_MIGRATION);
        assertThat(bytes).hasSize(10_658);
        assertThat(Files.readAllLines(V39_MIGRATION, StandardCharsets.UTF_8)).hasSize(189);
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
            .isEqualTo("c4d65c4811aae9a7146c9f686fb3000f2801caa651314bf4d5c57f0c49fdd8db");
        CRC32 checksum = new CRC32();
        for (String line : Files.readAllLines(V39_MIGRATION, StandardCharsets.UTF_8)) {
            checksum.update(line.getBytes(StandardCharsets.UTF_8));
        }
        assertThat((int) checksum.getValue()).isEqualTo(1_595_687_494);
    }

    @Test
    void v40IsFrozenAtItsFirstSuccessfullyExecutedContent() throws Exception {
        byte[] bytes = Files.readAllBytes(V40_MIGRATION);
        assertThat(bytes).hasSize(5_084);
        assertThat(Files.readAllLines(V40_MIGRATION, StandardCharsets.UTF_8)).hasSize(114);
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
            .isEqualTo("3e46db0f7b10dd5a5715764f429a0538035da8639cb06c9dae2a317018dab0ac");
        CRC32 checksum = new CRC32();
        for (String line : Files.readAllLines(V40_MIGRATION, StandardCharsets.UTF_8)) {
            checksum.update(line.getBytes(StandardCharsets.UTF_8));
        }
        assertThat((int) checksum.getValue()).isEqualTo(-1_711_800_357);
    }

    @Test
    void v40PreflightCallbackIsFrozenAtItsFirstSuccessfullyExecutedContent() throws Exception {
        byte[] bytes = Files.readAllBytes(V40_PREFLIGHT_CALLBACK);
        assertThat(bytes).hasSize(4_487);
        assertThat(Files.readAllLines(V40_PREFLIGHT_CALLBACK, StandardCharsets.UTF_8))
            .hasSize(106);
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
            .isEqualTo("f8e367674f30cf16ccfd46ccb964fc5143409377aa368beb65c87403736a6b20");
        CRC32 checksum = new CRC32();
        for (String line : Files.readAllLines(V40_PREFLIGHT_CALLBACK, StandardCharsets.UTF_8)) {
            checksum.update(line.getBytes(StandardCharsets.UTF_8));
        }
        assertThat((int) checksum.getValue()).isEqualTo(-1_314_979_842);
    }

    @Test
    void v41IsFrozenAtItsFirstSuccessfullyExecutedContent() throws Exception {
        byte[] bytes = Files.readAllBytes(V41_MIGRATION);
        assertThat(bytes).hasSize(4_067);
        assertThat(Files.readAllLines(V41_MIGRATION, StandardCharsets.UTF_8)).hasSize(91);
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
            .isEqualTo("d493479ef0b4043f80177a30b22f595abf9e7b3c773c41911d424fd24f32a48e");
        CRC32 checksum = new CRC32();
        for (String line : Files.readAllLines(V41_MIGRATION, StandardCharsets.UTF_8)) {
            checksum.update(line.getBytes(StandardCharsets.UTF_8));
        }
        assertThat((int) checksum.getValue()).isEqualTo(-444_986_840);
    }

    @Test
    void v42IsFrozenAtItsFirstSuccessfullyExecutedContent() throws Exception {
        byte[] bytes = Files.readAllBytes(V42_MIGRATION);
        assertThat(bytes).hasSize(2_447);
        assertThat(Files.readAllLines(V42_MIGRATION, StandardCharsets.UTF_8)).hasSize(63);
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
            .isEqualTo("b3ae42f24ebee2d5667a824b5aa1f1ff5b4a9ab857b3d0015ae1d37528cdc506");
        CRC32 checksum = new CRC32();
        for (String line : Files.readAllLines(V42_MIGRATION, StandardCharsets.UTF_8)) {
            checksum.update(line.getBytes(StandardCharsets.UTF_8));
        }
        assertThat((int) checksum.getValue()).isEqualTo(-897_909_760);
    }

    @Test
    void v43IsFrozenAtItsFirstSuccessfullyExecutedContent() throws Exception {
        byte[] bytes = Files.readAllBytes(V43_MIGRATION);
        assertThat(bytes).hasSize(5_054);
        assertThat(Files.readAllLines(V43_MIGRATION, StandardCharsets.UTF_8)).hasSize(112);
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)))
            .isEqualTo("c4982658f6f49dfe2eb9061c775cb8b78053bf5d86bdf8143b29d0c237856eb9");
        CRC32 checksum = new CRC32();
        for (String line : Files.readAllLines(V43_MIGRATION, StandardCharsets.UTF_8)) {
            checksum.update(line.getBytes(StandardCharsets.UTF_8));
        }
        assertThat((int) checksum.getValue()).isEqualTo(221_360_846);
    }

    @Test
    void existingV38SchemaUpgradesThroughFrozenV39V40V41V42AndV43Migrations() throws Exception {
        flyway("38").migrate();
        assertThat(currentSuccessfulVersion()).isEqualTo("38");

        flyway(null).migrate();

        assertThat(currentSuccessfulVersion()).isEqualTo("43");
        assertThat(singleLong("""
            SELECT count(*) FROM flyway_schema_history
             WHERE version IN ('37','38','39','40','41','42','43') AND success
            """)).isEqualTo(7L);
        assertThat(singleLong("""
            SELECT checksum FROM flyway_schema_history WHERE version='39' AND success
            """)).isEqualTo(1_595_687_494L);
        assertThat(singleLong("""
            SELECT checksum FROM flyway_schema_history WHERE version='40' AND success
            """)).isEqualTo(-1_711_800_357L);
        assertThat(singleLong("""
            SELECT checksum FROM flyway_schema_history WHERE version='41' AND success
            """)).isEqualTo(-444_986_840L);
        assertThat(singleLong("""
            SELECT checksum FROM flyway_schema_history WHERE version='42' AND success
            """)).isEqualTo(-897_909_760L);
        assertThat(singleLong("""
            SELECT checksum FROM flyway_schema_history WHERE version='43' AND success
            """)).isEqualTo(221_360_846L);
    }

    @Test
    void existingV39MerchantButtonsUpgradeToCanonicalI18nKeys() throws Exception {
        flyway("39").migrate();
        insertV39PlatformMenuFixture();
        assertThat(strings(v40ButtonTitles())).containsExactly(
            "MerchantCreate:merchant.create",
            "MerchantDisable:merchant.disable",
            "MerchantEdit:merchant.edit",
            "MerchantEnable:merchant.enable",
            "MerchantReview:merchant.review",
            "MerchantTerminate:merchant.terminate");

        flyway(null).migrate();

        assertThat(currentSuccessfulVersion()).isEqualTo("43");
        assertThat(strings(v40ButtonTitles())).containsExactly(
            "MerchantCreate:merchant.permission.create",
            "MerchantDisable:merchant.permission.disable",
            "MerchantEdit:merchant.permission.edit",
            "MerchantEnable:merchant.permission.enable",
            "MerchantReview:merchant.permission.review",
            "MerchantTerminate:merchant.permission.terminate");
    }

    @Test
    void v40RejectsModifiedMerchantButtonWithoutPartialTitleUpdates() throws Exception {
        flyway("39").migrate();
        insertV39PlatformMenuFixture();
        execute("""
            UPDATE iam_menu
               SET meta_json='{"title":"hostile.review"}'::jsonb
             WHERE tenant_id=40001 AND route_name='MerchantReview'
            """);

        assertThatThrownBy(() -> flyway(null).migrate())
            .isInstanceOf(org.flywaydb.core.api.FlywayException.class)
            .hasMessageContaining("V40 preflight blocked: Merchant button menu is absent or modified");

        assertThat(currentSuccessfulVersion()).isEqualTo("39");
        assertThat(strings(v40ButtonTitles())).containsExactly(
            "MerchantCreate:merchant.create",
            "MerchantDisable:merchant.disable",
            "MerchantEdit:merchant.edit",
            "MerchantEnable:merchant.enable",
            "MerchantReview:hostile.review",
            "MerchantTerminate:merchant.terminate");
    }

    @Test
    void v40PreflightLockRemainsHeldWhileV40UpdatesAndBlocksConcurrentMenuWriter()
        throws Exception {
        flyway("39").migrate();
        insertV39PlatformMenuFixture();
        execute("""
            CREATE FUNCTION test_pause_v40_merchant_title_update()
            RETURNS trigger LANGUAGE plpgsql AS $body$
            BEGIN
              IF NEW.menu_type='BUTTON'
                 AND NEW.meta_json->>'title' LIKE 'merchant.permission.%' THEN
                PERFORM pg_advisory_xact_lock(400040);
              END IF;
              RETURN NEW;
            END
            $body$;
            CREATE TRIGGER test_pause_v40_merchant_title_update
              BEFORE UPDATE ON iam_menu
              FOR EACH ROW EXECUTE FUNCTION test_pause_v40_merchant_title_update();
            """);

        var executor = Executors.newSingleThreadExecutor();
        try (Connection migrationGate = connection();
             Statement gateStatement = migrationGate.createStatement()) {
            gateStatement.execute("SELECT pg_advisory_lock(400040)");
            var migration = executor.submit(() -> flyway("40").migrate());
            try {
                assertEventuallyEquals(1L, """
                    SELECT count(*)
                      FROM pg_locks table_lock
                      JOIN pg_class relation ON relation.oid=table_lock.relation
                     WHERE relation.relname='iam_menu'
                       AND table_lock.mode='ShareRowExclusiveLock'
                       AND table_lock.granted
                       AND EXISTS (
                           SELECT 1 FROM pg_locks advisory_lock
                            WHERE advisory_lock.pid=table_lock.pid
                              AND advisory_lock.locktype='advisory'
                              AND NOT advisory_lock.granted)
                    """, Duration.ofSeconds(10));

                assertThatThrownBy(() -> execute("""
                    SET lock_timeout='250ms';
                    UPDATE iam_menu SET updated_at=updated_at WHERE id=40003;
                    """))
                    .isInstanceOf(SQLException.class)
                    .hasMessageContaining("lock timeout");
            } finally {
                gateStatement.execute("SELECT pg_advisory_unlock(400040)");
            }
            migration.get(10, TimeUnit.SECONDS);
        } finally {
            executor.shutdownNow();
        }

        assertThat(currentSuccessfulVersion()).isEqualTo("40");
        assertThat(strings(v40ButtonTitles())).allMatch(
            title -> title.contains(":merchant.permission."));
    }

    @Test
    void v41WaitsForConcurrentWriterThenRejectsPostV40MenuDrift() throws Exception {
        flyway("40").migrate();
        insertV39PlatformMenuFixture();
        execute("""
            UPDATE iam_menu
               SET meta_json=jsonb_build_object(
                   'title', 'merchant.permission.' || lower(replace(route_name,'Merchant','')))
             WHERE tenant_id=40001 AND menu_type='BUTTON';
            """);

        var executor = Executors.newSingleThreadExecutor();
        try (Connection writer = connection(); Statement writerStatement = writer.createStatement()) {
            writer.setAutoCommit(false);
            writerStatement.execute("""
                UPDATE iam_menu
                   SET auth_code='merchant:hostile'
                 WHERE tenant_id=40001 AND route_name='MerchantReview'
                """);
            var migration = executor.submit(() -> flyway(null).migrate());
            try {
                assertEventuallyEquals(1L, """
                    SELECT count(*)
                      FROM pg_locks table_lock
                      JOIN pg_class relation ON relation.oid=table_lock.relation
                     WHERE relation.relname='iam_menu'
                       AND table_lock.mode='ShareRowExclusiveLock'
                       AND NOT table_lock.granted
                    """, Duration.ofSeconds(10));
            } finally {
                writer.commit();
            }

            assertThatThrownBy(() -> migration.get(10, TimeUnit.SECONDS))
                .hasStackTraceContaining("V41 blocked: Merchant button menu is absent or modified");
        } finally {
            executor.shutdownNow();
        }

        assertThat(currentSuccessfulVersion()).isEqualTo("40");
        assertThat(strings("""
            SELECT auth_code FROM iam_menu
             WHERE tenant_id=40001 AND route_name='MerchantReview'
            """)).containsExactly("merchant:hostile");
    }

    @Test
    void v42RejectsAPreexistingAmendmentDocumentReferenceTable() throws Exception {
        flyway("41").migrate();
        execute("CREATE TABLE merchant_amendment_document(id BIGINT PRIMARY KEY)");

        assertThatThrownBy(() -> flyway(null).migrate())
            .isInstanceOf(org.flywaydb.core.api.FlywayException.class)
            .hasMessageContaining("V42 blocked: merchant_amendment_document already exists");

        assertThat(currentSuccessfulVersion()).isEqualTo("41");
        assertThat(strings("""
            SELECT column_name FROM information_schema.columns
             WHERE table_schema='public' AND table_name='merchant_amendment_document'
            """)).containsExactly("id");
    }

    @Test
    void v41HistoricalPendingAmendmentDocumentsUpgradeAndRemainReviewable() throws Exception {
        flyway("41").migrate();
        insertV41HistoricalAmendmentEvidence(5);

        flyway(null).migrate();

        assertThat(currentSuccessfulVersion()).isEqualTo("43");
        assertThat(strings("""
            SELECT reference.kind || ':' || reference.document_id || ':'
                   || reference.document_mode
              FROM merchant_amendment_document reference
             WHERE reference.amendment_id=34220
             ORDER BY reference.kind
            """)).containsExactly(
                "BRAND_LOGO:34230:REPLACE",
                "BUSINESS_LICENSE:34231:REPLACE",
                "LEGAL_ID_BACK:34233:REPLACE",
                "LEGAL_ID_FRONT:34232:REPLACE",
                "LEGAL_ID_HOLDING:34234:REPLACE");
        assertThat(singleLong("""
            SELECT count(*)
              FROM merchant_amendment amendment
              JOIN merchant_amendment_document reference
                ON reference.amendment_id=amendment.id
              JOIN merchant_document document ON document.id=reference.document_id
             WHERE amendment.id=34220 AND amendment.status='PENDING_REVIEW'
               AND document.amendment_id=amendment.id
               AND document.attachment_scope='AMENDMENT'
            """)).isEqualTo(5L);

        execute("""
            DELETE FROM merchant_document_binding WHERE merchant_id=34200;
            UPDATE merchant_document
               SET superseded_at=statement_timestamp()
             WHERE merchant_id=34200 AND attachment_scope='MERCHANT';
            UPDATE merchant_document document
               SET attachment_scope='MERCHANT', amendment_id=NULL
              FROM merchant_amendment_document reference
             WHERE reference.amendment_id=34220
               AND reference.document_id=document.id;
            INSERT INTO merchant_document_binding(merchant_id,kind,document_id)
            SELECT 34200,kind,document_id
              FROM merchant_amendment_document WHERE amendment_id=34220;
            UPDATE merchant_amendment
               SET status='APPROVED', row_version=1, decision='APPROVE',
                   decision_reason_code='PROFILE_AMENDMENT_VERIFIED',
                   decided_by_tenant_id=32002, decided_by_membership_id=32412,
                   decided_at=statement_timestamp(), applied_merchant_version=2,
                   updated_at=statement_timestamp()
             WHERE id=34220;
            """);

        assertThat(strings("""
            SELECT amendment.status || ':' || count(binding.document_id)
              FROM merchant_amendment amendment
              JOIN merchant_document_binding binding ON binding.merchant_id=amendment.merchant_id
             WHERE amendment.id=34220
             GROUP BY amendment.status
            """)).containsExactly("APPROVED:5");
        assertThat(singleLong("""
            SELECT count(*) FROM merchant_amendment
             WHERE id=34220 AND status='PENDING_REVIEW'
            """)).isZero();
    }

    @Test
    void v42RejectsIncompleteHistoricalAmendmentDocumentsWithoutPartialBackfill()
        throws Exception {
        flyway("41").migrate();
        insertV41HistoricalAmendmentEvidence(4);

        assertThatThrownBy(() -> flyway(null).migrate())
            .isInstanceOf(org.flywaydb.core.api.FlywayException.class)
            .hasMessageContaining("V42 blocked: historical amendment document evidence is incomplete");

        assertThat(currentSuccessfulVersion()).isEqualTo("41");
        assertThat(singleLong("""
            SELECT count(*) FROM information_schema.tables
             WHERE table_schema='public' AND table_name='merchant_amendment_document'
            """)).isZero();
        assertThat(singleLong("""
            SELECT count(*) FROM merchant_document
             WHERE amendment_id=34220 AND attachment_scope='AMENDMENT'
            """)).isEqualTo(4L);
    }

    @Test
    void v39RejectsAHostilePreexistingAmendmentRotationBoundary() throws Exception {
        flyway("38").migrate();
        execute("""
            CREATE FUNCTION merchant_amendment_rotation_guard()
            RETURNS trigger LANGUAGE plpgsql AS $body$
            BEGIN RETURN NEW; END
            $body$
            """);

        assertThatThrownBy(() -> flyway(null).migrate())
            .isInstanceOf(org.flywaydb.core.api.FlywayException.class)
            .hasMessageContaining("rotation boundary is absent or modified");
        assertThat(currentSuccessfulVersion()).isEqualTo("38");
        assertThat(singleLong("""
            SELECT count(*) FROM information_schema.table_privileges
             WHERE table_schema='public' AND table_name='merchant_amendment'
               AND grantee='payment_merchant_registration_rotation'
            """)).isZero();
    }

    @Test
    void v36DirectMerchantUpgradesWithoutChangingAggregateEvidence() throws Exception {
        flyway("36").migrate();
        insertTenantFixtures();
        insertMerchant(33801, 32002, "MCH_V36_DIRECT", "38");
        execute("UPDATE merchant SET status='ACTIVE',status_reason_code='PROFILE_VERIFIED',"
            + "row_version=1 WHERE id=33801");
        execute("UPDATE merchant SET merchant_type_code='DIRECT',legal_person_name='Director',"
            + "authentication_type='ENTERPRISE',row_version=2 WHERE id=33801");
        assertThat(singleLong("SELECT count(*) FROM pg_extension WHERE extname='pgcrypto'"))
            .isZero();

        flyway(null).migrate();

        assertThat(currentSuccessfulVersion()).isEqualTo("43");
        assertThat(strings("""
            SELECT merchant_type_code || ':' || status || ':' || row_version
              FROM merchant WHERE id=33801
            """)).containsExactly("PLATFORM:ACTIVE:2");
        assertThat(singleLong("SELECT count(*) FROM merchant_audit_event WHERE merchant_id=33801"))
            .isZero();
    }

    @Test
    void v37BridgeRejectsDriftedV36EvenWhenNoDirectMerchantExists() throws Exception {
        flyway("36").migrate();
        execute("ALTER TABLE merchant DROP CONSTRAINT ck_merchant_type_code");
        execute("ALTER TABLE merchant ADD CONSTRAINT ck_merchant_type_code CHECK (true)");

        assertThatThrownBy(() -> flyway(null).migrate())
            .isInstanceOf(org.flywaydb.core.api.FlywayException.class)
            .hasMessageContaining("V37 bridge blocked: V36 lifecycle or constraints drifted");

        assertThat(currentSuccessfulVersion()).isEqualTo("36");
        assertThat(singleLong("SELECT count(*) FROM information_schema.columns "
            + "WHERE table_schema='public' AND table_name='merchant' "
            + "AND column_name='brand_name'" )).isZero();
    }

    @Test
    void v37BridgeCallbackContainsNoPgcryptoDependency() throws Exception {
        String source = Files.readString(V37_BRIDGE_CALLBACK, StandardCharsets.UTF_8);
        assertThat(source).contains("sha256(convert_to(")
            .doesNotContain("digest(")
            .doesNotContain("CREATE EXTENSION");
        assertThat(Files.size(V37_BRIDGE_CALLBACK)).isEqualTo(3_467L);
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(V37_BRIDGE_CALLBACK))))
            .isEqualTo("28bc55d18b5b90363539cafa4c5425d860bdde0eb8a94c58f32957bed4d732aa");
    }

    @Test
    void v37CallbacksSortDirectCanonicalizationBeforeSubmitAuthorBackfill() {
        assertThat(V37_BRIDGE_CALLBACK.getFileName().toString())
            .isLessThan(V37_AUTHOR_BRIDGE_CALLBACK.getFileName().toString());
    }

    @Test
    void v37SubmitAuthorBridgeIsFrozenAtItsFirstSuccessfulExecution() throws Exception {
        assertThat(Files.size(V37_AUTHOR_BRIDGE_CALLBACK)).isEqualTo(4_083L);
        assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(Files.readAllBytes(V37_AUTHOR_BRIDGE_CALLBACK))))
            .isEqualTo("c16595e0acceaeffe5860bc31c433a5800203ce5f3fa1db87a7d2e4a1db4e901");
    }

    @Test
    void v36SubmitAuthorEvidenceUpgradesWithoutChangingAggregateEvidence() throws Exception {
        flyway("36").migrate();
        insertTenantFixtures();
        insertActorFixture();
        insertMerchant(33801, 32002, "MCH_V36_AUTHOR", "38");
        execute("""
            INSERT INTO merchant_audit_event(
                id,merchant_id,target_tenant_id,actor_account_domain,actor_tenant_id,
                actor_membership_id,action_code,previous_status,next_status,reason_code,
                changed_fields,trace_id,merchant_version)
            VALUES (33811,33801,32002,'MERCHANT',32002,32410,'SUBMIT',NULL,
                'PENDING_REVIEW','APPLICATION_SUBMITTED',
                '["legalName","displayName","registrationCountry","registrationNumber"]',
                'v36-author',0)
            """);

        flyway(null).migrate();

        assertThat(currentSuccessfulVersion()).isEqualTo("43");
        assertThat(strings("""
            SELECT application_source || ':' || application_actor_tenant_id || ':'
                   || application_author_membership_id || ':' || row_version
              FROM merchant WHERE id=33801
            """)).containsExactly("MERCHANT:32002:32410:0");
        assertThat(singleLong("SELECT count(*) FROM merchant_audit_event WHERE merchant_id=33801"))
            .isEqualTo(1L);
    }

    @Test
    void v36DirectAndSubmitAuthorUpgradeInOneTransaction() throws Exception {
        flyway("36").migrate();
        insertTenantFixtures();
        insertActorFixture();
        insertMerchant(33801, 32002, "MCH_V36_DIRECT_AUTHOR", "38");
        execute("UPDATE merchant SET status='ACTIVE',status_reason_code='PROFILE_VERIFIED',"
            + "row_version=1 WHERE id=33801");
        execute("UPDATE merchant SET merchant_type_code='DIRECT',legal_person_name='Director',"
            + "authentication_type='ENTERPRISE',row_version=2 WHERE id=33801");
        execute("""
            INSERT INTO merchant_audit_event(
                id,merchant_id,target_tenant_id,actor_account_domain,actor_tenant_id,
                actor_membership_id,action_code,previous_status,next_status,reason_code,
                changed_fields,trace_id,merchant_version)
            VALUES (33811,33801,32002,'MERCHANT',32002,32410,'SUBMIT',NULL,
                'PENDING_REVIEW','APPLICATION_SUBMITTED',
                '["legalName","displayName","registrationCountry","registrationNumber"]',
                'v36-direct-author',0)
            """);

        flyway(null).migrate();

        assertThat(strings("""
            SELECT merchant_type_code || ':' || application_source || ':' || row_version
              FROM merchant WHERE id=33801
            """)).containsExactly("PLATFORM:MERCHANT:2");
        assertThat(strings("""
            SELECT pg_get_functiondef('merchant_enforce_lifecycle()'::regprocedure)
            """).getFirst()).contains("merchant application author is immutable")
            .doesNotContain("V37 bridge permits only");
    }

    @Test
    void v38SeedsExactIndustryAndLegalIdDictionariesOnce() throws Exception {
        flyway("37").migrate();
        execute("UPDATE sys_dictionary_catalog_revision SET revision=7 WHERE singleton_id=1");

        flyway(null).migrate();

        assertThat(currentSuccessfulVersion()).isEqualTo("43");
        assertThat(strings("""
            SELECT type_row.dict_type || ':' || type_row.dict_name || ':' || data_row.value
                   || ':' || data_row.label || ':' || data_row.color || ':' || data_row.sort_order
              FROM sys_dictionary_type type_row
              JOIN sys_dictionary_data data_row ON data_row.dictionary_type_id=type_row.id
             WHERE type_row.dict_type IN ('MERCHANT_INDUSTRY_CODE','MERCHANT_LEGAL_ID_TYPE')
             ORDER BY type_row.dict_type,data_row.sort_order
            """)).containsExactly(
                "MERCHANT_INDUSTRY_CODE:商户-行业:FINANCIAL_SERVICES:金融服务:processing:1",
                "MERCHANT_INDUSTRY_CODE:商户-行业:ECOMMERCE:电商:success:2",
                "MERCHANT_INDUSTRY_CODE:商户-行业:RETAIL:零售:purple:3",
                "MERCHANT_INDUSTRY_CODE:商户-行业:TRAVEL:旅行:warning:4",
                "MERCHANT_INDUSTRY_CODE:商户-行业:EDUCATION:教育:default:5",
                "MERCHANT_INDUSTRY_CODE:商户-行业:OTHER:其他:default:6",
                "MERCHANT_LEGAL_ID_TYPE:商户-法人证件类型:NATIONAL_ID:国民身份证:processing:1",
                "MERCHANT_LEGAL_ID_TYPE:商户-法人证件类型:PASSPORT:护照:success:2",
                "MERCHANT_LEGAL_ID_TYPE:商户-法人证件类型:DRIVER_LICENSE:驾驶证:warning:3");
        assertThat(singleLong(
            "SELECT revision FROM sys_dictionary_catalog_revision WHERE singleton_id=1"))
            .isEqualTo(8L);
    }

    @Test
    void v34RejectsModifiedMerchantListPageWithoutPartialCapabilityWrites() throws Exception {
        flyway("31").migrate();
        execute("""
            INSERT INTO iam_tenant(id,tenant_code,tenant_name,tenant_type,status,account_domain)
            VALUES (32001,'mch-v34-platform','MCH V34 Platform','PLATFORM','ACTIVE','PLATFORM');
            INSERT INTO iam_role(id,tenant_id,role_code,role_name,applicable_tenant_type,
                                 assignable,system_role,status)
            VALUES (32101,32001,'mch-platform-admin','MCH Platform Administrator',
                    'PLATFORM',false,true,'ACTIVE');
            INSERT INTO iam_role_grant(id,tenant_id,role_id,permission_id,grant_key,status)
            SELECT 32111,32001,32101,id,'system-backoffice-access','ACTIVE'
              FROM iam_permission WHERE permission_code='backoffice:platform-access';
            INSERT INTO iam_grant_dimension(id,grant_id,dimension_code,scope_mode)
            VALUES (32121,32111,'TENANT','TENANT_ALL');
            """);
        flyway("33").migrate();
        execute("UPDATE iam_menu SET status='DISABLED' WHERE tenant_id=32001 "
            + "AND route_name='MerchantList'");

        assertThatThrownBy(() -> flyway(null).migrate())
            .isInstanceOf(org.flywaydb.core.api.FlywayException.class)
            .hasMessageContaining("one live MerchantList page");
        assertThat(currentSuccessfulVersion()).isEqualTo("33");
        assertThat(singleLong("SELECT count(*) FROM iam_permission "
            + "WHERE permission_code='merchant:update'" )).isZero();
        assertThat(singleLong("SELECT count(*) FROM information_schema.columns "
            + "WHERE table_schema='public' AND table_name='merchant' "
            + "AND column_name='remarks'" )).isZero();
        assertThat(strings("""
            SELECT pg_get_functiondef('merchant_enforce_lifecycle()'::regprocedure)
            """).getFirst()).doesNotContain("to_jsonb(OLD) ? 'status_reason_code'");
    }

    @Test
    void v34LeavesHistoricalStatusReasonNullWithoutSameVersionStatusAuditEvidence()
        throws Exception {
        flyway("33").migrate();
        insertTenantFixtures();
        insertActorFixture();
        execute("""
            INSERT INTO merchant(
                id,tenant_id,account_domain,merchant_code,legal_name,display_name,
                registration_country,registration_number_masked,registration_fingerprint,
                registration_search_key_id,registration_fingerprint_algorithm,
                registration_normalization_version,registration_ciphertext,registration_nonce,
                registration_auth_tag,registration_aead_key_id,registration_aead_algorithm,
                status,row_version,submitted_at,reviewed_at,last_decision,
                last_decision_reason_code,last_decided_by_membership_id,last_decided_at)
            VALUES (32401,32002,'MERCHANT','MCH_STALE_REASON','Example Legal','Example',
                'SG','********1234',decode(repeat('ab',32),'hex'),
                'mch-registration-search-v1','HMAC-SHA-256',1,decode('010203','hex'),
                decode(repeat('11',12),'hex'),decode(repeat('ee',16),'hex'),
                'mch-registration-aead-v1','AES-256-GCM','DISABLED',2,now(),now(),
                'APPROVE','PROFILE_VERIFIED',32410,now());
            INSERT INTO merchant_audit_event(
                id,merchant_id,target_tenant_id,actor_account_domain,actor_tenant_id,
                actor_membership_id,action_code,previous_status,next_status,reason_code,
                changed_fields,trace_id,merchant_version)
            VALUES (32411,32401,32002,'MERCHANT',32002,32410,'APPROVE',
                'PENDING_REVIEW','ACTIVE','PROFILE_VERIFIED','[]','older-audit',1);
            """);

        flyway(null).migrate();

        assertThat(currentSuccessfulVersion()).isEqualTo("43");
        assertThat(strings("SELECT status_reason_code FROM merchant WHERE id=32401"))
            .containsExactly((String) null);
        assertThat(strings("""
            SELECT merchant_type_code FROM merchant WHERE id=32401
            UNION ALL SELECT legal_person_name FROM merchant WHERE id=32401
            UNION ALL SELECT authentication_type FROM merchant WHERE id=32401
            """)).containsExactly((String) null, (String) null, (String) null);
    }

    @Test
    void v33MerchantWithCurrentAuditEvidenceUpgradesWithoutLifecycleTriggerConflict()
        throws Exception {
        flyway("33").migrate();
        insertTenantFixtures();
        insertActorFixture();
        insertMerchant(32401, 32002, "MCH_CURRENT_REASON", "31");
        execute("""
            INSERT INTO merchant_audit_event(
                id,merchant_id,target_tenant_id,actor_account_domain,actor_tenant_id,
                actor_membership_id,action_code,previous_status,next_status,reason_code,
                changed_fields,trace_id,merchant_version)
            VALUES (32411,32401,32002,'MERCHANT',32002,32410,'SUBMIT',NULL,
                'PENDING_REVIEW','APPLICATION_SUBMITTED',
                '["legalName","displayName","registrationCountry","registrationNumber"]',
                'current-audit',0)
            """);

        flyway(null).migrate();

        assertThat(currentSuccessfulVersion()).isEqualTo("43");
        assertThat(strings("""
            SELECT status_reason_code || ':' || row_version
              FROM merchant WHERE id=32401
            """)).containsExactly("APPLICATION_SUBMITTED:0");
        assertThat(strings("""
            SELECT pg_get_functiondef('merchant_enforce_lifecycle()'::regprocedure)
            """).getFirst()).contains("merchant profile change requires an approved amendment")
            .doesNotContain("to_jsonb(NEW) ? 'status_reason_code'");
    }

    @Test
    void v34BridgeDoesNotPermitOrdinaryUpdatesOnTheV33Schema() throws Exception {
        flyway("33").migrate();
        insertTenantFixtures();
        insertMerchant(32401, 32002, "MCH_BRIDGE_SCOPE", "30");

        execute(Files.readString(V34_AUDIT_PREFLIGHT_CALLBACK, StandardCharsets.UTF_8));
        execute(Files.readString(V34_BRIDGE_CALLBACK, StandardCharsets.UTF_8));

        assertThatThrownBy(() -> execute("""
            UPDATE merchant
               SET display_name = 'Unauthorized same-version update'
             WHERE id = 32401
            """))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("merchant row_version must advance exactly once");
        assertThat(currentSuccessfulVersion()).isEqualTo("33");
        assertThat(singleLong("""
            SELECT count(*) FROM information_schema.columns
             WHERE table_schema='public' AND table_name='merchant'
               AND column_name IN ('remarks','status_reason_code')
            """)).isZero();
        assertThat(strings("SELECT display_name FROM merchant WHERE id=32401"))
            .containsExactly("Example Display");
    }

    @Test
    void v34RejectsAmbiguousAuditVersionEvidenceWithoutPartialWrites() throws Exception {
        flyway("33").migrate();
        insertTenantFixtures();
        insertActorFixture();
        insertMerchant(32401, 32002, "MCH_AMBIGUOUS_REASON", "30");
        execute("""
            INSERT INTO merchant_audit_event(
                id,merchant_id,target_tenant_id,actor_account_domain,actor_tenant_id,
                actor_membership_id,action_code,previous_status,next_status,reason_code,
                changed_fields,trace_id,merchant_version)
            VALUES
                (32411,32401,32002,'MERCHANT',32002,32410,'SUBMIT',NULL,
                 'PENDING_REVIEW','APPLICATION_SUBMITTED',
                 '["legalName","displayName","registrationCountry","registrationNumber"]',
                 'ambiguous-submit',0),
                (32412,32401,32002,'MERCHANT',32002,32410,'RESUBMIT','REVIEW_REJECTED',
                 'PENDING_REVIEW','APPLICATION_RESUBMITTED',
                 '["legalName","displayName","registrationCountry","registrationNumber"]',
                 'ambiguous-resubmit',0)
            """);

        assertThatThrownBy(() -> flyway(null).migrate())
            .isInstanceOf(org.flywaydb.core.api.FlywayException.class)
            .hasMessageContaining("ambiguous Merchant audit version evidence");

        assertThat(currentSuccessfulVersion()).isEqualTo("33");
        assertThat(singleLong("""
            SELECT count(*) FROM information_schema.columns
             WHERE table_schema='public' AND table_name='merchant'
               AND column_name IN ('remarks','status_reason_code')
            """)).isZero();
        assertThat(singleLong("SELECT count(*) FROM iam_permission "
            + "WHERE permission_code='merchant:update'" )).isZero();
        assertThat(strings("""
            SELECT pg_get_functiondef('merchant_enforce_lifecycle()'::regprocedure)
            """).getFirst()).doesNotContain("to_jsonb(OLD) ? 'status_reason_code'");
        assertThat(singleLong("SELECT count(*) FROM merchant_audit_event "
            + "WHERE merchant_id=32401 AND merchant_version=0")).isEqualTo(2L);
    }

    @Test
    void v33MerchantWithMatchingAuditUpgradesToLatestWithoutChangingAggregateVersion()
        throws Exception {
        flyway("33").migrate();
        insertTenantFixtures();
        insertActorFixture();
        insertMerchant(32401, 32002, "MCH_EXACT_REASON", "31");
        execute("""
            INSERT INTO merchant_audit_event(
                id,merchant_id,target_tenant_id,actor_account_domain,actor_tenant_id,
                actor_membership_id,action_code,previous_status,next_status,reason_code,
                changed_fields,trace_id,merchant_version)
            VALUES (32411,32401,32002,'MERCHANT',32002,32410,'SUBMIT',
                NULL,'PENDING_REVIEW','APPLICATION_SUBMITTED',
                '["legalName","displayName","registrationCountry","registrationNumber"]',
                'matching-audit',0)
            """);

        flyway(null).migrate();

        assertThat(currentSuccessfulVersion()).isEqualTo("43");
        assertThat(strings("SELECT status_reason_code FROM merchant WHERE id=32401"))
            .containsExactly("APPLICATION_SUBMITTED");
        assertThat(singleLong("SELECT row_version FROM merchant WHERE id=32401")).isZero();
    }

    @Test
    void v34BridgeRejectsModifiedV33LifecycleFunctionWithoutPartialSchemaWrites()
        throws Exception {
        flyway("33").migrate();
        execute("""
            CREATE OR REPLACE FUNCTION merchant_enforce_lifecycle()
            RETURNS trigger LANGUAGE plpgsql AS $modified$
            BEGIN
                RAISE EXCEPTION 'modified lifecycle function';
            END;
            $modified$
            """);

        assertThatThrownBy(() -> flyway(null).migrate())
            .isInstanceOf(org.flywaydb.core.api.FlywayException.class)
            .hasMessageContaining("V33 Merchant lifecycle function is not canonical");
        assertThat(currentSuccessfulVersion()).isEqualTo("33");
        assertThat(singleLong("""
            SELECT count(*) FROM information_schema.columns
             WHERE table_schema='public' AND table_name='merchant'
               AND column_name IN ('remarks','status_reason_code')
            """)).isZero();
        assertThat(strings("""
            SELECT pg_get_functiondef('merchant_enforce_lifecycle()'::regprocedure)
            """).getFirst()).contains("modified lifecycle function")
            .doesNotContain("to_jsonb(OLD) ? 'status_reason_code'");
    }

    @Test
    void completedLatestMigrationDoesNotReinstallTheV34Bridge() throws Exception {
        flyway(null).migrate();
        String lifecycleFunction = strings("""
            SELECT pg_get_functiondef('merchant_enforce_lifecycle()'::regprocedure)
            """).getFirst();

        flyway(null).migrate();

        assertThat(currentSuccessfulVersion()).isEqualTo("43");
        assertThat(strings("""
            SELECT pg_get_functiondef('merchant_enforce_lifecycle()'::regprocedure)
            """)).containsExactly(lifecycleFunction);
        assertThat(lifecycleFunction)
            .contains("merchant profile change requires an approved amendment")
            .doesNotContain("to_jsonb(OLD) ? 'status_reason_code'");
    }

    @Test
    void v36AddsTheAuditVersionUniquenessBoundaryToAnExistingV35Schema() throws Exception {
        flyway("35").migrate();
        assertThat(currentSuccessfulVersion()).isEqualTo("35");

        flyway(null).migrate();

        assertThat(currentSuccessfulVersion()).isEqualTo("43");
        assertThat(singleLong("""
            SELECT count(*)
              FROM pg_constraint
             WHERE conrelid='merchant_audit_event'::regclass
               AND conname='uk_merchant_audit_merchant_version'
               AND contype='u'
            """)).isEqualTo(1L);
    }

    @Test
    void v36RejectsDuplicateV35AuditVersionsWithoutMutatingHistory() throws Exception {
        flyway("35").migrate();
        insertTenantFixtures();
        insertActorFixture();
        insertMerchant(32401, 32002, "MCH_V35_DUPLICATE_AUDIT", "32");
        execute("""
            INSERT INTO merchant_audit_event(
                id,merchant_id,target_tenant_id,actor_account_domain,actor_tenant_id,
                actor_membership_id,action_code,previous_status,next_status,reason_code,
                changed_fields,trace_id,merchant_version)
            VALUES
                (32411,32401,32002,'MERCHANT',32002,32410,'SUBMIT',NULL,
                 'PENDING_REVIEW','APPLICATION_SUBMITTED',
                 '["legalName","displayName","registrationCountry","registrationNumber"]',
                 'v35-duplicate-submit',0),
                (32412,32401,32002,'MERCHANT',32002,32410,'RESUBMIT','REVIEW_REJECTED',
                 'PENDING_REVIEW','APPLICATION_RESUBMITTED',
                 '["legalName","displayName","registrationCountry","registrationNumber"]',
                 'v35-duplicate-resubmit',0)
            """);

        assertThatThrownBy(() -> flyway(null).migrate())
            .isInstanceOf(org.flywaydb.core.api.FlywayException.class)
            .hasMessageContaining("duplicate Merchant audit version evidence exists");

        assertThat(currentSuccessfulVersion()).isEqualTo("35");
        assertThat(singleLong("SELECT count(*) FROM merchant_audit_event "
            + "WHERE merchant_id=32401 AND merchant_version=0")).isEqualTo(2L);
        assertThat(singleLong("""
            SELECT count(*)
              FROM pg_constraint
             WHERE conrelid='merchant_audit_event'::regclass
               AND conname='uk_merchant_audit_merchant_version'
            """)).isZero();
    }

    @Test
    void rotationRoleWithPreexistingMemberBlocksV33BeforeTriggerReplacement() throws Exception {
        flyway("32").migrate();
        execute("""
            DO $setup$
            BEGIN
              IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='payment_merchant_registration_rotation') THEN
                CREATE ROLE payment_merchant_registration_rotation
                  NOLOGIN NOSUPERUSER INHERIT NOCREATEDB NOCREATEROLE
                  NOREPLICATION NOBYPASSRLS;
              END IF;
              IF NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname='mch_hostile_runtime') THEN
                CREATE ROLE mch_hostile_runtime LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE
                  NOREPLICATION NOBYPASSRLS;
              END IF;
              GRANT payment_merchant_registration_rotation TO mch_hostile_runtime;
            END
            $setup$
            """);

        assertThatThrownBy(() -> flyway(null).migrate())
            .isInstanceOf(org.flywaydb.core.api.FlywayException.class)
            .hasMessageContaining("registration rotation role has preexisting memberships");
        assertThat(currentSuccessfulVersion()).isEqualTo("32");
        assertThat(strings("""
            SELECT pg_get_functiondef('merchant_enforce_lifecycle()'::regprocedure)
            """).getFirst()).contains("payment.merchant_registration_rotation")
            .doesNotContain("current_user = 'payment_merchant_registration_rotation'");
    }

    @Test
    void migrationGrantsOnlyProtectedPlatformAndMerchantRolesAndSeedsNoAgentSurface() throws Exception {
        flyway("31").migrate();
        execute("""
            INSERT INTO iam_tenant(id, tenant_code, tenant_name, tenant_type, status, account_domain)
            VALUES
              (32001, 'mch-v32-platform', 'MCH V32 Platform', 'PLATFORM', 'ACTIVE', 'PLATFORM'),
              (32002, 'mch-v32-merchant', 'MCH V32 Merchant', 'DIRECT_MERCHANT', 'ACTIVE', 'MERCHANT'),
              (32003, 'mch-v32-agent', 'MCH V32 Agent', 'AGENT', 'ACTIVE', 'AGENT');
            INSERT INTO iam_role(
                id, tenant_id, role_code, role_name, applicable_tenant_type,
                assignable, system_role, status)
            VALUES
              (32101, 32001, 'mch-platform-admin', 'MCH Platform Administrator',
               'PLATFORM', false, true, 'ACTIVE'),
              (32102, 32002, 'mch-merchant-admin', 'MCH Merchant Administrator',
               'DIRECT_MERCHANT', false, true, 'ACTIVE'),
              (32103, 32003, 'mch-agent-admin', 'MCH Agent Administrator',
               'AGENT', false, true, 'ACTIVE'),
              (32104, 32001, 'mch-platform-arbitrary-system', 'MCH Arbitrary System Role',
               'PLATFORM', true, true, 'ACTIVE');
            INSERT INTO iam_role_grant(
                id, tenant_id, role_id, permission_id, grant_key, status)
            VALUES
              (32111, 32001, 32101,
               (SELECT id FROM iam_permission WHERE permission_code='backoffice:platform-access'),
               'system-backoffice-access', 'ACTIVE'),
              (32112, 32002, 32102,
               (SELECT id FROM iam_permission WHERE permission_code='backoffice:merchant-access'),
               'system-backoffice-access', 'ACTIVE'),
              (32113, 32003, 32103,
               (SELECT id FROM iam_permission WHERE permission_code='backoffice:agent-access'),
               'system-backoffice-access', 'ACTIVE'),
              (32114, 32001, 32104,
               (SELECT id FROM iam_permission WHERE permission_code='backoffice:platform-access'),
               'system-backoffice-access', 'ACTIVE');
            INSERT INTO iam_grant_dimension(id, grant_id, dimension_code, scope_mode)
            VALUES
              (32121, 32111, 'TENANT', 'TENANT_ALL'),
              (32122, 32112, 'TENANT', 'TENANT_ALL'),
              (32123, 32113, 'TENANT', 'TENANT_ALL'),
              (32124, 32114, 'TENANT', 'TENANT_ALL');
            """);

        flyway(null).migrate();

        assertThat(strings("""
            SELECT permission.permission_code
              FROM iam_role_grant grant_row
              JOIN iam_permission permission ON permission.id = grant_row.permission_id
             WHERE grant_row.role_id = 32101
               AND permission.permission_code LIKE 'merchant:%'
             ORDER BY permission.permission_code
            """)).containsExactly(
                "merchant:amend", "merchant:create", "merchant:disable",
                "merchant:document:upload", "merchant:document:view", "merchant:enable", "merchant:review",
                "merchant:terminate", "merchant:update", "merchant:view");
        assertThat(strings("""
            SELECT permission.permission_code
              FROM iam_role_grant grant_row
              JOIN iam_permission permission ON permission.id = grant_row.permission_id
             WHERE grant_row.role_id = 32102
               AND permission.permission_code LIKE 'merchant:%'
             ORDER BY permission.permission_code
            """)).containsExactly(
                "merchant:resubmit", "merchant:self-view", "merchant:submit");
        assertThat(singleLong("""
            SELECT count(*)
              FROM iam_role_grant grant_row
              JOIN iam_permission permission ON permission.id = grant_row.permission_id
             WHERE grant_row.role_id IN (32103, 32104)
               AND permission.permission_code LIKE 'merchant:%'
            """)).isZero();
        assertThat(singleLong("""
            SELECT count(*)
              FROM iam_role_grant grant_row
              JOIN iam_permission permission ON permission.id = grant_row.permission_id
              JOIN iam_grant_dimension dimension ON dimension.grant_id = grant_row.id
             WHERE grant_row.role_id IN (32101, 32102)
               AND permission.permission_code LIKE 'merchant:%'
               AND grant_row.status = 'ACTIVE'
               AND grant_row.valid_from IS NOT NULL
               AND grant_row.valid_until IS NOT NULL
               AND grant_row.valid_until > grant_row.valid_from
               AND dimension.dimension_code = 'TENANT'
               AND dimension.scope_mode = 'TENANT_ALL'
               AND NOT EXISTS (
                   SELECT 1 FROM iam_grant_target target
                    WHERE target.dimension_id = dimension.id)
            """)).isEqualTo(13L);

        assertThat(strings("""
            SELECT menu.route_name
              FROM iam_menu menu
             WHERE menu.tenant_id = 32001
               AND menu.route_name IN (
                   'MerchantManagement', 'MerchantList', 'MerchantReview',
                   'MerchantDisable', 'MerchantEnable', 'MerchantEdit', 'MerchantTerminate')
             ORDER BY menu.route_name
            """)).containsExactly(
                "MerchantDisable", "MerchantEdit", "MerchantEnable", "MerchantList",
                "MerchantManagement", "MerchantReview", "MerchantTerminate");
        assertThat(strings("""
            SELECT menu.route_name || ':' || (menu.meta_json->>'title')
              FROM iam_menu menu
             WHERE menu.tenant_id = 32001
               AND menu.route_name IN (
                   'MerchantReview', 'MerchantDisable', 'MerchantEnable',
                   'MerchantTerminate', 'MerchantEdit', 'MerchantCreate')
             ORDER BY menu.route_name
            """)).containsExactly(
                "MerchantCreate:merchant.permission.create",
                "MerchantDisable:merchant.permission.disable",
                "MerchantEdit:merchant.permission.edit",
                "MerchantEnable:merchant.permission.enable",
                "MerchantReview:merchant.permission.review",
                "MerchantTerminate:merchant.permission.terminate");
        assertThat(strings("""
            SELECT menu.route_name
              FROM iam_menu menu
             WHERE menu.tenant_id = 32002
               AND menu.route_name IN ('MerchantProfile', 'MerchantSubmit', 'MerchantResubmit')
             ORDER BY menu.route_name
            """)).containsExactly("MerchantProfile", "MerchantResubmit", "MerchantSubmit");
        assertThat(singleLong("""
            SELECT count(*) FROM iam_menu
             WHERE tenant_id = 32003
               AND (route_name LIKE 'Merchant%' OR auth_code LIKE 'merchant:%')
            """)).isZero();
        assertThat(singleLong("""
            SELECT count(*)
              FROM iam_menu page
             WHERE (page.tenant_id = 32001
                    AND page.route_name = 'MerchantList'
                    AND page.route_path = '/merchant/list'
                    AND page.component_path = '/merchant/list'
                    AND page.auth_code = 'merchant:view'
                    AND page.meta_json->>'title' = 'merchant.list.title')
                OR (page.tenant_id = 32002
                    AND page.route_name = 'MerchantProfile'
                    AND page.route_path = '/merchant/profile'
                    AND page.component_path = '/merchant/profile'
                    AND page.auth_code = 'merchant:self-view'
                    AND page.meta_json->>'title' = 'merchant.profile.title')
            """)).isEqualTo(2L);
    }

    @Test
    void localV31FixtureUpgradeReproducesV32CollisionThenPreservesOldAndNewMenus() throws Exception {
        flyway("31").migrate();
        insertLegacyLocalMerchantFixture();

        assertThatThrownBy(() -> flyway("32").migrate())
            .isInstanceOf(org.flywaydb.core.api.FlywayException.class)
            .hasMessageContaining("iam_role_menu_pkey")
            .hasMessageContaining("(2, 2200, 6200)");
        assertThat(currentSuccessfulVersion()).isEqualTo("31");

        execute(localV32UpgradePreparation());
        execute(localV32UpgradePreparation());
        flyway(null).migrate();

        assertThat(currentSuccessfulVersion()).isEqualTo("43");
        assertThat(strings("""
            SELECT menu.route_name
              FROM iam_role_menu role_menu
              JOIN iam_menu menu
                ON menu.tenant_id = role_menu.tenant_id AND menu.id = role_menu.menu_id
             WHERE role_menu.tenant_id = 2 AND role_menu.role_id = 2200
               AND menu.route_name IN (
                   'MerchantDashboard', 'MerchantWorkspace',
                   'MerchantProfile', 'MerchantSubmit', 'MerchantResubmit')
             ORDER BY menu.route_name
            """)).containsExactly(
                "MerchantDashboard", "MerchantProfile", "MerchantResubmit",
                "MerchantSubmit", "MerchantWorkspace");
        assertThat(singleLong("""
            SELECT count(*) FROM iam_menu
             WHERE tenant_id = 2
               AND ((id = 6200 AND parent_id IS NULL
                     AND route_name = 'MerchantDashboard'
                     AND redirect_path = '/dashboard/workspace' AND sort_order = 10
                     AND auth_code IS NULL)
                 OR (id = 6201 AND parent_id = 6200
                     AND route_name = 'MerchantWorkspace'
                     AND redirect_path IS NULL AND sort_order = 20
                     AND auth_code IS NULL))
            """)).isEqualTo(2L);

        execute(localV32UpgradePreparation());
        assertThat(currentSuccessfulVersion()).isEqualTo("43");
        assertThat(singleLong("""
            SELECT count(*) FROM iam_role_menu
             WHERE tenant_id = 2 AND role_id = 2200 AND menu_id IN (6200, 6201)
            """)).isEqualTo(2L);
    }

    @Test
    void localV32UpgradePreparationRejectsInexactParentWithoutMutation() throws Exception {
        assertInexactLocalMenuRejected("UPDATE iam_menu SET parent_id = NULL WHERE id = 6201");
    }

    @Test
    void localV32UpgradePreparationRejectsInexactRedirectWithoutMutation() throws Exception {
        assertInexactLocalMenuRejected(
            "UPDATE iam_menu SET redirect_path = '/unexpected' WHERE id = 6200");
    }

    @Test
    void localV32UpgradePreparationRejectsInexactSortWithoutMutation() throws Exception {
        assertInexactLocalMenuRejected("UPDATE iam_menu SET sort_order = 99 WHERE id = 6201");
    }

    @Test
    void localV32UpgradePreparationRejectsInexactAuthCodeWithoutMutation() throws Exception {
        assertInexactLocalMenuRejected(
            "UPDATE iam_menu SET auth_code = 'merchant:self-view' WHERE id = 6201");
    }

    @Test
    void localV32UpgradePreparationIsNoOpForFreshDatabase() throws Exception {
        execute(localV32UpgradePreparation());

        flyway(null).migrate();

        assertThat(currentSuccessfulVersion()).isEqualTo("43");
        assertThat(singleLong("SELECT count(*) FROM iam_tenant WHERE id = 2")).isZero();
    }

    @Test
    void v35PreservesExactPreseedWithoutAdvancingCatalogRevision() throws Exception {
        flyway("34").migrate();
        execute(insertCanonicalV35Dictionaries());
        execute("UPDATE sys_dictionary_catalog_revision SET revision=7 WHERE singleton_id=1");

        flyway(null).migrate();

        assertThat(currentSuccessfulVersion()).isEqualTo("43");
        assertThat(singleLong(
            "SELECT revision FROM sys_dictionary_catalog_revision WHERE singleton_id=1"))
            .isEqualTo(9L);
        assertThat(singleLong("""
            SELECT count(*) FROM sys_dictionary_data data_row
              JOIN sys_dictionary_type type_row ON type_row.id=data_row.dictionary_type_id
             WHERE type_row.dict_type IN ('MERCHANT_TYPE_CODE','MERCHANT_AUTH_TYPE')
            """)).isEqualTo(10L);
    }

    @Test
    void v35RejectsPartialDictionaryCollisionAndRollsBackSchemaChanges() throws Exception {
        flyway("34").migrate();
        execute("""
            INSERT INTO sys_dictionary_type(id,dict_type,dict_name,sort_order,remark)
            VALUES (35001,'MERCHANT_TYPE_CODE','商户-商户类型',0,'')
            """);

        assertThatThrownBy(() -> flyway(null).migrate())
            .isInstanceOf(org.flywaydb.core.api.FlywayException.class)
            .hasMessageContaining("MERCHANT_TYPE_CODE must be absent or exactly match");
        assertThat(currentSuccessfulVersion()).isEqualTo("34");
        assertThat(singleLong("""
            SELECT count(*) FROM information_schema.columns
             WHERE table_schema='public' AND table_name='merchant'
               AND column_name='merchant_type_code'
            """)).isZero();
        assertThat(singleLong(
            "SELECT revision FROM sys_dictionary_catalog_revision WHERE singleton_id=1"))
            .isEqualTo(1L);
    }

    @Test
    void v35RejectsModifiedDictionaryCollision() throws Exception {
        flyway("34").migrate();
        execute(insertCanonicalV35Dictionaries());
        execute("""
            UPDATE sys_dictionary_data SET label='wrong'
             WHERE dictionary_type_id=(SELECT id FROM sys_dictionary_type
                                        WHERE dict_type='MERCHANT_AUTH_TYPE')
               AND value='ENTERPRISE'
            """);

        assertThatThrownBy(() -> flyway(null).migrate())
            .isInstanceOf(org.flywaydb.core.api.FlywayException.class)
            .hasMessageContaining("MERCHANT_AUTH_TYPE must be absent or exactly match");
        assertThat(currentSuccessfulVersion()).isEqualTo("34");
    }

    @Test
    void v38DatabaseConstraintsRejectPartialHistoricalOrUnknownClassification() throws Exception {
        flyway(null).migrate();
        insertTenantFixtures();
        insertMerchant(32501, 32002, "MCH_CLASSIFIED", "31");
        execute("UPDATE merchant SET status='ACTIVE', status_reason_code='PROFILE_VERIFIED', "
            + "row_version=1 WHERE id=32501");

        assertThatThrownBy(() -> execute("""
            UPDATE merchant SET merchant_type_code='DIRECT', row_version=2 WHERE id=32501
            """)).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute("""
            UPDATE merchant SET merchant_type_code='UNKNOWN', legal_person_name='Director',
                                authentication_type='ENTERPRISE', row_version=2
             WHERE id=32501
            """)).isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute("""
            UPDATE merchant SET merchant_type_code='DIRECT', legal_person_name=repeat('x',201),
                                authentication_type='ENTERPRISE', row_version=2
             WHERE id=32501
            """)).isInstanceOf(SQLException.class);
        assertThat(strings("""
            SELECT coalesce(merchant_type_code,'NULL') || ':'
                   || coalesce(legal_person_name,'NULL') || ':'
                   || coalesce(authentication_type,'NULL')
              FROM merchant WHERE id=32501
            """)).containsExactly("NULL:NULL:NULL");
    }

    @Test
    void databaseRejectsWrongOrMutableTenantBindingAndIllegalLifecycleWrites() throws Exception {
        flyway(null).migrate();
        insertTenantFixtures();

        assertThatThrownBy(() -> insertMerchant(32201, 32003, "MCH_AGENT", "01"))
            .isInstanceOf(SQLException.class);

        insertMerchant(32202, 32002, "MCH_ALPHA", "02");
        assertThatThrownBy(() -> execute("UPDATE merchant SET tenant_id = 32003 WHERE id = 32202"))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("immutable");
        assertThatThrownBy(() -> execute("UPDATE merchant SET merchant_code = 'MCH_CHANGED' WHERE id = 32202"))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("immutable");
        assertThatThrownBy(() -> execute("UPDATE merchant SET status = 'DISABLED', row_version = 1 WHERE id = 32202"))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("transition");
        assertThatThrownBy(() -> execute("UPDATE merchant SET status = 'ACTIVE', row_version = 4 WHERE id = 32202"))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("row_version");
        execute("UPDATE merchant SET status = 'ACTIVE', status_reason_code='PROFILE_VERIFIED', "
            + "row_version = 1 WHERE id = 32202");
        execute("UPDATE merchant SET status = 'DISABLED', status_reason_code='RISK_CONTROL', "
            + "row_version = 2 WHERE id = 32202");
        execute("UPDATE merchant SET status = 'TERMINATED', status_reason_code='BUSINESS_CLOSED', "
            + "row_version = 3 WHERE id = 32202");
        assertThatThrownBy(() -> execute("UPDATE merchant SET status = 'ACTIVE', row_version = 4 WHERE id = 32202"))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("transition");
        assertThatThrownBy(() -> execute("DELETE FROM merchant WHERE id = 32202"))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("never physically deleted");
    }

    @Test
    void databaseProtectsRegistrationCryptoMetadataAndActiveKeySingletons() throws Exception {
        flyway(null).migrate();
        insertTenantFixtures();
        insertMerchant(32301, 32002, "MCH_CRYPTO_A", "11");

        assertThatThrownBy(() -> insertMerchant(32302, 32004, "MCH_CRYPTO_B", "11"))
            .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> insertMerchantWithFingerprint(
            32303, 32004, "MCH_CRYPTO_C", "12", "ab"))
            .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute("""
            INSERT INTO merchant_registration_key_metadata(
                key_purpose, key_id, algorithm, active)
            VALUES ('REGISTRATION_SEARCH_HMAC', 'mch-registration-search-v2',
                    'HMAC-SHA-256', true)
            """))
            .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute("""
            UPDATE merchant_registration_key_metadata
               SET active = false
             WHERE key_purpose = 'REGISTRATION_AEAD' AND active
            """))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("exactly one active key");
    }

    @Test
    void auditAndDeduplicationAreAppendOnlyAndRejectSensitiveOrInconsistentFacts() throws Exception {
        flyway(null).migrate();
        insertTenantFixtures();
        insertActorFixture();
        insertMerchant(32401, 32002, "MCH_AUDIT", "21");
        execute("""
            INSERT INTO merchant_audit_event(
                id, merchant_id, target_tenant_id, actor_account_domain, actor_tenant_id,
                actor_membership_id, action_code, previous_status, next_status,
                reason_code, changed_fields, trace_id, merchant_version)
            VALUES (32411, 32401, 32002, 'MERCHANT', 32002, 32410,
                    'SUBMIT', NULL, 'PENDING_REVIEW', 'APPLICATION_SUBMITTED',
                    '["legalName","displayName","registrationCountry","registrationNumber"]',
                    'mch-v32-trace', 0)
            """);
        execute("""
            INSERT INTO merchant_command_dedup(
                id, actor_account_domain, actor_tenant_id, actor_membership_id,
                command_type, idempotency_key, request_digest,
                idempotency_hmac_key_id, command_schema_version,
                canonical_digest_scheme_version, registration_normalization_version,
                required_permission, merchant_id, result_merchant_code,
                result_status, result_row_version)
            VALUES (32412, 'MERCHANT', 32002, 32410, 'SUBMIT',
                    '8ce154cf-4f13-4aac-b0de-74922513a14f', decode(repeat('cd',32),'hex'),
                    'mch-idempotency-v1', 1, 1, 1, 'merchant:submit',
                    32401, 'MCH_AUDIT', 'PENDING_REVIEW', 0)
            """);

        assertThatThrownBy(() -> execute(
            "UPDATE merchant_audit_event SET trace_id = 'changed' WHERE id = 32411"))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("append-only");
        assertThatThrownBy(() -> execute("DELETE FROM merchant_command_dedup WHERE id = 32412"))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("append-only");
        assertThatThrownBy(() -> execute("""
            INSERT INTO merchant_audit_event(
                id, merchant_id, target_tenant_id, actor_account_domain, actor_tenant_id,
                actor_membership_id, action_code, previous_status, next_status,
                reason_code, changed_fields, trace_id, merchant_version)
            VALUES (32415, 32401, 32002, 'MERCHANT', 32002, 32410,
                    'SUBMIT', NULL, 'PENDING_REVIEW', 'APPLICATION_SUBMITTED',
                    '["legalName","displayName","registrationCountry","registrationNumber"]',
                    'duplicate-version', 0)
            """))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("uk_merchant_audit_merchant_version");
        assertThatThrownBy(() -> execute("""
            INSERT INTO merchant_audit_event(
                id, merchant_id, target_tenant_id, actor_account_domain, actor_tenant_id,
                actor_membership_id, action_code, previous_status, next_status,
                reason_code, changed_fields, trace_id, merchant_version)
            VALUES (32413, 32401, 32002, 'MERCHANT', 32002, 32410,
                    'SUBMIT', NULL, 'PENDING_REVIEW', 'RISK_CONTROL',
                    '[]', 'invalid-reason', 0)
            """))
            .isInstanceOf(SQLException.class);
        assertThatThrownBy(() -> execute("""
            INSERT INTO merchant_command_dedup(
                id, actor_account_domain, actor_tenant_id, actor_membership_id,
                command_type, idempotency_key, request_digest,
                idempotency_hmac_key_id, command_schema_version,
                canonical_digest_scheme_version, registration_normalization_version,
                required_permission, merchant_id, result_merchant_code,
                result_status, result_row_version)
            VALUES (32414, 'MERCHANT', 32002, 32410, 'SUBMIT',
                    '8ce154cf-4f13-4aac-b0de-74922513a14f', decode(repeat('ef',32),'hex'),
                    'mch-idempotency-v1', 1, 1, 1, 'merchant:resubmit',
                    32401, 'MCH_AUDIT', 'PENDING_REVIEW', 0)
            """))
            .isInstanceOf(SQLException.class);
    }

    private static Flyway flyway(String target) {
        var configuration = Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations(
                filesystemLocation(BACKEND_ROOT.resolve(
                    "modules/identity/persistence-postgres/src/main/resources/db/migration")),
                filesystemLocation(BACKEND_ROOT.resolve(
                    "modules/system-dictionary/src/main/resources/db/migration")),
                filesystemLocation(BACKEND_ROOT.resolve(
                    "modules/merchant/persistence-postgres/src/main/resources/db/migration")))
            .cleanDisabled(false);
        configuration.getConfigurationExtension(PostgreSQLConfigurationExtension.class)
            .setTransactionalLock(false);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private static String insertCanonicalV35Dictionaries() {
        return """
            WITH type_row AS (
              INSERT INTO sys_dictionary_type(id,dict_type,dict_name,sort_order,remark)
              VALUES (35001,'MERCHANT_TYPE_CODE','商户-商户类型',0,'') RETURNING id
            )
            INSERT INTO sys_dictionary_data(
                id,dictionary_type_id,label,value,color,sort_order,remark)
            VALUES
              (35101,35001,'直连商户','DIRECT','success',1,''),
              (35102,35001,'间连商户','INDIRECT','warning',2,''),
              (35103,35001,'分佣商户','COMMISSION','default',3,''),
              (35104,35001,'销售商户','SALES','processing',4,''),
              (35105,35001,'平台商户','PLATFORM','purple',5,'');
            INSERT INTO sys_dictionary_type(id,dict_type,dict_name,sort_order,remark)
            VALUES (35002,'MERCHANT_AUTH_TYPE','商户-认证类型',0,'');
            INSERT INTO sys_dictionary_data(
                id,dictionary_type_id,label,value,color,sort_order,remark)
            VALUES
              (35201,35002,'企业','ENTERPRISE','processing',1,''),
              (35202,35002,'非盈利组织','NON_PROFIT_ORGANIZATIONS','success',2,''),
              (35203,35002,'集团','CLIQUE','purple',3,''),
              (35204,35002,'个人','INDIVIDUAL','default',4,''),
              (35205,35002,'个体户','INDIVIDUAL_HOUSEHOLD','warning',5,'');
            """;
    }

    private static void assertInexactLocalMenuRejected(String mutation) throws Exception {
        flyway("31").migrate();
        insertLegacyLocalMerchantFixture();
        execute(mutation);

        assertThatThrownBy(() -> execute(localV32UpgradePreparation()))
            .isInstanceOf(SQLException.class)
            .hasMessageContaining("local Merchant upgrade menus were not exact");
        assertThat(singleLong("""
            SELECT count(*) FROM iam_role_menu
             WHERE tenant_id = 2 AND role_id = 2200 AND menu_id IN (6200, 6201)
            """)).isEqualTo(2L);
        assertThat(currentSuccessfulVersion()).isEqualTo("31");
    }

    private static void insertLegacyLocalMerchantFixture() throws Exception {
        execute("""
            INSERT INTO iam_tenant(
                id, tenant_code, tenant_name, tenant_type, status, account_domain)
            VALUES (2, 'local-merchant', 'Local Merchant',
                    'DIRECT_MERCHANT', 'ACTIVE', 'MERCHANT');
            INSERT INTO iam_role(
                id, tenant_id, role_code, role_name, applicable_tenant_type,
                assignable, system_role, status)
            VALUES (2200, 2, 'merchant-admin', 'Merchant Administrator',
                    'DIRECT_MERCHANT', false, true, 'ACTIVE');
            INSERT INTO iam_role_grant(
                id, tenant_id, role_id, permission_id, grant_key, status)
            SELECT 7023, 2, 2200, permission.id, 'system-backoffice-access', 'ACTIVE'
              FROM iam_permission permission
             WHERE permission.permission_code = 'backoffice:merchant-access';
            INSERT INTO iam_grant_dimension(id, grant_id, dimension_code, scope_mode)
            VALUES (8023, 7023, 'TENANT', 'TENANT_ALL');
            INSERT INTO iam_menu(
                id, tenant_id, parent_id, menu_type, menu_name, route_name, route_path,
                component_path, redirect_path, sort_order, auth_code, status,
                meta_json, system_managed)
            VALUES
              (6200, 2, NULL, 'DIRECTORY', 'Dashboard', 'MerchantDashboard', '/dashboard',
               NULL, '/dashboard/workspace', 10, NULL, 'ACTIVE',
               '{"title":"page.dashboard.title","icon":"lucide:layout-dashboard"}'::jsonb, true),
              (6201, 2, 6200, 'PAGE', 'Workspace', 'MerchantWorkspace',
               '/dashboard/workspace', '/dashboard/workspace/index', NULL, 20, NULL, 'ACTIVE',
               '{"title":"page.dashboard.workspace"}'::jsonb, true);
            INSERT INTO iam_role_menu(tenant_id, role_id, menu_id)
            VALUES (2, 2200, 6200), (2, 2200, 6201);
            """);
    }

    private static String localV32UpgradePreparation() throws Exception {
        Path script = BACKEND_ROOT.getParent().resolve("scripts/dev/iam002_local.py");
        Process process = new ProcessBuilder(
            "python3", "-c", """
                import importlib.util
                import pathlib
                import sys
                path = pathlib.Path(sys.argv[1])
                spec = importlib.util.spec_from_file_location("iam002_local", path)
                module = importlib.util.module_from_spec(spec)
                spec.loader.exec_module(module)
                print(module.local_merchant_v32_upgrade_statement(), end="")
                """, script.toString())
            .redirectErrorStream(true)
            .start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor()).as(output).isZero();
        return output;
    }

    private static String filesystemLocation(Path path) {
        return "filesystem:" + path.toAbsolutePath().normalize();
    }

    private static Path locateBackendRoot() {
        Path current = Path.of("").toAbsolutePath().normalize();
        while (current != null) {
            Path candidate = current.resolve("backend/modules/identity/persistence-postgres");
            if (Files.isDirectory(candidate)) {
                return current.resolve("backend");
            }
            candidate = current.resolve("modules/identity/persistence-postgres");
            if (Files.isDirectory(candidate)) {
                return current;
            }
            current = current.getParent();
        }
        throw new IllegalStateException("Cannot locate backend root for Flyway migration tests");
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static void execute(String sql) throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static void assertEventuallyEquals(long expected, String sql, Duration timeout)
        throws Exception {
        long deadline = System.nanoTime() + timeout.toNanos();
        long actual;
        do {
            actual = singleLong(sql);
            if (actual == expected) {
                return;
            }
            Thread.sleep(25);
        } while (System.nanoTime() < deadline);
        assertThat(actual).isEqualTo(expected);
    }

    private static long singleLong(String sql) throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            assertThat(rows.next()).isTrue();
            return rows.getLong(1);
        }
    }

    private static List<String> strings(String sql) throws SQLException {
        var values = new ArrayList<String>();
        try (Connection connection = connection(); Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(sql)) {
            while (rows.next()) {
                values.add(rows.getString(1));
            }
        }
        return values;
    }

    private static String currentSuccessfulVersion() throws SQLException {
        try (Connection connection = connection(); Statement statement = connection.createStatement();
             ResultSet row = statement.executeQuery("""
                 SELECT version FROM flyway_schema_history
                  WHERE success ORDER BY installed_rank DESC LIMIT 1
                 """)) {
            assertThat(row.next()).isTrue();
            return row.getString(1);
        }
    }

    private static void executeUnchecked(String sql) {
        try {
            execute(sql);
        } catch (SQLException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static void insertTenantFixtures() throws SQLException {
        execute("""
            INSERT INTO iam_tenant(id, tenant_code, tenant_name, tenant_type, status, account_domain)
            VALUES
              (32002, 'mch-runtime-merchant-a', 'MCH Runtime Merchant A',
               'DIRECT_MERCHANT', 'ACTIVE', 'MERCHANT'),
              (32003, 'mch-runtime-agent', 'MCH Runtime Agent',
               'AGENT', 'ACTIVE', 'AGENT'),
              (32004, 'mch-runtime-merchant-b', 'MCH Runtime Merchant B',
               'DIRECT_MERCHANT', 'ACTIVE', 'MERCHANT');
            """);
    }

    private static void insertV39PlatformMenuFixture() throws SQLException {
        execute("""
            INSERT INTO iam_tenant(
                id,tenant_code,tenant_name,tenant_type,status,account_domain)
            VALUES (40001,'v40-platform','V40 Platform','PLATFORM','ACTIVE','PLATFORM');
            INSERT INTO iam_menu(
                id,tenant_id,parent_id,menu_type,menu_name,route_name,route_path,
                component_path,sort_order,auth_code,status,meta_json,system_managed)
            VALUES
              (40002,40001,NULL,'DIRECTORY','Merchant Management','MerchantManagement',
               '/merchant',NULL,200,NULL,'ACTIVE',
               '{"title":"merchant.title","icon":"lucide:store"}'::jsonb,true),
              (40003,40001,40002,'PAGE','Merchant List','MerchantList','/merchant/list',
               '/merchant/list',201,'merchant:view','ACTIVE',
               '{"title":"merchant.list.title"}'::jsonb,true),
              (40004,40001,40003,'BUTTON','Merchant Review','MerchantReview',NULL,NULL,
               202,'merchant:review','ACTIVE','{"title":"merchant.review"}'::jsonb,true),
              (40005,40001,40003,'BUTTON','Merchant Disable','MerchantDisable',NULL,NULL,
               203,'merchant:disable','ACTIVE','{"title":"merchant.disable"}'::jsonb,true),
              (40006,40001,40003,'BUTTON','Merchant Enable','MerchantEnable',NULL,NULL,
               204,'merchant:enable','ACTIVE','{"title":"merchant.enable"}'::jsonb,true),
              (40007,40001,40003,'BUTTON','Merchant Terminate','MerchantTerminate',NULL,NULL,
               205,'merchant:terminate','ACTIVE','{"title":"merchant.terminate"}'::jsonb,true),
              (40008,40001,40003,'BUTTON','Merchant Edit','MerchantEdit',NULL,NULL,
               206,'merchant:amend','ACTIVE','{"title":"merchant.edit"}'::jsonb,true),
              (40009,40001,40003,'BUTTON','Merchant Create','MerchantCreate',NULL,NULL,
               207,'merchant:create','ACTIVE','{"title":"merchant.create"}'::jsonb,true);
            """);
    }

    private static String v40ButtonTitles() {
        return """
            SELECT route_name || ':' || (meta_json->>'title')
              FROM iam_menu
             WHERE tenant_id=40001 AND menu_type='BUTTON'
             ORDER BY route_name
            """;
    }

    private static void insertActorFixture() throws SQLException {
        execute("""
            INSERT INTO iam_user(
                id, idp_issuer, idp_subject, display_name, status, account_domain,
                identity_version)
            VALUES (32409, 'mch-v32-test', 'merchant-actor', 'Merchant Actor',
                    'ACTIVE', 'MERCHANT', 0);
            INSERT INTO iam_membership(
                id, tenant_id, user_id, status, account_domain,
                permission_version, session_version)
            VALUES (32410, 32002, 32409, 'ACTIVE', 'MERCHANT', 0, 0);
            """);
    }

    private static void insertV41HistoricalAmendmentEvidence(int replacementCount)
        throws SQLException {
        insertTenantFixtures();
        insertActorFixture();
        execute("""
            INSERT INTO iam_user(
                id,idp_issuer,idp_subject,display_name,status,account_domain,identity_version)
            VALUES (32411,'mch-v42-test','merchant-reviewer','Merchant Reviewer',
                    'ACTIVE','MERCHANT',0);
            INSERT INTO iam_membership(
                id,tenant_id,user_id,status,account_domain,permission_version,session_version)
            VALUES (32412,32002,32411,'ACTIVE','MERCHANT',0,0);
            """);
        insertMerchant(34200, 32002, "MCH_V41_HISTORY", "42");
        execute("""
            UPDATE merchant
               SET status='ACTIVE',status_reason_code='PROFILE_VERIFIED',row_version=1
             WHERE id=34200;
            INSERT INTO merchant_protected_nonce(purpose,key_id,algorithm,nonce)
            VALUES ('LEGAL_ID_AEAD','mch-legal-id-aead-v1','AES-256-GCM',
                    decode(repeat('43',12),'hex'));
            INSERT INTO merchant_amendment(
                id,merchant_id,target_tenant_id,status,row_version,
                origin_merchant_version,origin_status,author_tenant_id,author_membership_id,
                display_name,brand_name,authentication_type,merchant_type_code,industry_code,
                legal_name,registration_country,registered_address,operating_address,
                legal_person_name,contact_email,contact_phone,legal_id_type_code,
                legal_id_valid_from,legal_id_valid_to,remarks,
                registration_mode,registration_number_masked,registration_fingerprint,
                registration_search_key_id,registration_ciphertext,registration_nonce,
                registration_auth_tag,registration_aead_key_id,registration_aead_algorithm,
                legal_id_mode,legal_id_no_masked,legal_id_ciphertext,legal_id_nonce,
                legal_id_auth_tag,legal_id_aead_key_id,legal_id_aead_algorithm,
                registration_fingerprint_algorithm,registration_normalization_version,
                registration_nonce_purpose,legal_id_aad_scheme_version,
                legal_id_protection_version,legal_id_nonce_purpose)
            SELECT 34220,id,tenant_id,'PENDING_REVIEW',0,row_version,status,32002,32410,
                   display_name,'Historical Brand','ENTERPRISE','PLATFORM','RETAIL',
                   legal_name,'SG','Registered address','Operating address',
                   'Historical Legal Person','history@example.test','+6591234567','PASSPORT',
                   DATE '2020-01-01',DATE '2030-01-01','historical pending amendment',
                   'RETAIN',registration_number_masked,registration_fingerprint,
                   registration_search_key_id,registration_ciphertext,registration_nonce,
                   registration_auth_tag,registration_aead_key_id,registration_aead_algorithm,
                   'REPLACE','********5678',decode('010203','hex'),
                   decode(repeat('43',12),'hex'),decode(repeat('ee',16),'hex'),
                   'mch-legal-id-aead-v1','AES-256-GCM',
                   'HMAC-SHA-256',1,'REGISTRATION_AEAD',1,1,'LEGAL_ID_AEAD'
              FROM merchant WHERE id=34200;
            """);

        String[] kinds = {
            "BRAND_LOGO", "BUSINESS_LICENSE", "LEGAL_ID_FRONT",
            "LEGAL_ID_BACK", "LEGAL_ID_HOLDING"
        };
        for (int index = 0; index < kinds.length; index++) {
            long documentId = 34210L + index;
            String nonceByte = "%02x".formatted(0x51 + index);
            execute("""
                INSERT INTO merchant_protected_nonce(purpose,key_id,algorithm,nonce)
                VALUES ('DOCUMENT_AEAD','mch-document-aead-v1','AES-256-GCM',
                        decode(repeat('%s',12),'hex'));
                INSERT INTO merchant_document(
                    id,target_tenant_id,actor_tenant_id,actor_membership_id,kind,
                    media_type,width,height,size_bytes,ciphertext,nonce,auth_tag,
                    aead_key_id,aead_algorithm,aad_scheme_version,protection_version,
                    expires_at,attachment_scope,merchant_id,attached_at,nonce_purpose)
                VALUES (%d,32002,32002,32410,'%s','image/png',1,1,1,
                        decode('01','hex'),decode(repeat('%s',12),'hex'),
                        decode(repeat('ee',16),'hex'),'mch-document-aead-v1','AES-256-GCM',
                        1,1,statement_timestamp()+interval '30 minutes','MERCHANT',34200,
                        statement_timestamp(),'DOCUMENT_AEAD');
                INSERT INTO merchant_document_binding(merchant_id,kind,document_id)
                VALUES (34200,'%s',%d);
                """.formatted(nonceByte, documentId, kinds[index], nonceByte,
                    kinds[index], documentId));
        }
        for (int index = 0; index < replacementCount; index++) {
            long documentId = 34230L + index;
            String nonceByte = "%02x".formatted(0x61 + index);
            execute("""
                INSERT INTO merchant_protected_nonce(purpose,key_id,algorithm,nonce)
                VALUES ('DOCUMENT_AEAD','mch-document-aead-v1','AES-256-GCM',
                        decode(repeat('%s',12),'hex'));
                INSERT INTO merchant_document(
                    id,target_tenant_id,actor_tenant_id,actor_membership_id,kind,
                    media_type,width,height,size_bytes,ciphertext,nonce,auth_tag,
                    aead_key_id,aead_algorithm,aad_scheme_version,protection_version,
                    expires_at,attachment_scope,merchant_id,amendment_id,attached_at,
                    nonce_purpose)
                VALUES (%d,32002,32002,32410,'%s','image/png',1,1,1,
                        decode('01','hex'),decode(repeat('%s',12),'hex'),
                        decode(repeat('ee',16),'hex'),'mch-document-aead-v1','AES-256-GCM',
                        1,1,statement_timestamp()+interval '30 minutes','AMENDMENT',34200,
                        34220,statement_timestamp(),'DOCUMENT_AEAD');
                """.formatted(nonceByte, documentId, kinds[index], nonceByte));
        }
    }

    private static void insertMerchant(long id, long tenantId, String code, String nonceByte)
        throws SQLException {
        insertMerchantWithFingerprint(id, tenantId, code, nonceByte, "ab");
    }

    private static void insertMerchantWithFingerprint(
        long id, long tenantId, String code, String nonceByte, String fingerprintByte)
        throws SQLException {
        if (singleLong("SELECT count(*) FROM pg_class WHERE oid="
            + "to_regclass('public.merchant_protected_nonce')") == 1L) {
            execute("""
                INSERT INTO merchant_protected_nonce(purpose,key_id,algorithm,nonce)
                VALUES ('REGISTRATION_AEAD','mch-registration-aead-v1','AES-256-GCM',
                        decode(repeat('%s',12),'hex'))
                ON CONFLICT DO NOTHING
                """.formatted(nonceByte));
        }
        execute("""
            INSERT INTO merchant(
                id, tenant_id, account_domain, merchant_code, legal_name, display_name,
                registration_country, registration_number_masked,
                registration_fingerprint, registration_search_key_id,
                registration_fingerprint_algorithm, registration_normalization_version,
                registration_ciphertext, registration_nonce, registration_auth_tag,
                registration_aead_key_id, registration_aead_algorithm, status,
                row_version, submitted_at)
            VALUES (%d, %d, 'MERCHANT', '%s', 'Example Legal Name', 'Example Display',
                    'SG', '********1234', decode(repeat('%s',32),'hex'),
                    'mch-registration-search-v1', 'HMAC-SHA-256', 1,
                    decode('010203','hex'), decode(repeat('%s',12),'hex'),
                    decode(repeat('ee',16),'hex'), 'mch-registration-aead-v1',
                    'AES-256-GCM', 'PENDING_REVIEW', 0, now())
            """.formatted(id, tenantId, code, fingerprintByte, nonceByte));
    }
}
