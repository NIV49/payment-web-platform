package com.niv.payment.adminapi;

import com.niv.payment.identity.lifecycle.JooqIdentityInvitationRepository;
import com.niv.payment.identity.lifecycle.TenantBootstrapCommand;
import com.niv.payment.identity.lifecycle.TenantType;
import com.niv.payment.permission.domain.AccountDomain;
import com.niv.payment.permission.domain.AuthorizationSubject;
import org.flywaydb.core.Flyway;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_AUDIT_EVENT;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_GRANT_DIMENSION;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_IDENTITY_INVITATION;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_IDENTITY_INVITATION_ROLE;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_MENU;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_PERMISSION;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_ROLE;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_ROLE_GRANT;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_ROLE_MENU;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_TENANT;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_TENANT_ENTRY_HOST;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class JooqTenantDictionaryBootstrapIntegrationTest {
    private static final long PLATFORM_TENANT_ID = 98_001L;
    private static final long PLATFORM_USER_ID = 98_002L;
    private static final long PLATFORM_MEMBERSHIP_ID = 98_003L;
    private static final long PLATFORM_ROLE_ID = 98_004L;
    private static final AuthorizationSubject PLATFORM_ADMIN =
        new AuthorizationSubject(PLATFORM_USER_ID, PLATFORM_MEMBERSHIP_ID,
            PLATFORM_TENANT_ID, null, 0L, 0L, true);

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse(
        "postgres:18.4-alpine@sha256:9a8afca54e7861fd90fab5fdf4c42477a6b1cb7d293595148e674e0a3181de15")
        .asCompatibleSubstituteFor("postgres"));

    private DSLContext dsl;
    private JooqIdentityInvitationRepository repository;

    @BeforeEach
    void migrate() {
        Flyway flyway = PostgresFlywayTestSupport.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .cleanDisabled(false)
            .load();
        flyway.clean();
        flyway.migrate();
        dsl = DSL.using(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        seedPlatformAdministrator();
        repository = new JooqIdentityInvitationRepository(dsl, () -> "tenant-dictionary-bootstrap-test");
    }

    static Stream<Arguments> tenantDomains() {
        return Stream.of(
            Arguments.of(AccountDomain.MERCHANT, TenantType.DIRECT_MERCHANT, "merchant"),
            Arguments.of(AccountDomain.AGENT, TenantType.AGENT, "agent")
        );
    }

    @Test
    void newMerchantTenantProtectedAdministratorReceivesMerchantSelfServiceCapability() {
        var reservation = repository.reserve(PLATFORM_ADMIN, AccountDomain.MERCHANT,
            command("merchant", TenantType.DIRECT_MERCHANT, UUID.randomUUID()));
        long tenantId = reservation.tenantId();
        long protectedRoleId = protectedRoleId(tenantId);

        List<String> permissionCodes = protectedRolePermissionCodes(tenantId, protectedRoleId);
        assertThat(permissionCodes).containsExactly(
            "backoffice:merchant-access", "dictionary-data:view", "merchant:resubmit",
            "merchant:self-view", "merchant:submit");
        assertThat(permissionCodes).doesNotContain(
            "merchant:view", "merchant:review", "merchant:disable", "merchant:enable",
            "merchant:terminate", "merchant:update");

        var merchantGrants = dsl.select(IAM_PERMISSION.PERMISSION_CODE,
                IAM_ROLE_GRANT.ID, IAM_ROLE_GRANT.VALID_FROM, IAM_ROLE_GRANT.VALID_UNTIL)
            .from(IAM_ROLE_GRANT)
            .join(IAM_PERMISSION).on(IAM_PERMISSION.ID.eq(IAM_ROLE_GRANT.PERMISSION_ID))
            .where(IAM_ROLE_GRANT.TENANT_ID.eq(tenantId)
                .and(IAM_ROLE_GRANT.ROLE_ID.eq(protectedRoleId))
                .and(IAM_PERMISSION.PERMISSION_CODE.in(
                    "merchant:self-view", "merchant:submit", "merchant:resubmit")))
            .orderBy(IAM_PERMISSION.PERMISSION_CODE)
            .fetch();
        assertThat(merchantGrants).hasSize(3).allSatisfy(grant -> {
            assertThat(grant.get(IAM_ROLE_GRANT.VALID_FROM)).isNotNull();
            assertThat(grant.get(IAM_ROLE_GRANT.VALID_UNTIL))
                .isAfter(grant.get(IAM_ROLE_GRANT.VALID_FROM));
            assertThat(dsl.fetchCount(IAM_GRANT_DIMENSION,
                IAM_GRANT_DIMENSION.GRANT_ID.eq(grant.get(IAM_ROLE_GRANT.ID))
                    .and(IAM_GRANT_DIMENSION.DIMENSION_CODE.eq("TENANT"))
                    .and(IAM_GRANT_DIMENSION.SCOPE_MODE.eq("TENANT_ALL"))))
                .isOne();
        });

        var menus = dsl.select(IAM_MENU.ROUTE_NAME, IAM_MENU.PARENT_ID, IAM_MENU.MENU_TYPE,
                IAM_MENU.ROUTE_PATH, IAM_MENU.COMPONENT_PATH, IAM_MENU.AUTH_CODE,
                IAM_MENU.SYSTEM_MANAGED)
            .from(IAM_MENU)
            .where(IAM_MENU.TENANT_ID.eq(tenantId))
            .orderBy(IAM_MENU.SORT_ORDER)
            .fetch();
        assertThat(menus).extracting(row -> row.get(IAM_MENU.ROUTE_NAME))
            .containsExactly("MerchantProfile", "MerchantSubmit", "MerchantResubmit");
        var page = menus.getFirst();
        assertThat(page.get(IAM_MENU.PARENT_ID)).isNull();
        assertThat(page.get(IAM_MENU.MENU_TYPE)).isEqualTo("PAGE");
        assertThat(page.get(IAM_MENU.ROUTE_PATH)).isEqualTo("/merchant/profile");
        assertThat(page.get(IAM_MENU.COMPONENT_PATH)).isEqualTo("/merchant/profile");
        assertThat(page.get(IAM_MENU.AUTH_CODE)).isEqualTo("merchant:self-view");
        assertThat(page.get(IAM_MENU.SYSTEM_MANAGED)).isTrue();
        assertThat(menus.subList(1, 3)).allSatisfy(button -> {
            assertThat(button.get(IAM_MENU.PARENT_ID)).isEqualTo(
                dsl.select(IAM_MENU.ID).from(IAM_MENU)
                    .where(IAM_MENU.TENANT_ID.eq(tenantId)
                        .and(IAM_MENU.ROUTE_NAME.eq("MerchantProfile")))
                    .fetchSingle(IAM_MENU.ID));
            assertThat(button.get(IAM_MENU.MENU_TYPE)).isEqualTo("BUTTON");
            assertThat(button.get(IAM_MENU.ROUTE_PATH)).isNull();
            assertThat(button.get(IAM_MENU.COMPONENT_PATH)).isNull();
            assertThat(button.get(IAM_MENU.SYSTEM_MANAGED)).isTrue();
        });
        assertThat(dsl.select(IAM_MENU.AUTH_CODE).from(IAM_MENU)
            .where(IAM_MENU.TENANT_ID.eq(tenantId)
                .and(IAM_MENU.MENU_TYPE.eq("BUTTON")))
            .orderBy(IAM_MENU.SORT_ORDER)
            .fetch(IAM_MENU.AUTH_CODE))
            .containsExactly("merchant:submit", "merchant:resubmit");
        assertThat(dsl.select(IAM_MENU.ROUTE_NAME).from(IAM_ROLE_MENU)
            .join(IAM_MENU).on(IAM_MENU.TENANT_ID.eq(IAM_ROLE_MENU.TENANT_ID)
                .and(IAM_MENU.ID.eq(IAM_ROLE_MENU.MENU_ID)))
            .where(IAM_ROLE_MENU.TENANT_ID.eq(tenantId)
                .and(IAM_ROLE_MENU.ROLE_ID.eq(protectedRoleId)))
            .orderBy(IAM_MENU.SORT_ORDER)
            .fetch(IAM_MENU.ROUTE_NAME))
            .containsExactly("MerchantProfile", "MerchantSubmit", "MerchantResubmit");

        long memberRoleId = dsl.select(IAM_ROLE.ID).from(IAM_ROLE)
            .where(IAM_ROLE.TENANT_ID.eq(tenantId)
                .and(IAM_ROLE.ROLE_CODE.eq("tenant-member")))
            .fetchSingle(IAM_ROLE.ID);
        assertThat(protectedRolePermissionCodes(tenantId, memberRoleId))
            .containsExactly("backoffice:merchant-access");
        assertThat(dsl.fetchCount(IAM_ROLE_MENU,
            IAM_ROLE_MENU.TENANT_ID.eq(tenantId)
                .and(IAM_ROLE_MENU.ROLE_ID.eq(memberRoleId))))
            .isZero();
    }

    @Test
    void newAgentTenantReceivesNoMerchantCapabilityOrMenu() {
        var reservation = repository.reserve(PLATFORM_ADMIN, AccountDomain.AGENT,
            command("agent", TenantType.AGENT, UUID.randomUUID()));
        long tenantId = reservation.tenantId();
        long protectedRoleId = protectedRoleId(tenantId);

        assertThat(protectedRolePermissionCodes(tenantId, protectedRoleId)).containsExactly(
            "backoffice:agent-access", "dictionary-data:view");
        assertThat(dsl.fetchCount(IAM_MENU,
            IAM_MENU.TENANT_ID.eq(tenantId)
                .and(IAM_MENU.ROUTE_NAME.like("Merchant%")))).isZero();
        assertThat(dsl.fetchCount(IAM_ROLE_MENU,
            IAM_ROLE_MENU.TENANT_ID.eq(tenantId)
                .and(IAM_ROLE_MENU.ROLE_ID.eq(protectedRoleId)))).isZero();
    }

    @ParameterizedTest
    @MethodSource("tenantDomains")
    void newTenantProtectedAdministratorReceivesReadOnlyDictionaryCapability(
        AccountDomain domain, TenantType tenantType, String prefix) {
        var reservation = repository.reserve(PLATFORM_ADMIN, domain,
            command(prefix, tenantType, UUID.randomUUID()));
        long tenantId = reservation.tenantId();
        long protectedRoleId = protectedRoleId(tenantId);

        List<String> permissionCodes = protectedRolePermissionCodes(tenantId, protectedRoleId);
        assertThat(permissionCodes).contains(domain.accessPermissionCode(), "dictionary-data:view");
        assertThat(permissionCodes).noneMatch(code ->
            code.startsWith("dictionary:")
                || code.matches("dictionary-data:(create|update|delete)"));

        assertThat(dsl.fetchCount(IAM_GRANT_DIMENSION,
            IAM_GRANT_DIMENSION.GRANT_ID.in(
                dsl.select(IAM_ROLE_GRANT.ID)
                    .from(IAM_ROLE_GRANT)
                    .join(IAM_PERMISSION).on(IAM_PERMISSION.ID.eq(IAM_ROLE_GRANT.PERMISSION_ID))
                    .where(IAM_ROLE_GRANT.TENANT_ID.eq(tenantId)
                        .and(IAM_ROLE_GRANT.ROLE_ID.eq(protectedRoleId))
                        .and(IAM_PERMISSION.PERMISSION_CODE.eq("dictionary-data:view"))))
                .and(IAM_GRANT_DIMENSION.DIMENSION_CODE.eq("TENANT"))
                .and(IAM_GRANT_DIMENSION.SCOPE_MODE.eq("TENANT_ALL"))))
            .isOne();

        assertThat(dsl.fetchCount(IAM_MENU,
            IAM_MENU.TENANT_ID.eq(tenantId)
                .and(IAM_MENU.ROUTE_NAME.in(
                    "SystemDictionaryDataIndex", "SystemDictionaryData", "DictionaryDataView"))))
            .isZero();
        assertThat(dsl.fetchCount(IAM_MENU,
            IAM_MENU.TENANT_ID.eq(tenantId)
                .and(IAM_MENU.ROUTE_NAME.in(
                    "SystemDictionary", "DictionaryCreate", "DictionaryUpdate", "DictionaryDelete",
                    "DictionaryDataCreate", "DictionaryDataUpdate", "DictionaryDataDelete"))))
            .isZero();
    }

    @Test
    void missingCanonicalDictionaryPermissionRollsBackTheWholeTenantReservation() {
        UUID idempotencyKey = UUID.randomUUID();
        dsl.update(IAM_PERMISSION)
            .set(IAM_PERMISSION.STATUS, "DISABLED")
            .where(IAM_PERMISSION.PERMISSION_CODE.eq("dictionary-data:view"))
            .execute();
        int[] before = tableCounts();

        assertThatThrownBy(() -> repository.reserve(PLATFORM_ADMIN, AccountDomain.MERCHANT,
            command("rollback-merchant", TenantType.DIRECT_MERCHANT, idempotencyKey)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Canonical dictionary data view permission is missing");

        assertThat(tableCounts()).containsExactly(before);
        assertThat(dsl.fetchCount(IAM_TENANT,
            IAM_TENANT.TENANT_CODE.eq("rollback-merchant-new"))).isZero();
        assertThat(dsl.fetchCount(IAM_IDENTITY_INVITATION,
            IAM_IDENTITY_INVITATION.IDEMPOTENCY_KEY.eq(idempotencyKey))).isZero();
    }

    @Test
    void missingCanonicalMerchantPermissionRollsBackTheWholeTenantReservation() {
        UUID idempotencyKey = UUID.randomUUID();
        dsl.update(IAM_PERMISSION)
            .set(IAM_PERMISSION.STATUS, "DISABLED")
            .where(IAM_PERMISSION.PERMISSION_CODE.eq("merchant:submit"))
            .execute();
        int[] before = tableCounts();

        assertThatThrownBy(() -> repository.reserve(PLATFORM_ADMIN, AccountDomain.MERCHANT,
            command("rollback-mch", TenantType.DIRECT_MERCHANT, idempotencyKey)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("Canonical Merchant self-service permission is missing: merchant:submit");

        assertThat(tableCounts()).containsExactly(before);
        assertThat(dsl.fetchCount(IAM_TENANT,
            IAM_TENANT.TENANT_CODE.eq("rollback-mch-new"))).isZero();
        assertThat(dsl.fetchCount(IAM_IDENTITY_INVITATION,
            IAM_IDENTITY_INVITATION.IDEMPOTENCY_KEY.eq(idempotencyKey))).isZero();
    }

    private TenantBootstrapCommand command(String prefix, TenantType tenantType, UUID idempotencyKey) {
        return new TenantBootstrapCommand(prefix + "-new", prefix + " New", tenantType,
            prefix + "-new.admin.example.test", "admin@" + prefix + ".example.test",
            prefix + " Administrator", idempotencyKey);
    }

    private void seedPlatformAdministrator() {
        dsl.execute("""
            INSERT INTO iam_tenant(id,tenant_code,tenant_name,tenant_type,status,account_domain)
            VALUES (98001,'bootstrap-platform','Bootstrap Platform','PLATFORM','ACTIVE','PLATFORM');
            INSERT INTO iam_user(
                id,idp_issuer,idp_subject,display_name,status,account_domain,idp_provisioning_status
            ) VALUES (98002,'https://idp.example.test/realms/PLATFORM','bootstrap-platform-admin',
                      'Bootstrap Platform Administrator','ACTIVE','PLATFORM','PROVISIONED');
            INSERT INTO iam_authentication_credential(
                user_id,username,password_hash,status,account_domain
            ) VALUES (98002,'bootstrap-platform-admin@example.test',NULL,'ACTIVE','PLATFORM');
            INSERT INTO iam_membership(id,tenant_id,user_id,status,account_domain)
            VALUES (98003,98001,98002,'ACTIVE','PLATFORM');
            INSERT INTO iam_role(
                id,tenant_id,role_code,role_name,applicable_tenant_type,
                assignable,system_role,status
            ) VALUES (98004,98001,'bootstrap-platform-system-admin',
                      'Bootstrap Platform System Administrator','PLATFORM',false,true,'ACTIVE');
            INSERT INTO iam_membership_role(tenant_id,membership_id,role_id,assigned_by)
            VALUES (98001,98003,98004,98003);
            """);
    }

    private long protectedRoleId(long tenantId) {
        return dsl.select(IAM_ROLE.ID)
            .from(IAM_ROLE)
            .where(IAM_ROLE.TENANT_ID.eq(tenantId)
                .and(IAM_ROLE.SYSTEM_ROLE.isTrue())
                .and(IAM_ROLE.ASSIGNABLE.isFalse()))
            .fetchSingle(IAM_ROLE.ID);
    }

    private List<String> protectedRolePermissionCodes(long tenantId, long protectedRoleId) {
        return dsl.select(IAM_PERMISSION.PERMISSION_CODE)
            .from(IAM_ROLE_GRANT)
            .join(IAM_PERMISSION).on(IAM_PERMISSION.ID.eq(IAM_ROLE_GRANT.PERMISSION_ID))
            .where(IAM_ROLE_GRANT.TENANT_ID.eq(tenantId)
                .and(IAM_ROLE_GRANT.ROLE_ID.eq(protectedRoleId)))
            .orderBy(IAM_PERMISSION.PERMISSION_CODE)
            .fetch(IAM_PERMISSION.PERMISSION_CODE);
    }

    private int[] tableCounts() {
        return new int[]{
            dsl.fetchCount(IAM_TENANT),
            dsl.fetchCount(IAM_TENANT_ENTRY_HOST),
            dsl.fetchCount(IAM_ROLE),
            dsl.fetchCount(IAM_ROLE_GRANT),
            dsl.fetchCount(IAM_GRANT_DIMENSION),
            dsl.fetchCount(IAM_MENU),
            dsl.fetchCount(IAM_ROLE_MENU),
            dsl.fetchCount(IAM_IDENTITY_INVITATION),
            dsl.fetchCount(IAM_IDENTITY_INVITATION_ROLE),
            dsl.fetchCount(IAM_AUDIT_EVENT)
        };
    }
}
