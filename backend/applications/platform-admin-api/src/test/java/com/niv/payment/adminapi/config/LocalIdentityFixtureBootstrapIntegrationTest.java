package com.niv.payment.adminapi.config;

import com.niv.payment.adminapi.PostgresFlywayTestSupport;
import com.niv.payment.merchant.core.MerchantActor;
import com.niv.payment.merchant.core.MerchantOnboardingModels;
import com.niv.payment.merchant.core.MerchantOnboardingService;
import com.niv.payment.merchant.persistence.JooqMerchantRepository;
import com.niv.payment.merchant.persistence.crypto.JdkMerchantCryptography;
import com.niv.payment.merchant.persistence.crypto.JdkMerchantOnboardingCryptography;
import com.niv.payment.merchant.persistence.crypto.MerchantOnboardingKeyRing;
import com.niv.payment.merchant.persistence.crypto.ThreePurposeKeyRing;
import com.niv.payment.permission.domain.AccountDomain;
import com.niv.payment.permission.persistence.repository.JooqCredentialRepository;
import org.flywaydb.core.Flyway;
import org.jooq.Record;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.annotation.Profile;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import javax.sql.DataSource;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class LocalIdentityFixtureBootstrapIntegrationTest {
    private static final String FIXTURE_LOGIN_INPUT = "local-test-password";
    private static final String[] LEGACY_FIXTURE_TABLES = {
        "iam_tenant",
        "iam_department",
        "iam_user",
        "iam_membership",
        "iam_authentication_credential",
        "iam_role",
        "iam_membership_role",
        "iam_role_grant",
        "iam_grant_dimension",
        "iam_menu",
        "iam_role_menu"
    };

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse(
        "postgres:18.4-alpine@sha256:9a8afca54e7861fd90fab5fdf4c42477a6b1cb7d293595148e674e0a3181de15")
        .asCompatibleSubstituteFor("postgres"))
        .withDatabaseName("payment_platform")
        .withUsername("payment_dev")
        .withPassword("payment_dev");

    private DataSource dataSource;
    private JdbcTemplate jdbc;

    @BeforeEach
    void migrateProductionSchema() {
        Flyway flyway = PostgresFlywayTestSupport.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .cleanDisabled(false)
            .load();
        flyway.clean();
        flyway.migrate();

        DriverManagerDataSource configuredDataSource = new DriverManagerDataSource();
        configuredDataSource.setDriverClassName("org.postgresql.Driver");
        configuredDataSource.setUrl(POSTGRES.getJdbcUrl());
        configuredDataSource.setUsername(POSTGRES.getUsername());
        configuredDataSource.setPassword(POSTGRES.getPassword());
        dataSource = configuredDataSource;
        jdbc = new JdbcTemplate(dataSource);
    }

    @Test
    void emptyDatabaseCreatesAndValidatesTheCompleteLocalFixture() throws Exception {
        runBootstrap(FIXTURE_LOGIN_INPUT);

        assertCompleteFixture();
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM iam_menu WHERE route_name='SystemDictionaryData'
            """, Long.class)).isZero();
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM iam_menu
             WHERE tenant_id IN (2,3)
               AND route_name IN ('SystemDictionaryDataIndex','DictionaryDataView')
            """, Long.class)).isZero();
        String hash = jdbc.queryForObject(
            "SELECT password_hash FROM iam_authentication_credential WHERE user_id = 100",
            String.class);
        assertThat(new BCryptPasswordEncoder(10).matches(FIXTURE_LOGIN_INPUT, hash)).isTrue();
    }

    @Test
    void repeatedBootstrapIsStrictlyIdempotent() throws Exception {
        runBootstrap(FIXTURE_LOGIN_INPUT);
        String firstCredentialState = credentialState();
        String firstFixtureState = fixtureVersionState();
        String firstDictionaryState = dictionaryState();

        runBootstrap(FIXTURE_LOGIN_INPUT);

        assertCompleteFixture();
        assertThat(credentialState()).isEqualTo(firstCredentialState);
        assertThat(fixtureVersionState()).isEqualTo(firstFixtureState);
        assertThat(dictionaryState()).isEqualTo(firstDictionaryState);
    }

    @Test
    void freshLocalMerchantReceivesOnlyTheFiniteSelfServiceCapability() throws Exception {
        runBootstrap(FIXTURE_LOGIN_INPUT);

        assertLocalMerchantCapability();
        assertThat(jdbc.queryForObject("""
            SELECT count(*)
              FROM iam_role_grant grant_row
              JOIN iam_permission permission ON permission.id=grant_row.permission_id
             WHERE grant_row.tenant_id=3 AND grant_row.role_id=3200
               AND permission.permission_code LIKE 'merchant:%'
            """, Long.class)).isZero();
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM iam_menu
             WHERE tenant_id=3
               AND route_name IN ('MerchantProfile','MerchantSubmit','MerchantResubmit')
            """, Long.class)).isZero();
    }

    @Test
    void repeatedBootstrapDoesNotRenewOrRewriteMerchantCapability() throws Exception {
        runBootstrap(FIXTURE_LOGIN_INPUT);
        String firstState = localMerchantCapabilityState();

        runBootstrap(FIXTURE_LOGIN_INPUT);

        assertLocalMerchantCapability();
        assertThat(localMerchantCapabilityState()).isEqualTo(firstState);
    }

    @Test
    void partialMerchantCapabilityFailsClosedWithoutRepair() throws Exception {
        runBootstrap(FIXTURE_LOGIN_INPUT);
        jdbc.update("""
            DELETE FROM iam_role_grant grant_row
             USING iam_permission permission
             WHERE grant_row.permission_id=permission.id
               AND grant_row.tenant_id=2 AND grant_row.role_id=2200
               AND permission.permission_code='merchant:resubmit'
            """);
        String partialState = localMerchantCapabilityState();

        assertThatThrownBy(() -> runBootstrap(FIXTURE_LOGIN_INPUT))
            .hasStackTraceContaining("merchant self-service capability is incomplete or modified");

        assertThat(localMerchantCapabilityState()).isEqualTo(partialState);
    }

    @Test
    void freshLocalPlatformReceivesFiniteMerchantControlPlaneCapability() throws Exception {
        runBootstrap(FIXTURE_LOGIN_INPUT);

        assertLocalPlatformMerchantCapability();
    }

    @Test
    void repeatedBootstrapDoesNotRenewOrRewritePlatformMerchantCapability() throws Exception {
        runBootstrap(FIXTURE_LOGIN_INPUT);
        String firstState = localPlatformMerchantCapabilityState();

        runBootstrap(FIXTURE_LOGIN_INPUT);

        assertLocalPlatformMerchantCapability();
        assertThat(localPlatformMerchantCapabilityState()).isEqualTo(firstState);
    }

    @Test
    void partialPlatformMerchantCapabilityFailsClosedWithoutRepair() throws Exception {
        runBootstrap(FIXTURE_LOGIN_INPUT);
        jdbc.update("DELETE FROM iam_menu WHERE tenant_id=1 AND route_name='MerchantTerminate'");
        String partialState = localPlatformMerchantCapabilityState();

        assertThatThrownBy(() -> runBootstrap(FIXTURE_LOGIN_INPUT))
            .hasStackTraceContaining("platform merchant capability is incomplete or modified");

        assertThat(localPlatformMerchantCapabilityState()).isEqualTo(partialState);
    }

    @Test
    void freshLocalFixtureClosesMch003ReviewerAndEligibleTenantRuntime() throws Exception {
        runBootstrap(FIXTURE_LOGIN_INPUT);

        assertMch003LocalRuntime();
    }

    @Test
    void repeatedBootstrapDoesNotRewriteMch003Runtime() throws Exception {
        runBootstrap(FIXTURE_LOGIN_INPUT);
        String firstState = mch003LocalRuntimeState();

        runBootstrap(FIXTURE_LOGIN_INPUT);

        assertMch003LocalRuntime();
        assertThat(mch003LocalRuntimeState()).isEqualTo(firstState);
    }

    @Test
    void iam002LocalRestartConvergesReviewerWithoutRewritingPersistedMerchant() throws Exception {
        runBootstrap(FIXTURE_LOGIN_INPUT);
        preparePersistedIam002LocalStateWithoutReviewer();
        String merchantBefore = jdbc.queryForObject(
            "SELECT to_jsonb(merchant)::text FROM merchant WHERE tenant_id=2", String.class);
        String mainMembershipBefore = jdbc.queryForObject("""
            SELECT to_jsonb(membership)::text FROM iam_membership membership
             WHERE tenant_id=1 AND id=1000
            """, String.class);
        String existingCredentialsBefore = existingLocalAdministratorCredentialState();

        runBootstrap(FIXTURE_LOGIN_INPUT, "iam002-local");

        assertPersistedReviewerAndCandidate();
        assertThat(jdbc.queryForObject(
            "SELECT to_jsonb(merchant)::text FROM merchant WHERE tenant_id=2", String.class))
            .isEqualTo(merchantBefore);
        assertThat(jdbc.queryForObject("""
            SELECT to_jsonb(membership)::text FROM iam_membership membership
             WHERE tenant_id=1 AND id=1000
            """, String.class)).isEqualTo(mainMembershipBefore);
        assertThat(existingLocalAdministratorCredentialState()).isEqualTo(existingCredentialsBefore);
        String convergedState = mch003LocalRuntimeState();

        runBootstrap(FIXTURE_LOGIN_INPUT, "iam002-local");

        assertPersistedReviewerAndCandidate();
        assertThat(mch003LocalRuntimeState()).isEqualTo(convergedState);
        assertThat(jdbc.queryForObject(
            "SELECT to_jsonb(merchant)::text FROM merchant WHERE tenant_id=2", String.class))
            .isEqualTo(merchantBefore);
    }

    @Test
    void iam002LocalRestartSynchronizesReviewerWhenBootstrapPasswordRotates() throws Exception {
        runBootstrap(FIXTURE_LOGIN_INPUT);
        preparePersistedIam002LocalStateWithoutReviewer();
        runBootstrap(FIXTURE_LOGIN_INPUT, "iam002-local");
        String existingCredentialsBefore = existingLocalAdministratorCredentialState();
        long credentialVersionBefore = jdbc.queryForObject("""
            SELECT row_version FROM iam_authentication_credential WHERE user_id=101
            """, Long.class);
        long membershipVersionBefore = jdbc.queryForObject("""
            SELECT row_version FROM iam_membership WHERE tenant_id=1 AND id=1001
            """, Long.class);
        long sessionVersionBefore = jdbc.queryForObject("""
            SELECT session_version FROM iam_membership WHERE tenant_id=1 AND id=1001
            """, Long.class);

        runBootstrap("rotated-local-bootstrap-password", "iam002-local");

        String reviewerHash = jdbc.queryForObject("""
            SELECT password_hash FROM iam_authentication_credential WHERE user_id=101
            """, String.class);
        assertThat(new BCryptPasswordEncoder(10)
            .matches("rotated-local-bootstrap-password", reviewerHash)).isTrue();
        assertThat(new BCryptPasswordEncoder(10).matches(FIXTURE_LOGIN_INPUT, reviewerHash)).isFalse();
        assertThat(jdbc.queryForObject("""
            SELECT row_version FROM iam_authentication_credential WHERE user_id=101
            """, Long.class)).isEqualTo(credentialVersionBefore + 1);
        assertThat(jdbc.queryForObject("""
            SELECT row_version FROM iam_membership WHERE tenant_id=1 AND id=1001
            """, Long.class)).isEqualTo(membershipVersionBefore + 1);
        assertThat(jdbc.queryForObject("""
            SELECT session_version FROM iam_membership WHERE tenant_id=1 AND id=1001
            """, Long.class)).isEqualTo(sessionVersionBefore + 1);
        assertThat(existingLocalAdministratorCredentialState()).isEqualTo(existingCredentialsBefore);
        String synchronizedState = mch003LocalRuntimeState();

        runBootstrap("rotated-local-bootstrap-password", "iam002-local");

        assertThat(mch003LocalRuntimeState()).isEqualTo(synchronizedState);
        assertThat(existingLocalAdministratorCredentialState()).isEqualTo(existingCredentialsBefore);
    }

    @Test
    void iam002LocalRestartAfterPlatformCreatePreservesMerchantAndSeedsReplacementCandidate()
        throws Exception {
        runBootstrap(FIXTURE_LOGIN_INPUT);
        MerchantOnboardingService onboarding = onboardingService();
        MerchantActor reviewer = reviewerActor();
        var created = consumeInitialCandidate(onboarding, reviewer);
        String merchantBefore = merchantAggregateState(created.merchantId());

        runBootstrap(FIXTURE_LOGIN_INPUT, "iam002-local");

        assertThat(merchantAggregateState(created.merchantId())).isEqualTo(merchantBefore);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM iam_tenant tenant
             WHERE tenant.tenant_code='local-merchant-candidate-2'
               AND tenant.tenant_name='Local Merchant Candidate 2'
               AND tenant.tenant_type='DIRECT_MERCHANT'
               AND tenant.account_domain='MERCHANT' AND tenant.status='ACTIVE'
               AND NOT EXISTS (SELECT 1 FROM iam_department WHERE tenant_id=tenant.id)
               AND NOT EXISTS (SELECT 1 FROM iam_membership WHERE tenant_id=tenant.id)
               AND NOT EXISTS (SELECT 1 FROM iam_role WHERE tenant_id=tenant.id)
               AND NOT EXISTS (SELECT 1 FROM merchant WHERE tenant_id=tenant.id)
            """, Long.class)).isOne();
        assertThat(onboarding.eligibleTenants(reviewer,
            new MerchantOnboardingModels.EligibleTenantQuery("local-merchant", null, 1, 20)).items())
            .singleElement().satisfies(candidate -> {
                assertThat(candidate.tenantCode()).isEqualTo("local-merchant-candidate-2");
                assertThat(candidate.tenantId()).isNotEqualTo(4000L);
            });
        assertReviewerCredentialIsLoginCapable();
        String replacementBefore = candidateLineageState();

        runBootstrap(FIXTURE_LOGIN_INPUT, "iam002-local");

        assertThat(merchantAggregateState(created.merchantId())).isEqualTo(merchantBefore);
        assertThat(candidateLineageState()).isEqualTo(replacementBefore);
    }

    @Test
    void localProfileRestartsAcrossConsumedCandidatesAndSeedsCandidateTen() throws Exception {
        runBootstrap(FIXTURE_LOGIN_INPUT);
        MerchantOnboardingService onboarding = onboardingService();
        MerchantActor reviewer = reviewerActor();
        Map<Long, String> merchantStates = new LinkedHashMap<>();

        for (int ordinal = 1; ordinal <= 9; ordinal++) {
            int expectedOrdinal = ordinal;
            var eligible = onboarding.eligibleTenants(reviewer,
                new MerchantOnboardingModels.EligibleTenantQuery("local-merchant", null, 1, 20)).items();
            assertThat(eligible).singleElement().satisfies(candidate -> {
                String expectedCode = expectedOrdinal == 1
                    ? "local-merchant-candidate"
                    : "local-merchant-candidate-" + expectedOrdinal;
                assertThat(candidate.tenantCode()).isEqualTo(expectedCode);
            });
            long targetTenantId = eligible.getFirst().tenantId();
            var created = consumeCandidate(onboarding, reviewer, targetTenantId, ordinal);
            merchantStates.put(created.merchantId(), merchantAggregateState(created.merchantId()));

            runBootstrap(FIXTURE_LOGIN_INPUT, "local");
        }

        assertThat(onboarding.eligibleTenants(reviewer,
            new MerchantOnboardingModels.EligibleTenantQuery("local-merchant", null, 1, 20)).items())
            .singleElement().satisfies(candidate -> {
                assertThat(candidate.tenantCode()).isEqualTo("local-merchant-candidate-10");
                assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM iam_department WHERE tenant_id=?
                    """, Long.class, candidate.tenantId())).isZero();
                assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM iam_membership WHERE tenant_id=?
                    """, Long.class, candidate.tenantId())).isZero();
                assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM iam_role WHERE tenant_id=?
                    """, Long.class, candidate.tenantId())).isZero();
                assertThat(jdbc.queryForObject("""
                    SELECT count(*) FROM merchant WHERE tenant_id=?
                    """, Long.class, candidate.tenantId())).isZero();
            });
        String lineageBeforeRepeat = candidateLineageState();

        runBootstrap(FIXTURE_LOGIN_INPUT, "local");

        assertThat(candidateLineageState()).isEqualTo(lineageBeforeRepeat);
        merchantStates.forEach((merchantId, state) ->
            assertThat(merchantAggregateState(merchantId)).isEqualTo(state));
    }

    @Test
    void iam002LocalRestartRejectsOutOfRangeOrNonCanonicalCandidateOrdinals() throws Exception {
        runBootstrap(FIXTURE_LOGIN_INPUT);
        List<String> hostileCodes = List.of(
            "local-merchant-candidate-02",
            "local-merchant-candidate-1",
            "local-merchant-candidate-1000000",
            "local-merchant-candidate-not-a-number");

        for (int index = 0; index < hostileCodes.size(); index++) {
            long tenantId = 4100L + index;
            String code = hostileCodes.get(index);
            jdbc.update("""
                INSERT INTO iam_tenant(id,tenant_code,tenant_name,tenant_type,status,account_domain)
                VALUES(?,?,?,'DIRECT_MERCHANT','ACTIVE','MERCHANT')
                """, tenantId, code, "Hostile Candidate " + index);
            String hostileState = candidateLineageState();

            assertThatThrownBy(() -> runBootstrap(FIXTURE_LOGIN_INPUT, "iam002-local"))
                .hasStackTraceContaining("candidate lineage is incomplete or modified");

            assertThat(candidateLineageState()).isEqualTo(hostileState);
            jdbc.update("DELETE FROM iam_tenant WHERE id=?", tenantId);
        }
    }

    @Test
    void iam002LocalRestartRejectsConsumedCandidateWithoutExactCreateEvidence() throws Exception {
        runBootstrap(FIXTURE_LOGIN_INPUT);
        var created = consumeInitialCandidate(onboardingService(), reviewerActor());
        jdbc.execute("ALTER TABLE merchant_audit_event DISABLE TRIGGER trg_merchant_audit_append_only");
        try {
            jdbc.update("DELETE FROM merchant_audit_event WHERE merchant_id=? AND action_code='CREATE'",
                created.merchantId());
        } finally {
            jdbc.execute("ALTER TABLE merchant_audit_event ENABLE TRIGGER trg_merchant_audit_append_only");
        }
        String hostileState = candidateLineageState();

        assertThatThrownBy(() -> runBootstrap(FIXTURE_LOGIN_INPUT, "iam002-local"))
            .hasStackTraceContaining("candidate lineage is incomplete or modified");

        assertThat(candidateLineageState()).isEqualTo(hostileState);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM iam_tenant WHERE tenant_code='local-merchant-candidate-2'
            """, Long.class)).isZero();
    }

    @Test
    void iam002LocalRestartRejectsReplacementCandidateWithIamAdministrationRows() throws Exception {
        runBootstrap(FIXTURE_LOGIN_INPUT);
        consumeInitialCandidate(onboardingService(), reviewerActor());
        jdbc.update("""
            INSERT INTO iam_tenant(id,tenant_code,tenant_name,tenant_type,status,account_domain)
            VALUES(4100,'local-merchant-candidate-2','Local Merchant Candidate 2',
                   'DIRECT_MERCHANT','ACTIVE','MERCHANT')
            """);
        jdbc.update("""
            INSERT INTO iam_department(id,tenant_id,department_code,department_name,status,system_managed)
            VALUES(4101,4100,'hostile-admin','Hostile Admin','ACTIVE',false)
            """);
        String hostileState = candidateLineageState();

        assertThatThrownBy(() -> runBootstrap(FIXTURE_LOGIN_INPUT, "iam002-local"))
            .hasStackTraceContaining("candidate lineage is incomplete or modified");

        assertThat(candidateLineageState()).isEqualTo(hostileState);
    }

    @Test
    void iam002LocalRestartRejectsPartialReviewerWithoutRepairingIt() throws Exception {
        runBootstrap(FIXTURE_LOGIN_INPUT);
        preparePersistedIam002LocalStateWithoutReviewer();
        jdbc.update("""
            INSERT INTO iam_user(id,idp_issuer,idp_subject,display_name,status,remark,account_domain)
            VALUES(101,'local:platform','reviewer@platform.localhost','Partial Reviewer',
                   'ACTIVE','unexpected partial state','PLATFORM')
            """);
        String merchantBefore = jdbc.queryForObject(
            "SELECT to_jsonb(merchant)::text FROM merchant WHERE tenant_id=2", String.class);

        assertThatThrownBy(() -> runBootstrap(FIXTURE_LOGIN_INPUT, "iam002-local"))
            .hasStackTraceContaining("persisted MCH-003 reviewer is incomplete or modified");

        assertThat(jdbc.queryForObject(
            "SELECT display_name FROM iam_user WHERE id=101", String.class))
            .isEqualTo("Partial Reviewer");
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM iam_membership WHERE id=1001", Long.class)).isZero();
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM iam_tenant WHERE id=4000", Long.class)).isZero();
        assertThat(jdbc.queryForObject(
            "SELECT to_jsonb(merchant)::text FROM merchant WHERE tenant_id=2", String.class))
            .isEqualTo(merchantBefore);
    }

    @Test
    void partialMch003GrantFailsClosedWithoutRepair() throws Exception {
        runBootstrap(FIXTURE_LOGIN_INPUT);
        jdbc.update("""
            DELETE FROM iam_role_grant grant_row
             USING iam_permission permission
             WHERE grant_row.permission_id=permission.id
               AND grant_row.tenant_id=1 AND grant_row.role_id=2000
               AND permission.permission_code='merchant:document:view'
            """);
        String partialState = mch003LocalRuntimeState();

        assertThatThrownBy(() -> runBootstrap(FIXTURE_LOGIN_INPUT))
            .hasStackTraceContaining("platform merchant capability is incomplete or modified");

        assertThat(mch003LocalRuntimeState()).isEqualTo(partialState);
    }

    @Test
    void modifiedReviewerFailsClosedWithoutRepair() throws Exception {
        runBootstrap(FIXTURE_LOGIN_INPUT);
        jdbc.update("UPDATE iam_membership SET status='DISABLED' WHERE tenant_id=1 AND id=1001");
        String modifiedState = mch003LocalRuntimeState();

        assertThatThrownBy(() -> runBootstrap(FIXTURE_LOGIN_INPUT))
            .hasStackTraceContaining("MCH-003 reviewer fixture is incomplete or modified");

        assertThat(mch003LocalRuntimeState()).isEqualTo(modifiedState);
    }

    @Test
    void modifiedEligibleTenantFailsClosedWithoutRepair() throws Exception {
        runBootstrap(FIXTURE_LOGIN_INPUT);
        jdbc.update("UPDATE iam_tenant SET status='DISABLED' WHERE id=4000");
        String modifiedState = mch003LocalRuntimeState();

        assertThatThrownBy(() -> runBootstrap(FIXTURE_LOGIN_INPUT))
            .hasStackTraceContaining("MCH-003 Merchant Tenant fixture is incomplete or modified");

        assertThat(mch003LocalRuntimeState()).isEqualTo(modifiedState);
    }

    @Test
    void canonicalV30AndV31MenuTombstonesAndHistoricalRoleLinksRemainAccepted() throws Exception {
        runBootstrap(FIXTURE_LOGIN_INPUT);
        jdbc.update("""
            INSERT INTO iam_menu(
                id,tenant_id,parent_id,menu_type,menu_name,route_name,route_path,
                component_path,redirect_path,display_permission_id,sort_order,auth_code,
                status,meta_json,system_managed,row_version,deleted_at
            ) VALUES
              (6043,1,6000,'PAGE','Dictionary Data Detail','SystemDictionaryData',
               '/system/dict/data/type/:dictType','/system/dict/data/list',NULL,3029,161,NULL,
               'DISABLED','{"title":"system.dictData.title","hideInMenu":true,"activePath":"/system/dict"}'::jsonb,
               true,1,now()),
              (16041,2,16000,'PAGE','Dictionary Data','SystemDictionaryDataIndex',
               '/system/dict/data','/system/dict/data/list',NULL,3029,160,NULL,
               'DISABLED','{"title":"system.dictData.title","icon":"lucide:list-tree"}'::jsonb,
               true,1,now()),
              (16042,2,16000,'PAGE','Dictionary Data Detail','SystemDictionaryData',
               '/system/dict/data/type/:dictType','/system/dict/data/list',NULL,3029,161,NULL,
               'DISABLED','{"title":"system.dictData.title","hideInMenu":true,"activePath":"/system/dict/data"}'::jsonb,
               true,1,now()),
              (16043,2,16041,'BUTTON','View Dictionary Data','DictionaryDataView',
               NULL,NULL,NULL,NULL,162,'dictionary-data:view',
               'DISABLED','{"title":"system.dictData.permission.view"}'::jsonb,
               true,1,now()),
              (26041,3,26000,'PAGE','Dictionary Data','SystemDictionaryDataIndex',
               '/system/dict/data','/system/dict/data/list',NULL,3029,160,NULL,
               'DISABLED','{"title":"system.dictData.title","icon":"lucide:list-tree"}'::jsonb,
               true,1,now()),
              (26042,3,26000,'PAGE','Dictionary Data Detail','SystemDictionaryData',
               '/system/dict/data/type/:dictType','/system/dict/data/list',NULL,3029,161,NULL,
               'DISABLED','{"title":"system.dictData.title","hideInMenu":true,"activePath":"/system/dict/data"}'::jsonb,
               true,1,now()),
              (26043,3,26041,'BUTTON','View Dictionary Data','DictionaryDataView',
               NULL,NULL,NULL,NULL,162,'dictionary-data:view',
               'DISABLED','{"title":"system.dictData.permission.view"}'::jsonb,
               true,1,now());
            INSERT INTO iam_role_menu(tenant_id,role_id,menu_id)
            VALUES (1,2000,6043),
                   (2,2200,16041),(2,2200,16042),
                   (3,3200,26041),(3,3200,26042);
            """);

        runBootstrap(FIXTURE_LOGIN_INPUT);

        assertCompleteFixture();
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM iam_menu
             WHERE route_name='SystemDictionaryData' AND status='DISABLED'
               AND deleted_at IS NOT NULL AND row_version=1
            """, Long.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM iam_menu
             WHERE tenant_id IN (2,3)
               AND route_name IN ('SystemDictionaryDataIndex','DictionaryDataView')
               AND status='DISABLED' AND deleted_at IS NOT NULL AND row_version=1
            """, Long.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM iam_role_menu role_menu
            JOIN iam_menu menu ON menu.tenant_id=role_menu.tenant_id AND menu.id=role_menu.menu_id
            WHERE menu.route_name IN ('SystemDictionaryDataIndex','SystemDictionaryData')
              AND menu.status='DISABLED' AND menu.deleted_at IS NOT NULL
            """, Long.class)).isEqualTo(5);
    }

    @Test
    void activeLegacyDynamicMenuFailsClosedWithoutFixtureMutation() throws Exception {
        runBootstrap(FIXTURE_LOGIN_INPUT);
        jdbc.update("""
            INSERT INTO iam_menu(
                id,tenant_id,parent_id,menu_type,menu_name,route_name,route_path,
                component_path,redirect_path,display_permission_id,sort_order,auth_code,
                status,meta_json,system_managed
            ) VALUES (
                6043,1,6000,'PAGE','Dictionary Data Detail','SystemDictionaryData',
                '/system/dict/data/type/:dictType','/system/dict/data/list',NULL,3029,161,NULL,
                'ACTIVE','{"title":"system.dictData.title","hideInMenu":true,"activePath":"/system/dict"}'::jsonb,true
            );
            INSERT INTO iam_role_menu(tenant_id,role_id,menu_id) VALUES (1,2000,6043);
            """);
        String before = fixtureVersionState();

        assertThatThrownBy(() -> runBootstrap(FIXTURE_LOGIN_INPUT))
            .hasStackTraceContaining("local fixture footprint is incomplete or modified");

        assertThat(fixtureVersionState()).isEqualTo(before);
        assertThat(jdbc.queryForObject(
            "SELECT status FROM iam_menu WHERE id=6043", String.class)).isEqualTo("ACTIVE");
    }

    @ParameterizedTest(name = "role {0} must reject {1}")
    @CsvSource({
        "2200, DISABLED",
        "2200, TOMBSTONED",
        "3200, DISABLED",
        "3200, TOMBSTONED"
    })
    void isolatedPortalRoleMustRemainActiveAndLive(long roleId, String defect) throws Exception {
        runBootstrap(FIXTURE_LOGIN_INPUT);
        if ("DISABLED".equals(defect)) {
            jdbc.update("UPDATE iam_role SET status='DISABLED' WHERE id=?", roleId);
        } else {
            jdbc.update("UPDATE iam_role SET deleted_at=now() WHERE id=?", roleId);
        }
        String corruptedState = isolatedFixtureState();

        assertThatThrownBy(() -> runBootstrap(FIXTURE_LOGIN_INPUT))
            .hasStackTraceContaining("merchant or agent identity fixture is incomplete");
        assertThat(isolatedFixtureState()).isEqualTo(corruptedState);
    }

    @ParameterizedTest(name = "grant {0} must reject {3}")
    @CsvSource({
        "7023, 2, 2200, VALID_UNTIL, 3024",
        "7023, 2, 2200, EXTRA_DIMENSION, 3024",
        "7023, 2, 2200, EXTRA_PORTAL_GRANT, 3024",
        "7024, 3, 3200, VALID_UNTIL, 3023",
        "7024, 3, 3200, EXTRA_DIMENSION, 3023",
        "7024, 3, 3200, EXTRA_PORTAL_GRANT, 3023"
    })
    void isolatedPortalGrantMustRemainCanonical(long grantId,
                                                 long tenantId,
                                                 long roleId,
                                                 String defect,
                                                 long otherPortalPermissionId) throws Exception {
        runBootstrap(FIXTURE_LOGIN_INPUT);
        switch (defect) {
            case "VALID_UNTIL" -> jdbc.update(
                "UPDATE iam_role_grant SET valid_until=now() + INTERVAL '1 day' WHERE id=?", grantId);
            case "EXTRA_DIMENSION" -> jdbc.update("""
                INSERT INTO iam_grant_dimension(id,grant_id,dimension_code,scope_mode)
                VALUES (nextval('iam_id_seq'),?,'OWNER','SELF')
                """, grantId);
            case "EXTRA_PORTAL_GRANT" -> {
                long extraGrantId = jdbc.queryForObject("SELECT nextval('iam_id_seq')", Long.class);
                jdbc.update("""
                    INSERT INTO iam_role_grant(
                        id,tenant_id,role_id,permission_id,grant_key,status,created_by,updated_by
                    ) VALUES (?, ?, ?, ?, 'unexpected-portal-access', 'ACTIVE', NULL, NULL)
                    """, extraGrantId, tenantId, roleId, otherPortalPermissionId);
                jdbc.update("""
                    INSERT INTO iam_grant_dimension(id,grant_id,dimension_code,scope_mode)
                    VALUES (nextval('iam_id_seq'),?,'TENANT','TENANT_ALL')
                    """, extraGrantId);
            }
            default -> throw new IllegalArgumentException("Unknown fixture defect: " + defect);
        }
        String corruptedState = isolatedFixtureState();

        assertThatThrownBy(() -> runBootstrap(FIXTURE_LOGIN_INPUT))
            .hasStackTraceContaining("merchant or agent identity fixture is incomplete");
        assertThat(isolatedFixtureState()).isEqualTo(corruptedState);
    }

    @Test
    void activeMembershipWithoutAnExplicitPortalGrantCannotAuthenticate() throws Exception {
        runBootstrap(FIXTURE_LOGIN_INPUT);
        var credentials = new JooqCredentialRepository(DSL.using(dataSource, SQLDialect.POSTGRES));

        assertThat(credentials.findActiveByUsername("admin@platform.localhost", AccountDomain.PLATFORM)).isPresent();
        jdbc.update("UPDATE iam_role_grant SET status='DISABLED' WHERE id=7022");

        assertThat(jdbc.queryForObject(
            "SELECT status FROM iam_membership WHERE id=1000", String.class)).isEqualTo("ACTIVE");
        assertThat(credentials.findActiveByUsername("admin@platform.localhost", AccountDomain.PLATFORM)).isEmpty();
    }

    @Test
    void nonCanonicalPortalGrantValidityOrDuplicatesFailClosed() throws Exception {
        runBootstrap(FIXTURE_LOGIN_INPUT);
        var credentials = new JooqCredentialRepository(DSL.using(dataSource, SQLDialect.POSTGRES));

        jdbc.update("UPDATE iam_role_grant SET valid_until=CURRENT_TIMESTAMP + INTERVAL '1 day' WHERE id=7022");
        assertThat(credentials.findActiveByUsername("admin@platform.localhost", AccountDomain.PLATFORM)).isEmpty();

        jdbc.update("UPDATE iam_role_grant SET valid_until=NULL WHERE id=7022");
        long grantId = jdbc.queryForObject("SELECT nextval('iam_id_seq')", Long.class);
        long dimensionId = jdbc.queryForObject("SELECT nextval('iam_id_seq')", Long.class);
        jdbc.update("""
            INSERT INTO iam_role_grant(id,tenant_id,role_id,permission_id,grant_key,status)
            SELECT ?,1,2000,id,'unexpected-portal-access','ACTIVE'
              FROM iam_permission WHERE permission_code='backoffice:merchant-access'
            """, grantId);
        jdbc.update("""
            INSERT INTO iam_grant_dimension(id,grant_id,dimension_code,scope_mode)
            VALUES (?,?,'TENANT','TENANT_ALL')
            """, dimensionId, grantId);

        assertThat(credentials.findActiveByUsername("admin@platform.localhost", AccountDomain.PLATFORM)).isEmpty();
    }

    @Test
    void successfulLoginMetadataRemainsValidAcrossLocalRestart() throws Exception {
        runBootstrap(FIXTURE_LOGIN_INPUT);
        new JooqCredentialRepository(DSL.using(dataSource, SQLDialect.POSTGRES))
            .markLoginSucceeded(100L);
        OffsetDateTime lastLoginAt = jdbc.queryForObject("""
            SELECT last_login_at FROM iam_authentication_credential WHERE user_id = 100
            """, OffsetDateTime.class);
        Long rowVersion = jdbc.queryForObject("""
            SELECT row_version FROM iam_authentication_credential WHERE user_id = 100
            """, Long.class);

        runBootstrap(FIXTURE_LOGIN_INPUT);

        assertCompleteFixture();
        assertThat(jdbc.queryForObject("""
            SELECT last_login_at FROM iam_authentication_credential WHERE user_id = 100
            """, OffsetDateTime.class)).isEqualTo(lastLoginAt);
        assertThat(jdbc.queryForObject("""
            SELECT row_version FROM iam_authentication_credential WHERE user_id = 100
            """, Long.class)).isEqualTo(rowVersion).isEqualTo(2L);
    }

    @Test
    void reservedIdentifierCollisionFailsWithoutAttachingFixtureRowsToTheWrongTenant() {
        jdbc.update("""
            INSERT INTO iam_tenant(id, tenant_code, tenant_name, tenant_type, status, account_domain)
            VALUES (1, 'real-platform', 'Real Platform', 'PLATFORM', 'ACTIVE', 'PLATFORM')
            """);

        assertThatThrownBy(() -> runBootstrap(FIXTURE_LOGIN_INPUT))
            .hasStackTraceContaining("local fixture footprint is incomplete or modified");

        assertThat(count("iam_tenant")).isOne();
        assertThat(count("iam_department")).isZero();
        assertThat(count("iam_membership")).isZero();
        assertThat(count("iam_role_grant")).isZero();
    }

    @Test
    void reservedNaturalKeyCollisionFailsWithoutPartialFixtureWrites() {
        jdbc.update("""
            INSERT INTO iam_tenant(id, tenant_code, tenant_name, tenant_type, status, account_domain)
            VALUES (4, 'platform', 'Real Platform', 'PLATFORM', 'ACTIVE', 'PLATFORM')
            """);

        assertThatThrownBy(() -> runBootstrap(FIXTURE_LOGIN_INPUT))
            .hasStackTraceContaining("local fixture footprint is incomplete or modified");

        assertThat(count("iam_tenant")).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM iam_tenant WHERE id = 1", Long.class)).isZero();
        assertThat(count("iam_department")).isZero();
    }

    @Test
    void partialFixtureFailsInsteadOfBeingSilentlyRepaired() throws Exception {
        runBootstrap(FIXTURE_LOGIN_INPUT);
        jdbc.update("DELETE FROM iam_role_menu WHERE tenant_id = 1 AND role_id = 2000 AND menu_id = 6012");
        String partialRoleMenuState = roleMenuState();

        assertThatThrownBy(() -> runBootstrap(FIXTURE_LOGIN_INPUT))
            .hasStackTraceContaining("local fixture footprint is incomplete or modified");

        assertThat(roleMenuState()).isEqualTo(partialRoleMenuState);
    }

    @Test
    void exactLegacyEightMenuFixtureUpgradesTransactionally() throws Exception {
        runBootstrap(FIXTURE_LOGIN_INPUT);
        downgradeToLegacy(false);

        runBootstrap(FIXTURE_LOGIN_INPUT);

        assertCompleteFixture();
    }

    @Test
    void exactLegacyFourteenButtonFixtureUpgradesTransactionally() throws Exception {
        runBootstrap(FIXTURE_LOGIN_INPUT);
        downgradeToLegacy(true);

        runBootstrap(FIXTURE_LOGIN_INPUT);

        assertCompleteFixture();
    }

    @Test
    void v9EightMenuFixtureMigratedThroughV14BootstrapsTransactionally() throws Exception {
        restoreExactLegacyFixtureAtV9(false);

        migrateToLatest();

        assertExpandedMigratedLegacyFixture(8);
        runBootstrap(FIXTURE_LOGIN_INPUT);
        assertCompleteFixture();
        assertMigrationHistoryPreserved();
    }

    @Test
    void v9FourteenButtonFixtureMigratedThroughV14BootstrapsTransactionally() throws Exception {
        restoreExactLegacyFixtureAtV9(true);

        migrateToLatest();

        assertExpandedMigratedLegacyFixture(22);
        runBootstrap(FIXTURE_LOGIN_INPUT);
        assertCompleteFixture();
        assertMigrationHistoryPreserved();
    }

    @Test
    void v14FixtureWithAdvancedDepartmentVersionMigratesToLatestAndBootstraps() throws Exception {
        restoreExactLegacyFixtureAtV9(false);
        flyway("14").migrate();
        jdbc.update("UPDATE iam_department SET row_version=2 WHERE tenant_id=1 AND id=10");

        migrateToLatest();
        runBootstrap(FIXTURE_LOGIN_INPUT);

        assertCompleteFixture();
        assertThat(jdbc.queryForObject(
            "SELECT row_version FROM iam_department WHERE tenant_id=1 AND id=10", Long.class))
            .isEqualTo(2L);
    }

    @Test
    void latestFixtureWithAdvancedDepartmentVersionBootstraps() throws Exception {
        restoreExactLegacyFixtureAtV9(false);
        migrateToLatest();
        jdbc.update("UPDATE iam_department SET row_version=2 WHERE tenant_id=1 AND id=10");

        runBootstrap(FIXTURE_LOGIN_INPUT);

        assertCompleteFixture();
        assertThat(jdbc.queryForObject(
            "SELECT row_version FROM iam_department WHERE tenant_id=1 AND id=10", Long.class))
            .isEqualTo(2L);
    }

    @Test
    void systemManagedDepartmentEditsSurviveLocalRestart() throws Exception {
        restoreExactLegacyFixtureAtV9(false);
        migrateToLatest();
        jdbc.update("""
            UPDATE iam_department
               SET department_name='Modified Head Office', row_version=2
             WHERE tenant_id=1 AND id=10
            """);

        runBootstrap(FIXTURE_LOGIN_INPUT);

        assertThat(jdbc.queryForObject(
            "SELECT department_name FROM iam_department WHERE tenant_id=1 AND id=10", String.class))
            .isEqualTo("Modified Head Office");
        assertThat(count("iam_role_grant")).isEqualTo(71);
    }

    @Test
    void systemManagedMenuEditsAndSoftDeletesSurviveLocalRestart() throws Exception {
        runBootstrap(FIXTURE_LOGIN_INPUT);
        jdbc.update("""
            UPDATE iam_menu
               SET menu_name='RenamedUserMenu', route_name='RenamedUserMenu',
                   route_path='/renamed-user-menu', row_version=row_version+1
             WHERE tenant_id=1 AND id=6002
            """);
        jdbc.update("""
            UPDATE iam_menu
               SET status='DISABLED', deleted_at=now(), row_version=row_version+1
             WHERE tenant_id=1 AND id=6040
            """);

        runBootstrap(FIXTURE_LOGIN_INPUT);

        assertThat(jdbc.queryForObject(
            "SELECT route_name FROM iam_menu WHERE tenant_id=1 AND id=6002", String.class))
            .isEqualTo("RenamedUserMenu");
        assertThat(jdbc.queryForObject(
            "SELECT deleted_at FROM iam_menu WHERE tenant_id=1 AND id=6040", OffsetDateTime.class))
            .isNotNull();
    }

    @Test
    void reservedMenuOwnershipDriftStillFailsClosed() throws Exception {
        runBootstrap(FIXTURE_LOGIN_INPUT);
        jdbc.update("UPDATE iam_menu SET system_managed=false WHERE tenant_id=1 AND id=6040");

        assertThatThrownBy(() -> runBootstrap(FIXTURE_LOGIN_INPUT))
            .hasStackTraceContaining("local fixture footprint is incomplete or modified");
    }

    @Test
    void partialV14MigratedLegacyFixtureStillFailsWithoutMutation() {
        restoreExactLegacyFixtureAtV9(false);
        migrateToLatest();
        jdbc.update("""
            DELETE FROM iam_role_grant grant_row
             USING iam_permission permission
             WHERE grant_row.permission_id=permission.id
               AND grant_row.tenant_id=1 AND grant_row.role_id=2000
               AND permission.permission_code='menu:create'
               AND grant_row.grant_key='migration-v14-menu-create'
            """);
        String partialFixtureState = fixtureAuthorizationState();

        assertThatThrownBy(() -> runBootstrap(FIXTURE_LOGIN_INPUT))
            .hasStackTraceContaining("local fixture footprint is incomplete or modified");

        assertThat(fixtureAuthorizationState()).isEqualTo(partialFixtureState);
        assertThat(jdbc.queryForObject(
            "SELECT password_hash FROM iam_authentication_credential WHERE user_id=100", String.class))
            .isNull();
    }

    @Test
    void failedLegacyMenuUpgradeRollsBackToTheExactEightMenuState() throws Exception {
        runBootstrap(FIXTURE_LOGIN_INPUT);
        downgradeToLegacy(false);
        jdbc.execute("""
            CREATE FUNCTION fail_local_permission_button_insert()
            RETURNS trigger
            LANGUAGE plpgsql
            AS $$
            BEGIN
                IF NEW.id = 6020 THEN
                    RAISE EXCEPTION 'forced local permission button failure';
                END IF;
                RETURN NEW;
            END;
            $$
            """);
        jdbc.execute("""
            CREATE TRIGGER trg_fail_local_permission_button_insert
            BEFORE INSERT ON iam_menu
            FOR EACH ROW EXECUTE FUNCTION fail_local_permission_button_insert()
            """);

        assertThatThrownBy(() -> runBootstrap(FIXTURE_LOGIN_INPUT))
            .hasStackTraceContaining("forced local permission button failure");

        assertThat(count("iam_menu")).isEqualTo(61);
        assertThat(count("iam_role_menu")).isEqualTo(31);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM iam_menu
             WHERE tenant_id = 1 AND menu_type = 'BUTTON' AND id BETWEEN 6020 AND 6040
            """, Long.class)).isZero();
    }

    @Test
    void partialPermissionButtonFixtureFailsInsteadOfBeingSilentlyRepaired() throws Exception {
        runBootstrap(FIXTURE_LOGIN_INPUT);
        jdbc.update("DELETE FROM iam_menu WHERE tenant_id = 1 AND id = 6040");

        assertThatThrownBy(() -> runBootstrap(FIXTURE_LOGIN_INPUT))
            .hasStackTraceContaining("local fixture footprint is incomplete or modified");

        assertThat(count("iam_menu")).isEqualTo(81);
        assertThat(count("iam_role_menu")).isEqualTo(31);
    }

    @Test
    void duplicateFixtureAuthCodeFailsInsteadOfCreatingAnAmbiguousCatalog() throws Exception {
        runBootstrap(FIXTURE_LOGIN_INPUT);
        jdbc.update("""
            INSERT INTO iam_menu(
                id, tenant_id, parent_id, menu_type, menu_name, route_name,
                sort_order, auth_code, status, meta_json
            ) VALUES (
                7200, 1, NULL, 'BUTTON', 'Duplicate User View', 'DuplicateUserView',
                500, 'user:view', 'ACTIVE', '{"title":"system.user.permission.view"}'::jsonb
            )
            """);

        assertThatThrownBy(() -> runBootstrap(FIXTURE_LOGIN_INPUT))
            .hasStackTraceContaining("local fixture footprint is incomplete or modified");

        assertThat(count("iam_menu")).isEqualTo(83);
    }

    @Test
    void modifiedFixtureIdentityFailsInsteadOfGrantingTheWrongSubject() throws Exception {
        runBootstrap(FIXTURE_LOGIN_INPUT);
        jdbc.update("UPDATE iam_user SET idp_subject = 'different-subject' WHERE id = 100");

        assertThatThrownBy(() -> runBootstrap(FIXTURE_LOGIN_INPUT))
            .hasStackTraceContaining("local fixture footprint is incomplete or modified");

        assertThat(jdbc.queryForObject("SELECT idp_subject FROM iam_user WHERE id = 100", String.class))
            .isEqualTo("different-subject");
        assertThat(count("iam_role_grant")).isEqualTo(71);
    }

    @Test
    void changedConfiguredPasswordFailsInsteadOfSilentlyKeepingUnknownCredentials() throws Exception {
        runBootstrap(FIXTURE_LOGIN_INPUT);
        String originalCredentialState = credentialState();

        assertThatThrownBy(() -> runBootstrap("a-different-password"))
            .hasStackTraceContaining("does not match payment.bootstrap-password");

        assertThat(credentialState()).isEqualTo(originalCredentialState);
    }

    @Test
    void productPermissionExtensionsRemainUntouched() throws Exception {
        insertPermissionExtension();

        runBootstrap(FIXTURE_LOGIN_INPUT);

        assertCompleteFixture();
        assertThat(count("iam_permission")).isEqualTo(46);
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM iam_permission WHERE id = 9001 AND permission_code = 'payout:view'",
            Long.class)).isOne();
    }

    @Test
    void missingRequiredPermissionFailsWithoutAnyFixtureWrites() {
        jdbc.update("DELETE FROM iam_permission WHERE id = 3001");

        assertThatThrownBy(() -> runBootstrap(FIXTURE_LOGIN_INPUT))
            .hasStackTraceContaining("required permission catalog is incomplete or modified");

        assertThat(count("iam_permission")).isEqualTo(44);
        assertThat(count("iam_tenant")).isZero();
        assertThat(count("iam_user")).isZero();
        assertThat(count("iam_role_grant")).isZero();
    }

    @Test
    void unrelatedProductionRowsDoNotBlockTheLocalFixture() throws Exception {
        jdbc.update("""
            INSERT INTO iam_tenant(id, tenant_code, tenant_name, tenant_type, status, account_domain)
            VALUES (4, 'merchant-four', 'Merchant Four', 'DIRECT_MERCHANT', 'ACTIVE', 'MERCHANT')
            """);
        jdbc.update("""
            INSERT INTO iam_user(id, idp_issuer, idp_subject, display_name, status, account_domain)
            VALUES (400, 'production-idp', 'real-user', 'Real User', 'ACTIVE', 'MERCHANT')
            """);
        jdbc.update("""
            INSERT INTO iam_audit_event(
                id, tenant_id, target_type, target_ref, action_code,
                decision, reason_code, trace_id
            ) VALUES (nextval('iam_id_seq'), 4, 'TENANT', '4', 'CREATED',
                      'NOT_APPLICABLE', 'TEST', 'real-audit')
            """);
        jdbc.update("""
            INSERT INTO iam_permission_change_outbox(
                id, tenant_id, aggregate_type, aggregate_ref, event_type, payload,
                aggregate_version, schema_version, partition_key, trace_id
            ) VALUES (nextval('iam_id_seq'), 4, 'TENANT', '4', 'TenantChanged',
                      '{}'::jsonb, 1, 1, '4', 'real-outbox')
            """);

        runBootstrap(FIXTURE_LOGIN_INPUT);

        assertCompleteFixture();
        assertThat(count("iam_tenant")).isEqualTo(5);
        assertThat(count("iam_user")).isEqualTo(5);
        assertThat(count("iam_audit_event")).isOne();
        assertThat(count("iam_permission_change_outbox")).isEqualTo(3);
    }

    @Test
    void adminCreatedLocalIdentityDataSurvivesRestartWithoutOwningTheFixture() throws Exception {
        runBootstrap(FIXTURE_LOGIN_INPUT);
        jdbc.update("""
            INSERT INTO iam_department(
                id, tenant_id, parent_id, department_code, department_name, status, remark
            ) VALUES (7001, 1, 10, 'local-engineering', 'Local Engineering', 'ACTIVE',
                      'Created after local bootstrap')
            """);
        jdbc.update("""
            INSERT INTO iam_user(id, idp_issuer, idp_subject, display_name, status, account_domain)
            VALUES (700, 'local', 'developer', 'Local Developer', 'ACTIVE', 'PLATFORM')
            """);
        jdbc.update("""
            INSERT INTO iam_membership(id, tenant_id, user_id, department_id, status, account_domain)
            VALUES (7000, 1, 700, 7001, 'ACTIVE', 'PLATFORM')
            """);
        jdbc.update("""
            INSERT INTO iam_authentication_credential(user_id, username, password_hash, status, account_domain)
            VALUES (700, 'developer', NULL, 'ACTIVE', 'PLATFORM')
            """);
        jdbc.update("""
            INSERT INTO iam_role(
                id, tenant_id, role_code, role_name, applicable_tenant_type,
                assignable, system_role, status
            ) VALUES (7100, 1, 'local-developer', 'Local Developer', 'PLATFORM', true, false, 'ACTIVE')
            """);
        jdbc.update("""
            INSERT INTO iam_membership_role(tenant_id, membership_id, role_id, assigned_by)
            VALUES (1, 7000, 7100, 1000)
            """);
        jdbc.update("""
            INSERT INTO iam_role_grant(
                id, tenant_id, role_id, permission_id, grant_key, status, created_by, updated_by
            ) VALUES (7300, 1, 7100, 3001, 'local-developer-view', 'ACTIVE', 1000, 1000)
            """);
        jdbc.update("""
            INSERT INTO iam_grant_dimension(id, grant_id, dimension_code, scope_mode)
            VALUES (7400, 7300, 'TENANT', 'TENANT_ALL')
            """);
        jdbc.update("""
            INSERT INTO iam_menu(
                id, tenant_id, parent_id, menu_type, menu_name, route_name, route_path,
                component_path, sort_order, status, meta_json
            ) VALUES (
                7200, 1, 6000, 'PAGE', 'Local Developer', 'LocalDeveloper',
                '/local-developer', '/dashboard/workspace/index', 500, 'ACTIVE',
                '{"title":"local.developer"}'::jsonb
            )
            """);
        jdbc.update("""
            INSERT INTO iam_role_menu(tenant_id, role_id, menu_id)
            VALUES (1, 7100, 6001), (1, 7100, 7200)
            """);

        runBootstrap(FIXTURE_LOGIN_INPUT);

        assertCompleteFixture();
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM iam_department WHERE id = 7001 AND parent_id = 10", Long.class))
            .isOne();
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM iam_user WHERE id = 700 AND idp_subject = 'developer'", Long.class))
            .isOne();
        assertThat(jdbc.queryForObject(
            """
            SELECT count(*) FROM iam_membership_role
             WHERE membership_id = 7000 AND role_id = 7100 AND assigned_by = 1000
            """,
            Long.class)).isOne();
        assertThat(jdbc.queryForObject(
            """
            SELECT count(*) FROM iam_role_grant
             WHERE id = 7300 AND role_id = 7100 AND created_by = 1000 AND updated_by = 1000
            """, Long.class))
            .isOne();
        assertThat(jdbc.queryForObject(
            """
            SELECT count(*) FROM iam_menu
             WHERE id = 7200 AND route_name = 'LocalDeveloper' AND parent_id = 6000
            """, Long.class))
            .isOne();
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM iam_role_menu
             WHERE tenant_id = 1 AND role_id = 7100 AND menu_id IN (6001,7200)
            """, Long.class)).isEqualTo(2);
        assertThat(count("iam_department")).isEqualTo(4);
        assertThat(count("iam_user")).isEqualTo(5);
        assertThat(count("iam_role")).isEqualTo(4);
        assertThat(count("iam_menu")).isEqualTo(83);
    }

    @Test
    void relationshipAttachedToTheFixtureMembershipStillFailsWithoutMutation() throws Exception {
        runBootstrap(FIXTURE_LOGIN_INPUT);
        jdbc.update("""
            INSERT INTO iam_role(
                id, tenant_id, role_code, role_name, applicable_tenant_type,
                assignable, system_role, status
            ) VALUES (7100, 1, 'attached-role', 'Attached Role', 'PLATFORM', true, false, 'ACTIVE')
            """);
        jdbc.update("""
            INSERT INTO iam_membership_role(tenant_id, membership_id, role_id, assigned_by)
            VALUES (1, 1000, 7100, 1000)
            """);

        assertThatThrownBy(() -> runBootstrap(FIXTURE_LOGIN_INPUT))
            .hasStackTraceContaining("local fixture footprint is incomplete or modified");

        assertThat(count("iam_membership_role")).isEqualTo(5);
        assertThat(credentialState()).isNotBlank();
    }

    @Test
    void failureAfterEarlierInsertsRollsBackEveryFixtureTable() {
        jdbc.execute("""
            CREATE FUNCTION fail_local_fixture_role_insert()
            RETURNS trigger
            LANGUAGE plpgsql
            AS $$
            BEGIN
                IF NEW.id = 2000 THEN
                    RAISE EXCEPTION 'forced local fixture failure';
                END IF;
                RETURN NEW;
            END;
            $$
            """);
        jdbc.execute("""
            CREATE TRIGGER trg_fail_local_fixture_role_insert
            BEFORE INSERT ON iam_role
            FOR EACH ROW EXECUTE FUNCTION fail_local_fixture_role_insert()
            """);

        assertThatThrownBy(() -> runBootstrap(FIXTURE_LOGIN_INPUT))
            .hasStackTraceContaining("forced local fixture failure");

        assertThat(count("iam_tenant")).isZero();
        assertThat(count("iam_department")).isZero();
        assertThat(count("iam_user")).isZero();
        assertThat(count("iam_membership")).isZero();
        assertThat(count("iam_authentication_credential")).isZero();
        assertThat(count("iam_role")).isZero();
        assertThat(count("iam_membership_role")).isZero();
        assertThat(count("iam_role_grant")).isZero();
        assertThat(count("iam_grant_dimension")).isZero();
        assertThat(count("iam_menu")).isZero();
        assertThat(count("iam_role_menu")).isZero();
        assertThat(count("iam_permission")).isEqualTo(45);
    }

    @Test
    void bootstrapComponentIsRegisteredForBothLocalRuntimeProfiles() {
        Profile profile = LocalIdentityFixtureBootstrap.class.getAnnotation(Profile.class);

        assertThat(profile).isNotNull();
        assertThat(profile.value()).containsExactlyInAnyOrder("local", "iam002-local");
    }

    private void runBootstrap(String password) throws Exception {
        runBootstrap(password, "local");
    }

    private void runBootstrap(String password, String profile) throws Exception {
        BCryptPasswordEncoder encoder = new BCryptPasswordEncoder(10);
        MockEnvironment environment = new MockEnvironment();
        environment.setActiveProfiles(profile);
        LocalIdentityFixtureBootstrap bootstrap = new LocalIdentityFixtureBootstrap(
            dataSource,
            jdbc,
            new DataSourceTransactionManager(dataSource),
            encoder,
            environment,
            password);
        bootstrap.run(new DefaultApplicationArguments(new String[0]));
    }

    private void restoreExactLegacyFixtureAtV9(boolean includeButtons) {
        flyway(null).clean();
        flyway("7").migrate();
        for (String table : LEGACY_FIXTURE_TABLES) {
            jdbc.execute("CREATE TABLE local_v9_backup_" + table + " AS TABLE " + table);
        }

        flyway("9").migrate();

        for (String table : LEGACY_FIXTURE_TABLES) {
            jdbc.execute("INSERT INTO " + table + " SELECT * FROM local_v9_backup_" + table);
        }
        jdbc.update("UPDATE iam_menu SET row_version=0 WHERE tenant_id=1");
        for (String table : LEGACY_FIXTURE_TABLES) {
            jdbc.execute("DROP TABLE local_v9_backup_" + table);
        }
        if (includeButtons) {
            insertLegacyPermissionButtons();
        }
        assertThat(count("iam_permission")).isEqualTo(14);
        assertThat(count("iam_role_grant")).isEqualTo(14);
        assertThat(count("iam_grant_dimension")).isEqualTo(14);
        assertThat(count("iam_menu")).isEqualTo(includeButtons ? 22 : 8);
        assertThat(count("iam_role_menu")).isEqualTo(8);
    }

    private void insertLegacyPermissionButtons() {
        jdbc.update("""
            INSERT INTO iam_menu(
                id,tenant_id,parent_id,menu_type,menu_name,route_name,sort_order,auth_code,status,meta_json
            ) VALUES
              (6020,1,6001,'BUTTON','View Users','UserView',111,'user:view','ACTIVE',
               '{"title":"system.user.permission.view"}'::jsonb),
              (6021,1,6001,'BUTTON','Create User','UserCreate',112,'user:create','ACTIVE',
               '{"title":"system.user.permission.create"}'::jsonb),
              (6022,1,6001,'BUTTON','Update User','UserUpdate',113,'user:update','ACTIVE',
               '{"title":"system.user.permission.update"}'::jsonb),
              (6023,1,6001,'BUTTON','Delete User','UserDelete',114,'user:delete','ACTIVE',
               '{"title":"system.user.permission.delete"}'::jsonb),
              (6024,1,6001,'BUTTON','Disable User','UserDisable',115,'user:disable','ACTIVE',
               '{"title":"system.user.permission.disable"}'::jsonb),
              (6025,1,6001,'BUTTON','Assign User Roles','UserAssignRole',116,'user:assign-role','ACTIVE',
               '{"title":"system.user.permission.assignRole"}'::jsonb),
              (6026,1,6002,'BUTTON','View Roles','RoleView',121,'role:view','ACTIVE',
               '{"title":"system.role.permission.view"}'::jsonb),
              (6027,1,6002,'BUTTON','Create Role','RoleCreate',122,'role:create','ACTIVE',
               '{"title":"system.role.permission.create"}'::jsonb),
              (6028,1,6002,'BUTTON','Update Role','RoleUpdate',123,'role:update','ACTIVE',
               '{"title":"system.role.permission.update"}'::jsonb),
              (6029,1,6002,'BUTTON','Delete Role','RoleDelete',124,'role:delete','ACTIVE',
               '{"title":"system.role.permission.delete"}'::jsonb),
              (6030,1,6003,'BUTTON','View Menus','MenuView',131,'menu:view','ACTIVE',
               '{"title":"system.menu.permission.view"}'::jsonb),
              (6031,1,6003,'BUTTON','Manage Menus','MenuManage',132,'menu:manage','ACTIVE',
               '{"title":"system.menu.permission.manage"}'::jsonb),
              (6032,1,6004,'BUTTON','View Departments','DepartmentView',141,'department:view','ACTIVE',
               '{"title":"system.dept.permission.view"}'::jsonb),
              (6033,1,6004,'BUTTON','Manage Departments','DepartmentManage',142,'department:manage','ACTIVE',
               '{"title":"system.dept.permission.manage"}'::jsonb)
            """);
    }

    private void migrateToLatest() {
        flyway(null).migrate();
    }

    private Flyway flyway(String version) {
        var configuration = PostgresFlywayTestSupport.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .cleanDisabled(false);
        if (version != null) {
            configuration.target(version);
        }
        return configuration.load();
    }

    private void assertExpandedMigratedLegacyFixture(int expectedMenuCount) {
        assertThat(count("iam_permission")).isEqualTo(45);
        assertThat(count("iam_role_grant")).isEqualTo(40);
        assertThat(count("iam_grant_dimension")).isEqualTo(40);
        assertThat(count("iam_menu")).isEqualTo(expectedMenuCount + 19L);
        assertThat(jdbc.queryForObject(
            "SELECT row_version FROM iam_role WHERE id=2000", Long.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject(
            "SELECT permission_version FROM iam_membership WHERE id=1000", Long.class)).isEqualTo(7);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM iam_permission
             WHERE permission_code IN ('menu:manage','department:manage') AND status='ACTIVE'
            """, Long.class)).isEqualTo(2);
    }

    private void assertMigrationHistoryPreserved() {
        assertThat(jdbc.queryForObject(
            "SELECT row_version FROM iam_role WHERE id=2000", Long.class)).isEqualTo(4);
        assertThat(jdbc.queryForObject(
            "SELECT permission_version FROM iam_membership WHERE id=1000", Long.class)).isEqualTo(7);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM iam_permission_change_outbox
             WHERE tenant_id=1 AND aggregate_type='MEMBERSHIP' AND aggregate_ref='1000'
               AND event_type='PERMISSION_VERSION_CHANGED' AND aggregate_version=5
               AND trace_id='migration-v32'
            """, Long.class)).isOne();
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM iam_audit_event
             WHERE tenant_id=1 AND target_type='ROLE_GRANTS' AND target_ref='2000'
               AND action_code='MIGRATE_GRANULAR_ADMIN_PERMISSIONS' AND trace_id='migration-v14'
            """, Long.class)).isOne();
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM iam_permission_change_outbox
             WHERE tenant_id=1 AND aggregate_type='MEMBERSHIP' AND aggregate_ref='1000'
               AND event_type='PERMISSION_VERSION_CHANGED' AND trace_id='migration-v14'
            """, Long.class)).isOne();
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM iam_audit_event
             WHERE tenant_id=1 AND target_type='ROLE_GRANTS' AND target_ref='2000'
               AND action_code='MIGRATE_PROTECTED_BACKOFFICE_ACCESS' AND trace_id='migration-v19'
            """, Long.class)).isOne();
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM iam_permission_change_outbox
             WHERE tenant_id=1 AND aggregate_type='MEMBERSHIP' AND aggregate_ref='1000'
               AND event_type='PERMISSION_VERSION_CHANGED' AND aggregate_version=3
               AND trace_id='migration-v19'
            """, Long.class)).isOne();
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM iam_audit_event
             WHERE tenant_id=1 AND target_type='ROLE_GRANTS' AND target_ref='2000'
               AND action_code='EXPAND_LEGACY_ADMIN_PERMISSIONS' AND trace_id='migration-v15'
            """, Long.class)).isOne();
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM iam_permission_change_outbox
             WHERE tenant_id=1 AND aggregate_type='MEMBERSHIP' AND aggregate_ref='1000'
               AND event_type='PERMISSION_VERSION_CHANGED' AND trace_id='migration-v15'
            """, Long.class)).isOne();
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM iam_role_grant
             WHERE tenant_id=1 AND role_id=2000 AND grant_key LIKE 'migration-v14-%'
            """, Long.class)).isZero();
    }

    private void assertCompleteFixture() {
        assertThat(jdbc.queryForObject("""
            SELECT count(*)
              FROM iam_tenant tenant
              JOIN iam_department department ON department.tenant_id = tenant.id
              JOIN iam_membership membership
                ON membership.tenant_id = tenant.id AND membership.department_id = department.id
              JOIN iam_user user_account ON user_account.id = membership.user_id
              JOIN iam_authentication_credential credential ON credential.user_id = user_account.id
              JOIN iam_role role ON role.tenant_id = tenant.id
              JOIN iam_membership_role membership_role
                ON membership_role.tenant_id = tenant.id
               AND membership_role.membership_id = membership.id
               AND membership_role.role_id = role.id
             WHERE tenant.id = 1 AND tenant.tenant_code = 'platform'
               AND department.id = 10 AND department.department_code = 'head-office'
               AND membership.id = 1000 AND membership.status = 'ACTIVE'
               AND user_account.id = 100 AND user_account.idp_issuer = 'local'
               AND user_account.idp_subject = 'admin@platform.localhost' AND user_account.status = 'ACTIVE'
               AND credential.username = 'admin@platform.localhost' AND credential.password_hash IS NOT NULL
               AND credential.status = 'ACTIVE'
               AND role.id = 2000 AND role.role_code = 'platform-admin'
               AND role.system_role AND NOT role.assignable AND role.status = 'ACTIVE'
            """, Long.class)).isOne();
        assertThat(jdbc.queryForObject(
            "SELECT count(*) FROM iam_role_grant WHERE tenant_id = 1 AND role_id = 2000",
            Long.class)).isEqualTo(38);
        assertThat(jdbc.queryForObject("""
            SELECT count(*)
              FROM iam_grant_dimension dimension_row
              JOIN iam_role_grant grant_row ON grant_row.id = dimension_row.grant_id
             WHERE grant_row.tenant_id = 1 AND grant_row.role_id = 2000
            """, Long.class)).isEqualTo(38);
        assertThat(jdbc.queryForObject("""
            SELECT count(*)
              FROM iam_grant_target target
              JOIN iam_grant_dimension dimension_row ON dimension_row.id = target.dimension_id
              JOIN iam_role_grant grant_row ON grant_row.id = dimension_row.grant_id
             WHERE grant_row.tenant_id = 1 AND grant_row.role_id = 2000
            """, Long.class)).isZero();
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM iam_menu
             WHERE tenant_id = 1
               AND id IN (6000, 6001, 6002, 6003, 6004, 6010, 6011, 6012)
            """, Long.class)).isEqualTo(8);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM iam_role_menu
             WHERE tenant_id = 1 AND role_id = 2000
               AND menu_id IN (6000, 6001, 6002, 6003, 6004, 6010, 6011, 6012)
            """, Long.class)).isEqualTo(8);
        assertThat(jdbc.queryForObject("""
            SELECT count(*)
              FROM iam_menu menu
              JOIN iam_permission permission ON permission.permission_code = menu.auth_code
             WHERE menu.tenant_id = 1 AND menu.menu_type = 'BUTTON'
               AND menu.status = 'ACTIVE' AND permission.status = 'ACTIVE'
               AND menu.route_path IS NULL AND menu.component_path IS NULL
               AND menu.redirect_path IS NULL
            """, Long.class)).isEqualTo(33);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM iam_menu
             WHERE tenant_id = 1 AND id IN (6031,6033) AND menu_type = 'BUTTON'
               AND status = 'DISABLED' AND meta_json ->> 'hideInMenu' = 'true'
            """, Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM iam_menu
             WHERE tenant_id = 1 AND menu_type = 'BUTTON'
            """, Long.class)).isEqualTo(35);
        assertThat(jdbc.queryForObject("""
            SELECT count(*)
              FROM iam_role_menu role_menu
              JOIN iam_menu menu
                ON menu.id = role_menu.menu_id AND menu.tenant_id = role_menu.tenant_id
             WHERE role_menu.tenant_id = 1
               AND role_menu.role_id = 2000
               AND menu.menu_type = 'BUTTON'
            """, Long.class)).isEqualTo(6);
        assertThat(jdbc.queryForObject("""
            SELECT count(*)
              FROM sys_dictionary_type dictionary_type
              JOIN sys_dictionary_data dictionary_data
                ON dictionary_data.dictionary_type_id=dictionary_type.id
             WHERE dictionary_type.dict_type='BELONG_SYSTEM'
               AND dictionary_type.dict_name='系统-归属系统'
               AND dictionary_type.sort_order=0
               AND dictionary_type.remark=''
               AND dictionary_type.deleted_at IS NULL
               AND dictionary_data.deleted_at IS NULL
               AND (dictionary_data.label,dictionary_data.value,dictionary_data.color,
                    dictionary_data.sort_order,dictionary_data.remark) IN (
                    ('运维','1','processing',1,''),
                    ('商户','2','success',2,''),
                    ('代理','3','purple',3,''))
            """, Long.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject(
            "SELECT revision FROM sys_dictionary_catalog_revision WHERE singleton_id=1",
            Long.class)).isEqualTo(5L);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM iam_menu
             WHERE route_name='SystemDictionaryData' AND status='ACTIVE' AND deleted_at IS NULL
            """, Long.class)).isZero();
    }

    private void downgradeToLegacy(boolean includeButtons) {
        jdbc.execute("""
            ALTER TABLE iam_permission_change_outbox
            DISABLE TRIGGER trg_iam_permission_outbox_append_only
            """);
        try {
            jdbc.update("""
                DELETE FROM iam_permission_change_relay_state relay
                 USING iam_permission_change_outbox outbox
                 WHERE relay.event_record_id=outbox.id
                   AND outbox.tenant_id=1 AND outbox.aggregate_type='MEMBERSHIP'
                   AND outbox.aggregate_ref='1000' AND outbox.aggregate_version=7
                   AND outbox.trace_id='local-mch003-bootstrap'
                """);
            jdbc.update("""
                DELETE FROM iam_permission_change_outbox
                 WHERE tenant_id=1 AND aggregate_type='MEMBERSHIP'
                   AND aggregate_ref='1000' AND aggregate_version=7
                   AND trace_id='local-mch003-bootstrap'
                """);
        } finally {
            jdbc.execute("""
                ALTER TABLE iam_permission_change_outbox
                ENABLE TRIGGER trg_iam_permission_outbox_append_only
                """);
        }
        jdbc.update("""
            UPDATE iam_membership
               SET permission_version=1, row_version=0
             WHERE tenant_id=1 AND id=1000
            """);
        jdbc.update("DELETE FROM iam_role_grant WHERE tenant_id=1 AND role_id=2000 AND permission_id BETWEEN 3015 AND 3021");
        jdbc.update("""
            INSERT INTO iam_role_grant(
                id,tenant_id,role_id,permission_id,grant_key,status,created_by,updated_by
            ) VALUES
              (4012,1,2000,3012,'menu-manage','ACTIVE',1000,1000),
              (4014,1,2000,3014,'department-manage','ACTIVE',1000,1000)
            """);
        jdbc.update("""
            INSERT INTO iam_grant_dimension(id,grant_id,dimension_code,scope_mode)
            VALUES (5012,4012,'TENANT','TENANT_ALL'),(5014,4014,'TENANT','TENANT_ALL')
            """);
        if (!includeButtons) {
            jdbc.update("""
                DELETE FROM iam_menu
                 WHERE tenant_id=1 AND menu_type='BUTTON' AND id BETWEEN 6020 AND 6040
                """);
            return;
        }
        jdbc.update("DELETE FROM iam_menu WHERE tenant_id=1 AND id BETWEEN 6034 AND 6040");
        jdbc.update("""
            UPDATE iam_menu SET status='ACTIVE', meta_json='{"title":"system.menu.permission.manage"}'::jsonb,
                   row_version=0 WHERE tenant_id=1 AND id=6031
            """);
        jdbc.update("""
            UPDATE iam_menu SET status='ACTIVE', meta_json='{"title":"system.dept.permission.manage"}'::jsonb,
                   row_version=0 WHERE tenant_id=1 AND id=6033
            """);
    }

    private void preparePersistedIam002LocalStateWithoutReviewer() {
        jdbc.execute("""
            ALTER TABLE iam_permission_change_outbox
            DISABLE TRIGGER trg_iam_permission_outbox_append_only
            """);
        try {
            jdbc.update("""
                DELETE FROM iam_permission_change_relay_state relay
                 USING iam_permission_change_outbox outbox
                 WHERE relay.event_record_id=outbox.id AND outbox.tenant_id=1
                   AND outbox.aggregate_type='MEMBERSHIP'
                   AND outbox.aggregate_ref IN ('1000','1001')
                   AND outbox.aggregate_version=7
                """);
            jdbc.update("""
                DELETE FROM iam_permission_change_outbox
                 WHERE tenant_id=1 AND aggregate_type='MEMBERSHIP'
                   AND aggregate_ref IN ('1000','1001') AND aggregate_version=7
                """);
        } finally {
            jdbc.execute("""
                ALTER TABLE iam_permission_change_outbox
                ENABLE TRIGGER trg_iam_permission_outbox_append_only
                """);
        }
        jdbc.update("DELETE FROM iam_membership_role WHERE tenant_id=1 AND membership_id=1001");
        jdbc.update("DELETE FROM iam_authentication_credential WHERE user_id=101");
        jdbc.update("DELETE FROM iam_membership WHERE tenant_id=1 AND id=1001");
        jdbc.update("DELETE FROM iam_user WHERE id=101");
        jdbc.update("DELETE FROM iam_tenant WHERE id=4000");
        jdbc.execute("ALTER TABLE merchant DISABLE TRIGGER USER");
        try {
            jdbc.execute("CREATE TABLE persisted_tenant_two_merchant AS "
                + "SELECT * FROM merchant WHERE id=9000");
            jdbc.update("DELETE FROM merchant WHERE id=9000");
            jdbc.update("""
                UPDATE persisted_tenant_two_merchant
                   SET id=10708,merchant_code='MCH_PERSISTED_LOCAL',
                       display_name='Persisted Local Merchant',row_version=6
                """);
            jdbc.execute("INSERT INTO merchant SELECT * FROM persisted_tenant_two_merchant");
            jdbc.execute("DROP TABLE persisted_tenant_two_merchant");
        } finally {
            jdbc.execute("DROP TABLE IF EXISTS persisted_tenant_two_merchant");
            jdbc.execute("ALTER TABLE merchant ENABLE TRIGGER USER");
        }
        jdbc.update("""
            UPDATE iam_user SET idp_issuer=CASE account_domain
                WHEN 'PLATFORM' THEN 'local:platform'
                WHEN 'MERCHANT' THEN 'local:merchant'
                WHEN 'AGENT' THEN 'local:agent'
            END WHERE id IN (100,200,300)
            """);
        jdbc.update("""
            UPDATE iam_membership SET permission_version=5,row_version=63
             WHERE tenant_id=1 AND id=1000
            """);
        String persistedPasswordHash = new BCryptPasswordEncoder(10)
            .encode("persisted-local-password");
        jdbc.update("""
            UPDATE iam_authentication_credential
               SET password_hash=?,row_version=row_version+1
             WHERE user_id IN (100,200,300)
            """, persistedPasswordHash);
    }

    private String existingLocalAdministratorCredentialState() {
        return jdbc.queryForObject("""
            SELECT string_agg(user_id || ':' || password_hash || ':' || row_version,
                              ',' ORDER BY user_id)
              FROM iam_authentication_credential WHERE user_id IN (100,200,300)
            """, String.class);
    }

    private void assertPersistedReviewerAndCandidate() {
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM iam_user user_account
            JOIN iam_authentication_credential credential ON credential.user_id=user_account.id
            JOIN iam_membership membership ON membership.user_id=user_account.id
            JOIN iam_membership_role assignment
              ON assignment.tenant_id=membership.tenant_id
             AND assignment.membership_id=membership.id
            WHERE user_account.id=101 AND user_account.idp_issuer='local:platform'
              AND user_account.idp_subject='reviewer@platform.localhost'
              AND user_account.status='ACTIVE' AND credential.status='ACTIVE'
              AND membership.id=1001 AND membership.tenant_id=1
              AND membership.permission_version=5 AND assignment.role_id=2000
            """, Long.class)).isOne();
        String reviewerHash = jdbc.queryForObject("""
            SELECT password_hash FROM iam_authentication_credential WHERE user_id=101
            """, String.class);
        assertThat(new BCryptPasswordEncoder(10).matches(FIXTURE_LOGIN_INPUT, reviewerHash)).isTrue();
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM iam_tenant tenant
             WHERE tenant.id=4000 AND tenant.account_domain='MERCHANT' AND tenant.status='ACTIVE'
               AND NOT EXISTS (SELECT 1 FROM iam_department WHERE tenant_id=tenant.id)
               AND NOT EXISTS (SELECT 1 FROM iam_membership WHERE tenant_id=tenant.id)
               AND NOT EXISTS (SELECT 1 FROM iam_role WHERE tenant_id=tenant.id)
               AND NOT EXISTS (SELECT 1 FROM merchant WHERE tenant_id=tenant.id)
            """, Long.class)).isOne();
    }

    private String credentialState() {
        return jdbc.queryForObject("""
            SELECT password_hash || '|' || row_version || '|' || updated_at::text
              FROM iam_authentication_credential
             WHERE user_id = 100
            """, String.class);
    }

    private String dictionaryState() {
        return jdbc.queryForObject("""
            SELECT dictionary_type.row_version || '|' || revision.revision || '|' ||
                   string_agg(dictionary_data.label || ':' || dictionary_data.value || ':' ||
                              dictionary_data.color || ':' || dictionary_data.row_version,
                              ',' ORDER BY dictionary_data.sort_order, dictionary_data.id)
              FROM sys_dictionary_type dictionary_type
              JOIN sys_dictionary_data dictionary_data
                ON dictionary_data.dictionary_type_id=dictionary_type.id
              CROSS JOIN sys_dictionary_catalog_revision revision
             WHERE dictionary_type.dict_type='BELONG_SYSTEM'
               AND dictionary_type.deleted_at IS NULL
               AND dictionary_data.deleted_at IS NULL
               AND revision.singleton_id=1
             GROUP BY dictionary_type.row_version,revision.revision
            """, String.class);
    }

    private String fixtureVersionState() {
        return jdbc.queryForObject("""
            SELECT string_agg(state, ',' ORDER BY state)
              FROM (
                    SELECT 'tenant:' || row_version AS state FROM iam_tenant WHERE id = 1
                    UNION ALL SELECT 'department:' || row_version FROM iam_department WHERE id = 10
                    UNION ALL SELECT 'user:' || row_version FROM iam_user WHERE id = 100
                    UNION ALL SELECT 'membership:' || row_version FROM iam_membership WHERE id = 1000
                    UNION ALL SELECT 'role:' || row_version FROM iam_role WHERE id = 2000
                    UNION ALL SELECT 'grant:' || id || ':' || row_version
                              FROM iam_role_grant WHERE role_id = 2000
                    UNION ALL SELECT 'menu:' || id || ':' || row_version
                              FROM iam_menu WHERE tenant_id = 1
                   ) fixture_versions
            """, String.class);
    }

    private String roleMenuState() {
        return jdbc.queryForObject("""
            SELECT string_agg(tenant_id || ':' || role_id || ':' || menu_id, ','
                              ORDER BY tenant_id, role_id, menu_id)
              FROM iam_role_menu
            """, String.class);
    }

    private String fixtureAuthorizationState() {
        return jdbc.queryForObject("""
            SELECT string_agg(state, ',' ORDER BY state)
              FROM (
                    SELECT 'grant:' || id || ':' || tenant_id || ':' || role_id || ':'
                           || permission_id || ':' || grant_key || ':' || status || ':' || row_version
                           || ':' || COALESCE(valid_from::text, '') || ':' || COALESCE(valid_until::text, '')
                           AS state
                      FROM iam_role_grant
                    UNION ALL
                    SELECT 'dimension:' || id || ':' || grant_id || ':' || dimension_code || ':'
                           || scope_mode
                      FROM iam_grant_dimension
                    UNION ALL
                    SELECT 'menu:' || id || ':' || tenant_id || ':' || COALESCE(parent_id::text, '') || ':'
                           || route_name || ':' || status || ':' || row_version
                      FROM iam_menu
                    UNION ALL
                    SELECT 'role-menu:' || tenant_id || ':' || role_id || ':' || menu_id
                      FROM iam_role_menu
                   ) fixture_authorization
            """, String.class);
    }

    private String isolatedFixtureState() {
        return jdbc.queryForObject("""
            SELECT string_agg(state, ',' ORDER BY state)
              FROM (
                    SELECT 'role:' || id || ':' || status || ':' || COALESCE(deleted_at::text, '') AS state
                      FROM iam_role WHERE id IN (2200, 3200)
                    UNION ALL
                    SELECT 'grant:' || id || ':' || permission_id || ':' || grant_key || ':' || status
                           || ':' || COALESCE(valid_from::text, '') || ':' || COALESCE(valid_until::text, '')
                      FROM iam_role_grant WHERE role_id IN (2200, 3200)
                    UNION ALL
                    SELECT 'dimension:' || dimension_row.id || ':' || dimension_row.grant_id || ':'
                           || dimension_row.dimension_code || ':' || dimension_row.scope_mode
                      FROM iam_grant_dimension dimension_row
                      JOIN iam_role_grant grant_row ON grant_row.id=dimension_row.grant_id
                     WHERE grant_row.role_id IN (2200, 3200)
                   ) isolated_fixture
            """, String.class);
    }

    private void assertLocalMerchantCapability() {
        assertThat(jdbc.queryForObject("""
            SELECT count(*)
              FROM iam_role_grant grant_row
              JOIN iam_permission permission ON permission.id=grant_row.permission_id
              JOIN iam_grant_dimension dimension_row ON dimension_row.grant_id=grant_row.id
             WHERE grant_row.tenant_id=2 AND grant_row.role_id=2200
               AND permission.permission_code IN (
                   'merchant:self-view','merchant:submit','merchant:resubmit')
               AND grant_row.status='ACTIVE'
               AND grant_row.valid_from IS NOT NULL
               AND grant_row.valid_until IS NOT NULL
               AND grant_row.valid_until > grant_row.valid_from
               AND dimension_row.dimension_code='TENANT'
               AND dimension_row.scope_mode='TENANT_ALL'
            """, Long.class)).isEqualTo(3);
        assertThat(jdbc.queryForList("""
            SELECT route_name FROM iam_menu
             WHERE tenant_id=2
               AND route_name IN ('MerchantProfile','MerchantSubmit','MerchantResubmit')
             ORDER BY sort_order
            """, String.class)).containsExactly(
                "MerchantProfile", "MerchantSubmit", "MerchantResubmit");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM iam_role_menu role_menu
              JOIN iam_menu menu ON menu.tenant_id=role_menu.tenant_id
                                AND menu.id=role_menu.menu_id
             WHERE role_menu.tenant_id=2 AND role_menu.role_id=2200
               AND menu.route_name IN ('MerchantProfile','MerchantSubmit','MerchantResubmit')
            """, Long.class)).isEqualTo(3);
    }

    private String localMerchantCapabilityState() {
        return jdbc.queryForObject("""
            SELECT string_agg(state, ',' ORDER BY state)
              FROM (
                    SELECT 'grant:' || grant_row.id || ':' || permission.permission_code || ':'
                           || grant_row.valid_from::text || ':' || grant_row.valid_until::text
                           AS state
                      FROM iam_role_grant grant_row
                      JOIN iam_permission permission ON permission.id=grant_row.permission_id
                     WHERE grant_row.tenant_id=2 AND grant_row.role_id=2200
                       AND permission.permission_code LIKE 'merchant:%'
                    UNION ALL
                    SELECT 'menu:' || id || ':' || route_name || ':' || row_version
                      FROM iam_menu WHERE tenant_id=2
                       AND route_name IN ('MerchantProfile','MerchantSubmit','MerchantResubmit')
                    UNION ALL
                    SELECT 'role-menu:' || role_menu.menu_id
                      FROM iam_role_menu role_menu
                      JOIN iam_menu menu ON menu.tenant_id=role_menu.tenant_id
                                        AND menu.id=role_menu.menu_id
                     WHERE role_menu.tenant_id=2 AND role_menu.role_id=2200
                       AND menu.route_name IN ('MerchantProfile','MerchantSubmit','MerchantResubmit')
                   ) merchant_capability
            """, String.class);
    }

    private void assertLocalPlatformMerchantCapability() {
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM iam_role_grant grant_row
              JOIN iam_permission permission ON permission.id=grant_row.permission_id
              JOIN iam_grant_dimension dimension_row ON dimension_row.grant_id=grant_row.id
             WHERE grant_row.tenant_id=1 AND grant_row.role_id=2000
               AND permission.permission_code IN (
                   'merchant:view','merchant:review','merchant:disable',
                   'merchant:enable','merchant:terminate','merchant:update')
               AND grant_row.status='ACTIVE' AND grant_row.valid_from IS NOT NULL
               AND grant_row.valid_until > grant_row.valid_from
               AND dimension_row.dimension_code='TENANT'
               AND dimension_row.scope_mode='TENANT_ALL'
            """, Long.class)).isEqualTo(6);
        assertThat(jdbc.queryForList("""
            SELECT route_name FROM iam_menu WHERE tenant_id=1
             AND route_name IN (
                 'MerchantManagement','MerchantList','MerchantReview','MerchantDisable',
                 'MerchantEnable','MerchantTerminate','MerchantEdit')
             ORDER BY sort_order
            """, String.class)).containsExactly(
                "MerchantManagement", "MerchantList", "MerchantReview",
                "MerchantDisable", "MerchantEnable", "MerchantTerminate", "MerchantEdit");
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM iam_role_menu role_menu
              JOIN iam_menu menu ON menu.id=role_menu.menu_id AND menu.tenant_id=role_menu.tenant_id
             WHERE role_menu.tenant_id=1 AND role_menu.role_id=2000
               AND menu.route_name IN (
                   'MerchantManagement','MerchantList','MerchantReview','MerchantDisable',
                   'MerchantEnable','MerchantTerminate','MerchantEdit')
            """, Long.class)).isEqualTo(7);
    }

    private String localPlatformMerchantCapabilityState() {
        return jdbc.queryForObject("""
            SELECT string_agg(state, ',' ORDER BY state) FROM (
                SELECT 'grant:' || grant_row.id || ':' || permission.permission_code || ':'
                       || grant_row.valid_from::text || ':' || grant_row.valid_until::text AS state
                  FROM iam_role_grant grant_row
                  JOIN iam_permission permission ON permission.id=grant_row.permission_id
                 WHERE grant_row.tenant_id=1 AND grant_row.role_id=2000
                   AND permission.permission_code LIKE 'merchant:%'
                UNION ALL
                SELECT 'menu:' || id || ':' || route_name || ':' || row_version
                  FROM iam_menu WHERE tenant_id=1 AND route_name LIKE 'Merchant%'
                UNION ALL
                SELECT 'role-menu:' || role_menu.menu_id
                  FROM iam_role_menu role_menu
                  JOIN iam_menu menu ON menu.id=role_menu.menu_id AND menu.tenant_id=role_menu.tenant_id
                 WHERE role_menu.tenant_id=1 AND role_menu.role_id=2000
                   AND menu.route_name LIKE 'Merchant%'
            ) platform_merchant_capability
            """, String.class);
    }

    private void assertMch003LocalRuntime() {
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM iam_role_grant grant_row
              JOIN iam_permission permission ON permission.id=grant_row.permission_id
              JOIN iam_grant_dimension dimension_row ON dimension_row.grant_id=grant_row.id
             WHERE grant_row.tenant_id=1 AND grant_row.role_id=2000
               AND permission.permission_code IN (
                   'merchant:create','merchant:amend',
                   'merchant:document:upload','merchant:document:view')
               AND permission.risk_level='SENSITIVE' AND permission.requires_step_up
               AND NOT permission.requires_approval
               AND grant_row.status='ACTIVE' AND grant_row.valid_from IS NOT NULL
               AND grant_row.valid_until > grant_row.valid_from
               AND dimension_row.dimension_code='TENANT'
               AND dimension_row.scope_mode='TENANT_ALL'
               AND NOT EXISTS (SELECT 1 FROM iam_grant_target target
                                WHERE target.dimension_id=dimension_row.id)
            """, Long.class)).isEqualTo(4);
        assertThat(jdbc.queryForList("""
            SELECT route_name || ':' || auth_code || ':' || sort_order
              FROM iam_menu
             WHERE tenant_id=1 AND route_name IN ('MerchantCreate','MerchantEdit')
             ORDER BY sort_order
            """, String.class)).containsExactly(
                "MerchantEdit:merchant:amend:206", "MerchantCreate:merchant:create:207");
        assertThat(jdbc.queryForObject("""
            SELECT count(*)
              FROM iam_user user_account
              JOIN iam_authentication_credential credential ON credential.user_id=user_account.id
              JOIN iam_membership membership ON membership.user_id=user_account.id
              JOIN iam_membership_role assignment
                ON assignment.tenant_id=membership.tenant_id
               AND assignment.membership_id=membership.id
             WHERE user_account.id=101 AND user_account.idp_issuer='local'
               AND user_account.idp_subject='reviewer@platform.localhost'
               AND user_account.account_domain='PLATFORM' AND user_account.status='ACTIVE'
               AND credential.username='reviewer@platform.localhost'
               AND credential.account_domain='PLATFORM' AND credential.status='ACTIVE'
               AND credential.password_hash IS NOT NULL
               AND membership.id=1001 AND membership.tenant_id=1
               AND membership.account_domain='PLATFORM' AND membership.status='ACTIVE'
               AND membership.permission_version=7
               AND assignment.role_id=2000
            """, Long.class)).isOne();
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM iam_membership
             WHERE tenant_id=1 AND id IN (1000,1001) AND permission_version=7
            """, Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM iam_permission_change_outbox
             WHERE tenant_id=1 AND aggregate_type='MEMBERSHIP'
               AND aggregate_ref IN ('1000','1001')
               AND event_type='PERMISSION_VERSION_CHANGED' AND aggregate_version=7
               AND payload->>'reason' IN (
                   'V37_MERCHANT_ONBOARDING_ACCESS','LOCAL_MCH003_BOOTSTRAP')
            """, Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM iam_permission_change_relay_state relay
              JOIN iam_permission_change_outbox outbox ON outbox.id=relay.event_record_id
             WHERE outbox.tenant_id=1 AND outbox.aggregate_type='MEMBERSHIP'
               AND outbox.aggregate_ref IN ('1000','1001')
               AND outbox.aggregate_version=7
            """, Long.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM iam_tenant tenant
             WHERE tenant.id=4000 AND tenant.tenant_code='local-merchant-candidate'
               AND tenant.tenant_name='Local Merchant Candidate'
               AND tenant.tenant_type='DIRECT_MERCHANT'
               AND tenant.account_domain='MERCHANT' AND tenant.status='ACTIVE'
               AND NOT EXISTS (SELECT 1 FROM iam_membership WHERE tenant_id=tenant.id)
               AND NOT EXISTS (SELECT 1 FROM iam_role WHERE tenant_id=tenant.id)
               AND NOT EXISTS (SELECT 1 FROM iam_department WHERE tenant_id=tenant.id)
               AND NOT EXISTS (SELECT 1 FROM merchant WHERE tenant_id=tenant.id)
            """, Long.class)).isOne();
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM merchant
             WHERE tenant_id=2 AND account_domain='MERCHANT' AND status='ACTIVE'
            """, Long.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM merchant WHERE tenant_id=3", Long.class))
            .isZero();
    }

    private String mch003LocalRuntimeState() {
        return jdbc.queryForObject("""
            SELECT string_agg(state, ',' ORDER BY state) FROM (
                SELECT 'tenant:' || id || ':' || status || ':' || row_version AS state
                  FROM iam_tenant WHERE id IN (2,3,4000)
                UNION ALL
                SELECT 'user:' || id || ':' || status || ':' || row_version
                  FROM iam_user WHERE id=101
                UNION ALL
                SELECT 'membership:' || id || ':' || status || ':'
                       || permission_version || ':' || row_version
                  FROM iam_membership WHERE id IN (1000,1001)
                UNION ALL
                SELECT 'credential:' || user_id || ':' || COALESCE(password_hash,'') || ':' || row_version
                  FROM iam_authentication_credential WHERE user_id=101
                UNION ALL
                SELECT 'grant:' || grant_row.id || ':' || permission.permission_code || ':'
                       || grant_row.row_version || ':' || grant_row.valid_from::text || ':'
                       || grant_row.valid_until::text
                  FROM iam_role_grant grant_row
                  JOIN iam_permission permission ON permission.id=grant_row.permission_id
                 WHERE grant_row.tenant_id=1 AND grant_row.role_id=2000
                   AND permission.permission_code IN (
                       'merchant:create','merchant:amend',
                       'merchant:document:upload','merchant:document:view')
                UNION ALL
                SELECT 'menu:' || id || ':' || route_name || ':' || auth_code || ':'
                       || sort_order || ':' || row_version
                  FROM iam_menu WHERE tenant_id=1
                   AND route_name IN ('MerchantCreate','MerchantEdit')
                UNION ALL
                SELECT 'merchant:' || id || ':' || tenant_id || ':' || status || ':' || row_version
                  FROM merchant WHERE tenant_id IN (2,4000)
                UNION ALL
                SELECT 'outbox:' || id || ':' || aggregate_ref || ':' || aggregate_version
                  FROM iam_permission_change_outbox
                 WHERE tenant_id=1 AND aggregate_ref IN ('1000','1001')
                   AND aggregate_version=7
            ) mch003_runtime
            """, String.class);
    }

    private MerchantOnboardingService onboardingService() {
        var ring = ThreePurposeKeyRing.fromBase64(
            Map.of("mch-registration-search-v1", key(11)),
            Map.of("mch-idempotency-v1", key(22)),
            Map.of("mch-registration-aead-v1", key(33)));
        var onboardingRing = MerchantOnboardingKeyRing.fromBase64(
            Map.of("mch-legal-id-aead-v1", key(44)),
            Map.of("mch-document-aead-v1", key(55)),
            List.of(Map.of("mch-registration-search-v1", key(11)),
                Map.of("mch-idempotency-v1", key(22)),
                Map.of("mch-registration-aead-v1", key(33))));
        var repository = new JooqMerchantRepository(
            DSL.using(dataSource, SQLDialect.POSTGRES),
            new JdkMerchantCryptography(ring),
            new JdkMerchantOnboardingCryptography(onboardingRing),
            "mch-idempotency-v1", () -> "local-bootstrap-create-it");
        return new MerchantOnboardingService(repository);
    }

    private MerchantActor reviewerActor() {
        Record versions = DSL.using(dataSource, SQLDialect.POSTGRES).fetchOne("""
            SELECT membership.permission_version,membership.session_version,user_account.identity_version
              FROM iam_membership membership
              JOIN iam_user user_account ON user_account.id=membership.user_id
             WHERE membership.tenant_id=1 AND membership.id=1001
            """);
        return MerchantActor.localStepUp(101, 1001, 1,
            com.niv.payment.merchant.core.AccountDomain.PLATFORM,
            versions.get(0, Long.class), versions.get(1, Long.class), versions.get(2, Long.class));
    }

    private static Map<MerchantOnboardingModels.DocumentKind, Long> uploadDocumentSet(
        MerchantOnboardingService onboarding, MerchantActor reviewer, long targetTenantId
    ) {
        var documents = new EnumMap<MerchantOnboardingModels.DocumentKind, Long>(
            MerchantOnboardingModels.DocumentKind.class);
        for (var kind : MerchantOnboardingModels.DocumentKind.values()) {
            var uploaded = onboarding.uploadDocument(reviewer,
                new MerchantOnboardingModels.DocumentUploadRequest(targetTenantId, kind,
                    "image/png", new byte[]{(byte) (kind.ordinal() + 1), 42}, 1, 1));
            documents.put(kind, uploaded.documentId());
        }
        return Map.copyOf(documents);
    }

    private static com.niv.payment.merchant.core.MerchantMutationResult consumeInitialCandidate(
        MerchantOnboardingService onboarding, MerchantActor reviewer
    ) {
        return consumeCandidate(onboarding, reviewer, 4000L, 1);
    }

    private static com.niv.payment.merchant.core.MerchantMutationResult consumeCandidate(
        MerchantOnboardingService onboarding, MerchantActor reviewer, long targetTenantId, int ordinal
    ) {
        Map<MerchantOnboardingModels.DocumentKind, Long> documents = uploadDocumentSet(
            onboarding, reviewer, targetTenantId);
        return onboarding.create(reviewer, new MerchantOnboardingModels.CreateRequest(
            targetTenantId, new UUID(0x2cb43a568a9e45deL, ordinal),
            onboardingProfile(documents, ordinal)));
    }

    private static MerchantOnboardingModels.Profile onboardingProfile(
        Map<MerchantOnboardingModels.DocumentKind, Long> documents
    ) {
        return onboardingProfile(documents, 1);
    }

    private static MerchantOnboardingModels.Profile onboardingProfile(
        Map<MerchantOnboardingModels.DocumentKind, Long> documents, int ordinal
    ) {
        String suffix = Integer.toString(ordinal);
        return new MerchantOnboardingModels.Profile(
            "Candidate Merchant " + suffix, "Candidate Brand " + suffix,
            "ENTERPRISE", "PLATFORM", "ECOMMERCE",
            documents.get(MerchantOnboardingModels.DocumentKind.BRAND_LOGO),
            "Candidate Merchant Legal " + suffix, "BR", List.of("BRA"),
            "Candidate Registered Address", "Candidate Operating Address",
            documents.get(MerchantOnboardingModels.DocumentKind.BUSINESS_LICENSE),
            "Candidate Director " + suffix, "candidate" + suffix + "@example.test",
            "+5511999999998", "NATIONAL_ID",
            new MerchantOnboardingModels.SensitiveValue(
                MerchantOnboardingModels.SensitiveMode.REPLACE, "LEGAL-CANDIDATE-" + suffix),
            new MerchantOnboardingModels.LegalIdValidity(
                LocalDate.of(2020, 1, 1), LocalDate.of(2030, 1, 1)),
            documents.get(MerchantOnboardingModels.DocumentKind.LEGAL_ID_FRONT),
            documents.get(MerchantOnboardingModels.DocumentKind.LEGAL_ID_BACK),
            documents.get(MerchantOnboardingModels.DocumentKind.LEGAL_ID_HOLDING),
            "Local candidate consumed through PLATFORM create",
            new MerchantOnboardingModels.SensitiveValue(
                MerchantOnboardingModels.SensitiveMode.REPLACE, "REG-CANDIDATE-" + suffix));
    }

    private String merchantAggregateState(long merchantId) {
        return jdbc.queryForObject("""
            SELECT jsonb_build_object(
              'merchant',(SELECT to_jsonb(merchant_row) FROM merchant merchant_row WHERE id=?),
              'markets',(SELECT jsonb_agg(to_jsonb(market_row) ORDER BY market_code)
                           FROM merchant_operating_market market_row WHERE merchant_id=?),
              'documents',(SELECT jsonb_agg(to_jsonb(document_row) ORDER BY id)
                             FROM merchant_document document_row WHERE merchant_id=?),
              'bindings',(SELECT jsonb_agg(to_jsonb(binding_row) ORDER BY kind)
                            FROM merchant_document_binding binding_row WHERE merchant_id=?),
              'audit',(SELECT jsonb_agg(to_jsonb(audit_row) ORDER BY id)
                        FROM merchant_audit_event audit_row WHERE merchant_id=?),
              'dedup',(SELECT jsonb_agg(to_jsonb(dedup_row) ORDER BY id)
                        FROM merchant_command_dedup dedup_row WHERE merchant_id=?))::text
            """, String.class, merchantId, merchantId, merchantId, merchantId, merchantId,
            merchantId);
    }

    private String candidateLineageState() {
        return jdbc.queryForObject("""
            SELECT jsonb_agg(jsonb_build_object(
                     'tenant',to_jsonb(tenant),
                     'departments',(SELECT jsonb_agg(to_jsonb(department) ORDER BY id)
                                      FROM iam_department department WHERE tenant_id=tenant.id),
                     'memberships',(SELECT jsonb_agg(to_jsonb(membership) ORDER BY id)
                                      FROM iam_membership membership WHERE tenant_id=tenant.id),
                     'roles',(SELECT jsonb_agg(to_jsonb(role_row) ORDER BY id)
                                FROM iam_role role_row WHERE tenant_id=tenant.id),
                     'merchants',(SELECT jsonb_agg(to_jsonb(merchant_row) ORDER BY id)
                                    FROM merchant merchant_row WHERE tenant_id=tenant.id))
                     ORDER BY tenant.tenant_code)::text
              FROM iam_tenant tenant
             WHERE tenant.id=4000 OR tenant.tenant_code LIKE 'local-merchant-candidate%'
            """, String.class);
    }

    private void assertReviewerCredentialIsLoginCapable() {
        var account = new JooqCredentialRepository(DSL.using(dataSource, SQLDialect.POSTGRES))
            .findActiveByUsername("reviewer@platform.localhost", AccountDomain.PLATFORM)
            .orElseThrow();
        assertThat(account.membershipId()).isEqualTo(1001L);
        assertThat(new BCryptPasswordEncoder(10)
            .matches(FIXTURE_LOGIN_INPUT, account.passwordHash())).isTrue();
    }

    private static String key(int value) {
        byte[] bytes = new byte[32];
        java.util.Arrays.fill(bytes, (byte) value);
        return Base64.getEncoder().encodeToString(bytes);
    }

    private long count(String table) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class);
        return count == null ? 0 : count;
    }

    private void insertPermissionExtension() {
        jdbc.update("""
            INSERT INTO iam_permission(
                id, permission_code, resource_code, action_code, risk_level,
                required_dimensions, requires_step_up, requires_approval, status,
                description, cross_tenant_mode
            ) VALUES (9001, 'payout:view', 'payout', 'view', 'NORMAL',
                      ARRAY['TENANT']::varchar(32)[], false, false, 'ACTIVE',
                      'Product extension', 'SAME_TENANT_ONLY')
            """);
    }
}
