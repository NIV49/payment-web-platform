package com.niv.payment.adminapi;

import cn.dev33.satoken.config.SaTokenConfig;
import jakarta.servlet.http.Cookie;
import com.niv.payment.permission.domain.AdministrationActor;
import com.niv.payment.permission.persistence.repository.JooqRoleAdministrationRepository;
import com.niv.payment.permission.service.IdentityAdministrationService;
import com.niv.payment.permission.service.IdentityModels;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import com.jayway.jsonpath.JsonPath;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(classes = AdminApiApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("local")
class AdminApiContractIntegrationTest {
    private static final String ORIGIN = "http://localhost:5999";
    private static final String ADMIN_LOGIN_INPUT = "Admin-Test-Password-2026";
    private static final String RESTRICTED_LOGIN_INPUT = "Low-Test-Password-2026";
    private static final String LOCAL_SERVICE_SENTINEL = "disabled";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:18.4-alpine@sha256:9a8afca54e7861fd90fab5fdf4c42477a6b1cb7d293595148e674e0a3181de15").asCompatibleSubstituteFor("postgres"))
        .withDatabaseName("payment_platform").withUsername("payment_dev").withPassword("payment_dev");

    @Container
    static final GenericContainer<?> VALKEY = new GenericContainer<>("valkey/valkey:7.2.13-alpine@sha256:ac32d5e70f29e2be83384f5173180911b666c79a0e91ac0d074de5771638ed91")
        .withCommand("valkey-server", "--requirepass", LOCAL_SERVICE_SENTINEL)
        .withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", VALKEY::getHost);
        registry.add("spring.data.redis.port", () -> VALKEY.getMappedPort(6379));
        registry.add("PAYMENT_BOOTSTRAP_PASSWORD", () -> ADMIN_LOGIN_INPUT);
        registry.add("payment.security.allowed-origins", () -> ORIGIN);
        registry.add("sa-token.cookie.secure", () -> false);
    }

    @Autowired MockMvc mvc;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;
    @Autowired BCryptPasswordEncoder passwords;
    @Autowired JooqRoleAdministrationRepository roles;
    @Autowired ApplicationContext applicationContext;
    @Autowired SaTokenConfig saTokenConfig;

    @BeforeEach
    void seedRestrictedUserAndCrossTenantMembership() {
        jdbc.update("""
            UPDATE iam_membership SET status='ACTIVE',row_version=0,session_version=session_version+1
             WHERE tenant_id=1 AND id=1000
            """);
        jdbc.update("DELETE FROM iam_membership_role WHERE tenant_id=1 AND membership_id=1000");
        jdbc.update("""
            INSERT INTO iam_membership_role(tenant_id,membership_id,role_id,assigned_by)
            VALUES(1,1000,2000,1000)
            """);
        jdbc.update("""
            INSERT INTO iam_user(id,idp_issuer,idp_subject,display_name,status)
            VALUES(801,'local','restricted','Restricted User','ACTIVE') ON CONFLICT(id) DO NOTHING
            """);
        jdbc.update("""
            INSERT INTO iam_membership(id,tenant_id,user_id,department_id,status)
            VALUES(802,1,801,10,'ACTIVE') ON CONFLICT(id) DO NOTHING
            """);
        jdbc.update("""
            UPDATE iam_membership SET status='ACTIVE',row_version=0,session_version=session_version+1
             WHERE tenant_id=1 AND id=802
            """);
        jdbc.update("""
            INSERT INTO iam_authentication_credential(user_id,username,password_hash,status)
            VALUES(801,'restricted',?,'ACTIVE')
            ON CONFLICT(user_id) DO UPDATE SET password_hash=excluded.password_hash,status='ACTIVE'
            """, passwords.encode(RESTRICTED_LOGIN_INPUT));
        jdbc.update("""
            INSERT INTO iam_tenant(id,tenant_code,tenant_name,tenant_type,status)
            VALUES(2,'merchant-two','Merchant Two','DIRECT_MERCHANT','ACTIVE') ON CONFLICT(id) DO NOTHING
            """);
        jdbc.update("""
            INSERT INTO iam_department(id,tenant_id,parent_id,department_code,department_name,status)
            VALUES(20,2,NULL,'root','Root','ACTIVE') ON CONFLICT(id) DO NOTHING
            """);
        jdbc.update("""
            INSERT INTO iam_membership(id,tenant_id,user_id,department_id,status)
            VALUES(803,2,801,20,'ACTIVE') ON CONFLICT(id) DO NOTHING
            """);
        jdbc.update("UPDATE iam_membership SET status='DISABLED' WHERE tenant_id=2 AND id=803");
    }

    @Test
    void unauthenticatedRequestReturns401Envelope() throws Exception {
        mvc.perform(get("/api/user/info"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(40102))
            .andExpect(jsonPath("$.error").value("SESSION_INVALID"))
            .andExpect(jsonPath("$.traceId").isString());
    }

    @Test
    void loginUsesOpaqueMarkerAndHardenedCookie() throws Exception {
        String cookie = login("admin", ADMIN_LOGIN_INPUT);
        assertThat(cookie).isNotBlank();
    }

    @Test
    void securityCriticalSaTokenConfigurationIsBoundWithoutPostStartupRunner() {
        assertThat(applicationContext.containsBean("saTokenSecurityInitializer")).isFalse();
        assertThat(saTokenConfig.getTokenName()).isEqualTo("PAYMENT_SESSION");
        assertThat(saTokenConfig.getTimeout()).isEqualTo(28_800L);
        assertThat(saTokenConfig.getActiveTimeout()).isEqualTo(1_800L);
        assertThat(saTokenConfig.getIsConcurrent()).isFalse();
        assertThat(saTokenConfig.getIsShare()).isFalse();
        assertThat(saTokenConfig.getIsReadCookie()).isTrue();
        assertThat(saTokenConfig.getIsReadHeader()).isFalse();
        assertThat(saTokenConfig.getIsReadBody()).isFalse();
        assertThat(saTokenConfig.getIsWriteHeader()).isFalse();
        assertThat(saTokenConfig.getIsLastingCookie()).isTrue();
        assertThat(saTokenConfig.getRightNowCreateTokenSession()).isTrue();
        assertThat(saTokenConfig.getCookie().getHttpOnly()).isTrue();
        assertThat(saTokenConfig.getCookie().getSecure()).isFalse();
        assertThat(saTokenConfig.getCookie().getSameSite()).isEqualTo("Strict");
        assertThat(saTokenConfig.getCookie().getPath()).isEqualTo("/");
    }

    @Test
    void loginFailureDoesNotRevealWhetherUsernameExists() throws Exception {
        String known = mvc.perform(post("/api/auth/login").header("Origin", ORIGIN)
                .contentType("application/json").content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
            .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        String unknown = mvc.perform(post("/api/auth/login").header("Origin", ORIGIN)
                .contentType("application/json").content("{\"username\":\"unknown\",\"password\":\"wrong\"}"))
            .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        assertThat(known).contains("INVALID_CREDENTIALS", "Invalid username or password");
        assertThat(unknown).contains("INVALID_CREDENTIALS", "Invalid username or password");
    }

    @Test
    void untrustedForwardedForCannotRotateTheLoginRateLimitBucket() throws Exception {
        for (int attempt = 0; attempt < 5; attempt++) {
            mvc.perform(post("/api/auth/login")
                    .with(request -> {
                        request.setRemoteAddr("198.51.100.77");
                        return request;
                    })
                    .header("Origin", ORIGIN)
                    .header("X-Forwarded-For", "203.0.113." + (attempt + 1))
                    .contentType("application/json")
                    .content("{\"username\":\"forwarded-header-probe\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized());
        }

        mvc.perform(post("/api/auth/login")
                .with(request -> {
                    request.setRemoteAddr("198.51.100.77");
                    return request;
                })
                .header("Origin", ORIGIN)
                .header("X-Forwarded-For", "203.0.113.250")
                .contentType("application/json")
                .content("{\"username\":\"forwarded-header-probe\",\"password\":\"wrong\"}"))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.error").value("LOGIN_RATE_LIMITED"));
    }

    @Test
    void oversizedJsonRequestIsRejectedBeforeControllerDispatch() throws Exception {
        String body = "{\"username\":\"admin\",\"password\":\""
            + "x".repeat(262_144) + "\"}";
        mvc.perform(post("/api/auth/login").header("Origin", ORIGIN)
                .contentType("application/json").content(body))
            .andExpect(status().isPayloadTooLarge())
            .andExpect(jsonPath("$.code").value(41301))
            .andExpect(jsonPath("$.error").value("PAYLOAD_TOO_LARGE"));
    }

    @Test
    void multipleActiveMembershipsRequireExplicitTenantSelection() throws Exception {
        jdbc.update("UPDATE iam_membership SET status='ACTIVE' WHERE tenant_id=2 AND id=803");

        mvc.perform(post("/api/auth/login").header("Origin", ORIGIN).contentType("application/json")
                .content("{\"username\":\"restricted\",\"password\":\"" + RESTRICTED_LOGIN_INPUT + "\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("INVALID_CREDENTIALS"));

        mvc.perform(post("/api/auth/login").header("Origin", ORIGIN).contentType("application/json")
                .content("{\"username\":\"restricted\",\"password\":\"" + RESTRICTED_LOGIN_INPUT
                    + "\",\"tenantId\":\"1\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.accessToken").value("cookie-session"));

        mvc.perform(post("/api/auth/login").header("Origin", ORIGIN).contentType("application/json")
                .content("{\"username\":\"restricted\",\"password\":\"" + RESTRICTED_LOGIN_INPUT
                    + "\",\"tenantId\":\"999\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("INVALID_CREDENTIALS"));
    }

    @Test
    void currentUserCodesAndDynamicMenuMatchVbenContract() throws Exception {
        Cookie cookie = cookie(login("admin", ADMIN_LOGIN_INPUT));
        mvc.perform(get("/api/user/info").cookie(cookie)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.userId").value("100"))
            .andExpect(jsonPath("$.data.homePath").value("/dashboard"));
        mvc.perform(get("/api/auth/codes").cookie(cookie)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(19))
            .andExpect(jsonPath("$.data[?(@ == 'user:assign-role')]").exists())
            .andExpect(jsonPath("$.data[?(@ == 'role:grant-update')]").exists())
            .andExpect(jsonPath("$.data[?(@ == 'menu:manage')]").doesNotExist());
        String dynamicMenuBody = mvc.perform(get("/api/menu/all").cookie(cookie)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].pid").value("0"))
            .andExpect(jsonPath("$.data[0].component").doesNotExist())
            .andExpect(jsonPath("$.data[0].meta.title").value("page.dashboard.title"))
            .andExpect(jsonPath("$.data[0].redirect").value("/dashboard/analytics"))
            .andExpect(jsonPath("$.data[0].children[0].component").value("/dashboard/analytics/index"))
            .andExpect(jsonPath("$.data[1].component").doesNotExist())
            .andExpect(jsonPath("$.data[1].meta.title").value("system.title"))
            .andExpect(jsonPath("$.data[1].children[0].component").value("/system/user/list"))
            .andExpect(jsonPath("$.data[1].children[0].meta.title").value("system.user.title"))
            .andExpect(jsonPath("$.data[1].children[1].meta.title").value("system.role.title"))
            .andExpect(jsonPath("$.data[1].children[2].meta.title").value("system.menu.title"))
            .andExpect(jsonPath("$.data[1].children[3].meta.title").value("system.dept.title"))
            .andExpect(jsonPath("$.data[1].children[3].name").value("SystemDept"))
            .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(dynamicMenuBody, "$.data..authCode")).isEmpty();
        mvc.perform(get("/api/system/user/list?page=1&pageSize=20&status=1").cookie(cookie))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].id").isString());
        mvc.perform(get("/api/system/role/list?page=1&pageSize=200&status=1").cookie(cookie))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].status").value(1))
            .andExpect(jsonPath("$.data.items[0].rowVersion").isNumber())
            .andExpect(jsonPath("$.data.items[0].systemRole").isBoolean())
            .andExpect(jsonPath("$.data.items[0].assignable").isBoolean());
        mvc.perform(get("/api/system/dept/list").cookie(cookie))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].pid").value("0"))
            .andExpect(jsonPath("$.data[0].rowVersion").isNumber());
        String administrationMenuBody = mvc.perform(get("/api/system/menu/list").cookie(cookie))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].component").doesNotExist())
            .andExpect(jsonPath("$.data[1].component").doesNotExist())
            .andExpect(jsonPath("$.data[0].rowVersion").isNumber())
            .andExpect(jsonPath("$.data[1].meta.title").value("system.title"))
            .andExpect(jsonPath("$.data[1].children[0].component").value("/system/user/list"))
            .andExpect(jsonPath("$.data[1].children[0].children[0].type").value("button"))
            .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<List<String>>read(administrationMenuBody, "$.data..authCode"))
            .containsExactlyInAnyOrder(
                "user:view", "user:create", "user:update", "user:delete", "user:disable", "user:assign-role",
                "role:view", "role:create", "role:update", "role:delete",
                "menu:view", "menu:manage", "menu:create", "menu:update", "menu:delete",
                "department:view", "department:manage", "department:create", "department:update",
                "department:delete", "role:grant-update");
    }

    @Test
    void roleGrantEndpointsKeepPresentationMenusSeparateAndUseTheFrozenDimensionsContract() throws Exception {
        Cookie cookie = cookie(login("admin", ADMIN_LOGIN_INPUT));
        mvc.perform(get("/api/v1/iam/permissions/grantable").cookie(cookie))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(18))
            .andExpect(jsonPath("$.data[0].permissionCode").isString())
            .andExpect(jsonPath("$.data[0].riskLevel").value("NORMAL"))
            .andExpect(jsonPath("$.data[0].requiredDimensions[0].code").value("TENANT"))
            .andExpect(jsonPath("$.data[0].requiredDimensions[0].allowedModes[0]").value("TENANT_ALL"))
            .andExpect(jsonPath("$.data[0].allowedScopeModes").doesNotExist());

        String roleBody = mvc.perform(post("/api/system/role").cookie(cookie).header("Origin", ORIGIN)
                .contentType("application/json")
                .content("{\"name\":\"Grant Contract Role\",\"menuIds\":[\"6001\"],\"status\":1}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String roleId = JsonPath.read(roleBody, "$.data.id");
        List<Long> menuIdsBefore = jdbc.queryForList(
            "SELECT menu_id FROM iam_role_menu WHERE tenant_id=1 AND role_id=? ORDER BY menu_id",
            Long.class, Long.parseLong(roleId));

        mvc.perform(get("/api/v1/iam/roles/" + roleId + "/grants").cookie(cookie))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.roleId").value(roleId))
            .andExpect(jsonPath("$.data.roleVersion").value(0))
            .andExpect(jsonPath("$.data.editable").value(true))
            .andExpect(jsonPath("$.data.grants.length()").value(0));

        mvc.perform(put("/api/v1/iam/roles/" + roleId + "/grants").cookie(cookie)
                .header("Origin", ORIGIN).contentType("application/json")
                .content("""
                    {"expectedVersion":0,"reason":"least privilege acceptance",
                     "grants":[{"grantKey":"user-view","permissionCode":"user:view",
                       "dimensions":[{"code":"TENANT","mode":"TENANT_ALL","targets":[]}]}]}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.roleVersion").value(1))
            .andExpect(jsonPath("$.data.grants[0].permissionCode").value("user:view"))
            .andExpect(jsonPath("$.data.grants[0].dimensions[0].code").value("TENANT"))
            .andExpect(jsonPath("$.data.grants[0].dimensions[0].mode").value("TENANT_ALL"))
            .andExpect(jsonPath("$.data.grants[0].dimensions[0].targets.length()").value(0))
            .andExpect(jsonPath("$.data.grants[0].dimension").doesNotExist());

        assertThat(jdbc.queryForList(
            "SELECT menu_id FROM iam_role_menu WHERE tenant_id=1 AND role_id=? ORDER BY menu_id",
            Long.class, Long.parseLong(roleId))).isEqualTo(menuIdsBefore);
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM iam_audit_event
             WHERE tenant_id=1 AND target_type='ROLE_GRANTS' AND target_ref=?
            """, Long.class, roleId)).isOne();
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM iam_permission_change_outbox
             WHERE tenant_id=1 AND aggregate_type='ROLE_GRANTS' AND aggregate_ref=?
            """, Long.class, roleId)).isOne();

        mvc.perform(put("/api/v1/iam/roles/" + roleId + "/grants").cookie(cookie)
                .header("Origin", ORIGIN).contentType("application/json")
                .content("{\"expectedVersion\":0,\"reason\":\"stale\",\"grants\":[]}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("OPTIMISTIC_LOCK_CONFLICT"));
        mvc.perform(put("/api/v1/iam/roles/2000/grants").cookie(cookie)
                .header("Origin", ORIGIN).contentType("application/json")
                .content("{\"expectedVersion\":0,\"reason\":\"forbidden system role\",\"grants\":[]}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void roleGrantEndpointsRecheckSystemRoleAfterTheHttpPermissionGate() throws Exception {
        mvc.perform(get("/api/v1/iam/permissions/grantable"))
            .andExpect(status().isUnauthorized());

        long roleId = jdbc.queryForObject("SELECT nextval('iam_id_seq')", Long.class);
        long roleViewGrant = jdbc.queryForObject("SELECT nextval('iam_id_seq')", Long.class);
        long grantUpdateGrant = jdbc.queryForObject("SELECT nextval('iam_id_seq')", Long.class);
        long roleViewDimension = jdbc.queryForObject("SELECT nextval('iam_id_seq')", Long.class);
        long grantUpdateDimension = jdbc.queryForObject("SELECT nextval('iam_id_seq')", Long.class);
        jdbc.update("""
            INSERT INTO iam_role(id,tenant_id,role_code,role_name,applicable_tenant_type,
                                 assignable,system_role,status)
            VALUES (?,1,?,?,'PLATFORM',true,false,'ACTIVE')
            """, roleId, "grant-gate-" + roleId, "Grant Gate " + roleId);
        jdbc.update("INSERT INTO iam_membership_role(tenant_id,membership_id,role_id) VALUES(1,802,?)", roleId);
        jdbc.update("""
            INSERT INTO iam_role_grant(id,tenant_id,role_id,permission_id,grant_key,status)
            VALUES (?,1,?,3007,'gate-role-view','ACTIVE'),
                   (?,1,?,3021,'gate-grant-update','ACTIVE')
            """, roleViewGrant, roleId, grantUpdateGrant, roleId);
        jdbc.update("""
            INSERT INTO iam_grant_dimension(id,grant_id,dimension_code,scope_mode)
            VALUES (?,?,'TENANT','TENANT_ALL'),(?,?,'TENANT','TENANT_ALL')
            """, roleViewDimension, roleViewGrant, grantUpdateDimension, grantUpdateGrant);
        try {
            Cookie restricted = cookie(login("restricted", RESTRICTED_LOGIN_INPUT));
            mvc.perform(get("/api/v1/iam/permissions/grantable").cookie(restricted))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("PERMISSION_DENIED"));
        } finally {
            jdbc.update("DELETE FROM iam_membership_role WHERE tenant_id=1 AND membership_id=802 AND role_id=?", roleId);
            jdbc.update("DELETE FROM iam_role WHERE tenant_id=1 AND id=?", roleId);
            jdbc.update("UPDATE iam_membership SET permission_version=permission_version+1 WHERE tenant_id=1 AND id=802");
        }
    }

    @Test
    void roleGrantEndpointsAcceptAnActorWithMultipleActiveSystemRoles() throws Exception {
        Cookie cookie = cookie(login("admin", ADMIN_LOGIN_INPUT));
        long roleId = jdbc.queryForObject("SELECT nextval('iam_id_seq')", Long.class);
        jdbc.update("""
            INSERT INTO iam_role(id,tenant_id,role_code,role_name,applicable_tenant_type,
                                 assignable,system_role,status)
            VALUES (?,1,?,?,'PLATFORM',false,true,'ACTIVE')
            """, roleId, "second-system-role-" + roleId, "Second System Role " + roleId);
        jdbc.update("INSERT INTO iam_membership_role(tenant_id,membership_id,role_id) VALUES(1,1000,?)", roleId);
        try {
            mvc.perform(get("/api/v1/iam/permissions/grantable").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(18));
        } finally {
            jdbc.update("DELETE FROM iam_membership_role WHERE tenant_id=1 AND membership_id=1000 AND role_id=?",
                roleId);
            jdbc.update("DELETE FROM iam_role WHERE tenant_id=1 AND id=?", roleId);
        }
    }

    @Test
    void unsupportedExistingGrantMakesTheRoleReadOnlyAndRejectsReplacement() throws Exception {
        Cookie cookie = cookie(login("admin", ADMIN_LOGIN_INPUT));
        String roleBody = mvc.perform(post("/api/system/role").cookie(cookie).header("Origin", ORIGIN)
                .contentType("application/json")
                .content("{\"name\":\"Unsupported Grant Role\",\"menuIds\":[],\"status\":1}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String roleId = JsonPath.read(roleBody, "$.data.id");
        long grantId = jdbc.queryForObject("SELECT nextval('iam_id_seq')", Long.class);
        long dimensionId = jdbc.queryForObject("SELECT nextval('iam_id_seq')", Long.class);
        jdbc.update("""
            INSERT INTO iam_role_grant(id,tenant_id,role_id,permission_id,grant_key,status)
            VALUES (?,1,?,3021,'unsupported-admin-only','ACTIVE')
            """, grantId, Long.parseLong(roleId));
        jdbc.update("""
            INSERT INTO iam_grant_dimension(id,grant_id,dimension_code,scope_mode)
            VALUES (?,?,'TENANT','TENANT_ALL')
            """, dimensionId, grantId);

        mvc.perform(get("/api/v1/iam/roles/" + roleId + "/grants").cookie(cookie))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.editable").value(false));
        mvc.perform(put("/api/v1/iam/roles/" + roleId + "/grants").cookie(cookie)
                .header("Origin", ORIGIN).contentType("application/json")
                .content("{\"expectedVersion\":0,\"reason\":\"must reject unsupported\",\"grants\":[]}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.error").value("DATA_CONFLICT"));
    }

    @Test
    void outboxFailureRollsBackRoleGrantReplacementAuditAndVersion() throws Exception {
        Cookie cookie = cookie(login("admin", ADMIN_LOGIN_INPUT));
        String roleBody = mvc.perform(post("/api/system/role").cookie(cookie).header("Origin", ORIGIN)
                .contentType("application/json")
                .content("{\"name\":\"Grant Rollback Role\",\"menuIds\":[\"6001\"],\"status\":1}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String roleId = JsonPath.read(roleBody, "$.data.id");
        jdbc.execute("""
            CREATE FUNCTION fail_selected_role_grant_outbox()
            RETURNS trigger LANGUAGE plpgsql AS $$
            BEGIN
                IF NEW.aggregate_type='ROLE_GRANTS' AND NEW.aggregate_ref='%s' THEN
                    RAISE EXCEPTION 'forced role grant outbox failure';
                END IF;
                RETURN NEW;
            END;
            $$
            """.formatted(roleId));
        jdbc.execute("""
            CREATE TRIGGER trg_fail_selected_role_grant_outbox
            BEFORE INSERT ON iam_permission_change_outbox
            FOR EACH ROW EXECUTE FUNCTION fail_selected_role_grant_outbox()
            """);
        try {
            mvc.perform(put("/api/v1/iam/roles/" + roleId + "/grants").cookie(cookie)
                    .header("Origin", ORIGIN).contentType("application/json")
                    .content("""
                        {"expectedVersion":0,"reason":"rollback acceptance",
                         "grants":[{"grantKey":"user-view","permissionCode":"user:view",
                           "dimensions":[{"code":"TENANT","mode":"TENANT_ALL","targets":[]}]}]}
                        """))
                .andExpect(status().isInternalServerError());
        } finally {
            jdbc.execute("DROP TRIGGER trg_fail_selected_role_grant_outbox ON iam_permission_change_outbox");
            jdbc.execute("DROP FUNCTION fail_selected_role_grant_outbox()");
        }

        assertThat(jdbc.queryForObject("SELECT row_version FROM iam_role WHERE id=?", Long.class,
            Long.parseLong(roleId))).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM iam_role_grant WHERE tenant_id=1 AND role_id=?",
            Long.class, Long.parseLong(roleId))).isZero();
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM iam_audit_event
             WHERE tenant_id=1 AND target_type='ROLE_GRANTS' AND target_ref=?
            """, Long.class, roleId)).isZero();
        assertThat(jdbc.queryForObject("""
            SELECT count(*) FROM iam_permission_change_outbox
             WHERE tenant_id=1 AND aggregate_type='ROLE_GRANTS' AND aggregate_ref=?
            """, Long.class, roleId)).isZero();
    }

    @Test
    void dynamicMenuExcludesButtonsAndAddsOnlyTheAuthorizedRoutesActiveAncestors() throws Exception {
        Cookie cookie = cookie(login("admin", ADMIN_LOGIN_INPUT));
        long catalogId = jdbc.queryForObject("SELECT nextval('iam_id_seq')", Long.class);
        long pageId = jdbc.queryForObject("SELECT nextval('iam_id_seq')", Long.class);
        long siblingId = jdbc.queryForObject("SELECT nextval('iam_id_seq')", Long.class);
        long buttonId = jdbc.queryForObject("SELECT nextval('iam_id_seq')", Long.class);
        long disabledParentId = jdbc.queryForObject("SELECT nextval('iam_id_seq')", Long.class);
        long brokenPageId = jdbc.queryForObject("SELECT nextval('iam_id_seq')", Long.class);

        jdbc.update("""
            INSERT INTO iam_menu(id,tenant_id,parent_id,menu_type,menu_name,route_name,route_path,
                                 component_path,auth_code,sort_order,status,meta_json)
            VALUES
              (?,1,NULL,'DIRECTORY','Contract ancestor','ContractAncestor','/contract-ancestor',
               NULL,NULL,700,'ACTIVE','{"title":"system.title"}'::jsonb),
              (?,1,?,'PAGE','Contract page','ContractPage','/contract-page',
               '/system/user/list',NULL,701,'ACTIVE','{"title":"system.user.title"}'::jsonb),
              (?,1,?,'PAGE','Unauthorized sibling','UnauthorizedSibling','/unauthorized-sibling',
               '/system/user/list',NULL,702,'ACTIVE','{"title":"system.user.title"}'::jsonb),
              (?,1,?,'BUTTON','Route button','RouteButton',NULL,
               NULL,'user:create',703,'ACTIVE','{"title":"common.create"}'::jsonb),
              (?,1,NULL,'DIRECTORY','Disabled ancestor','DisabledAncestor','/disabled-ancestor',
               NULL,NULL,704,'DISABLED','{"title":"system.title"}'::jsonb),
              (?,1,?,'PAGE','Broken child page','BrokenChildPage','/broken-child-page',
               '/system/user/list',NULL,705,'ACTIVE','{"title":"system.user.title"}'::jsonb)
            """, catalogId, pageId, catalogId, siblingId, catalogId, buttonId, pageId,
            disabledParentId, brokenPageId, disabledParentId);
        jdbc.update("""
            INSERT INTO iam_role_menu(tenant_id,role_id,menu_id)
            VALUES(1,2000,?),(1,2000,?),(1,2000,?)
            """, pageId, buttonId, brokenPageId);

        try {
            String body = mvc.perform(get("/api/menu/all").cookie(cookie))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
            List<String> routeNames = JsonPath.read(body, "$..name");

            assertThat(routeNames)
                .contains("ContractAncestor", "ContractPage")
                .doesNotContain("UnauthorizedSibling", "RouteButton", "DisabledAncestor", "BrokenChildPage");
        } finally {
            jdbc.update("DELETE FROM iam_role_menu WHERE tenant_id=1 AND menu_id IN (?,?,?)",
                pageId, buttonId, brokenPageId);
            jdbc.update("DELETE FROM iam_menu WHERE tenant_id=1 AND id IN (?,?,?,?,?,?)",
                buttonId, siblingId, pageId, brokenPageId, catalogId, disabledParentId);
        }
    }

    @Test
    void unregisteredApiRoutesAreDeniedByDefault() throws Exception {
        Cookie cookie = cookie(login("admin", ADMIN_LOGIN_INPUT));

        mvc.perform(get("/api/not-registered").cookie(cookie))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("PERMISSION_DENIED"));
        mvc.perform(get("/api/system/user-archive").cookie(cookie))
            .andExpect(status().isForbidden())
            .andExpect(jsonPath("$.error").value("PERMISSION_DENIED"));
    }

    @Test
    void missingNonApiResourcesReturn404InsteadOf500() throws Exception {
        mvc.perform(get("/missing-resource"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.code").value(40401))
            .andExpect(jsonPath("$.error").value("RESOURCE_NOT_FOUND"));
    }

    @Test
    void menuWritesEnforceVbenTitleAndComponentContract() throws Exception {
        Cookie cookie = cookie(login("admin", ADMIN_LOGIN_INPUT));

        mvc.perform(post("/api/system/menu").cookie(cookie).header("Origin", ORIGIN)
                .contentType("application/json")
                .content("""
                    {"pid":"6000","type":"menu","name":"InvalidLiteralTitle","path":"/contract/literal-title",
                     "component":"/system/user/list","meta":{"title":"Literal title"},"status":1}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
        mvc.perform(post("/api/system/menu").cookie(cookie).header("Origin", ORIGIN)
                .contentType("application/json")
                .content("""
                    {"pid":"6000","type":"menu","name":"InvalidPageComponent","path":"/contract/component",
                     "component":"/system/not-registered","meta":{"title":"system.user.title"},"status":1}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
        mvc.perform(post("/api/system/menu").cookie(cookie).header("Origin", ORIGIN)
                .contentType("application/json")
                .content("""
                    {"pid":"0","type":"catalog","name":"LegacyLayoutComponent","path":"/contract/catalog",
                     "component":"BasicLayout","meta":{"title":"system.title"},"status":1}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
        mvc.perform(post("/api/system/menu").cookie(cookie).header("Origin", ORIGIN)
                .contentType("application/json")
                .content("""
                    {"pid":"6000","type":"menu","name":"InjectedPageMeta","path":"/contract/injected-page",
                     "component":"/system/user/list",
                     "meta":{"title":"system.user.title","iframeSrc":"javascript:alert(1)"},"status":1}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
        mvc.perform(post("/api/system/menu").cookie(cookie).header("Origin", ORIGIN)
                .contentType("application/json")
                .content("""
                    {"pid":"6001","type":"button","name":"InjectedButtonMeta","authCode":"user:create",
                     "meta":{"title":"common.create","link":"javascript:alert(1)"},"status":1}
                    """))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.error").value("INVALID_REQUEST"));
        mvc.perform(post("/api/system/menu").cookie(cookie).header("Origin", ORIGIN)
                .contentType("application/json")
                .content("""
                    {"pid":"6000","type":"link","name":"ContractDocumentation","path":"/contract/docs",
                     "component":"IFrameView","meta":{"title":"system.title","link":"https://docs.example.com"},
                     "status":1}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").isString());
    }
    @Test
    void auditEventUsesTheHttpRequestTraceId() throws Exception {
        Cookie cookie = cookie(login("admin", ADMIN_LOGIN_INPUT));
        String traceId = mvc.perform(post("/api/system/user").cookie(cookie).header("Origin", ORIGIN)
                .contentType("application/json")
                .content("""
                    {"username":"trace-audit-user","name":"Trace Audit User","deptId":"10",
                     "roleIds":[],"status":1,"userVersion":0}
                    """))
            .andExpect(status().isOk())
            .andReturn().getResponse().getHeader("X-Trace-Id");

        String auditTraceId = jdbc.queryForObject("""
            SELECT trace_id FROM iam_audit_event
             WHERE target_type='USER' AND action_code='CREATE'
             ORDER BY occurred_at DESC, id DESC LIMIT 1
            """, String.class);
        assertThat(auditTraceId).isEqualTo(traceId);
    }

    @Test
    void serverSidePepUsesTheVersionedGrantCache() throws Exception {
        Cookie cookie = cookie(login("admin", ADMIN_LOGIN_INPUT));
        long version = jdbc.queryForObject("""
            SELECT permission_version FROM iam_membership WHERE tenant_id=1 AND id=1000
            """, Long.class);
        String key = "iam:grant:1:1000:v" + version;
        redis.delete(key);

        mvc.perform(get("/api/system/user/list?page=1&pageSize=20").cookie(cookie))
            .andExpect(status().isOk());

        assertThat(redis.hasKey(key)).isTrue();
    }

    @Test
    void committedPermissionVersionChangeInvalidatesSessionAndClearsCookie() throws Exception {
        Cookie cookie = cookie(login("admin", ADMIN_LOGIN_INPUT));
        long version = jdbc.queryForObject("""
            SELECT permission_version FROM iam_membership WHERE tenant_id=1 AND id=1000
            """, Long.class);

        try {
            jdbc.update("""
                UPDATE iam_membership SET permission_version=permission_version+1
                 WHERE tenant_id=1 AND id=1000
                """);

            mvc.perform(get("/api/system/user/list?page=1&pageSize=20").cookie(cookie))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40102))
                .andExpect(jsonPath("$.error").value("SESSION_INVALID"))
                .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.allOf(
                    org.hamcrest.Matchers.containsString("PAYMENT_SESSION="),
                    org.hamcrest.Matchers.containsString("Max-Age=0"))));
        } finally {
            jdbc.update("""
                UPDATE iam_membership SET permission_version=?
                 WHERE tenant_id=1 AND id=1000
                """, version);
        }
    }


    @Test
    void everySystemEndpointRequiresServerSidePermission() throws Exception {
        Cookie cookie = cookie(login("restricted", RESTRICTED_LOGIN_INPUT));
        mvc.perform(get("/api/system/user/list?page=1&pageSize=20").cookie(cookie))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("PERMISSION_DENIED"));
        mvc.perform(patch("/api/system/role/2000/status").cookie(cookie).header("Origin", ORIGIN)
                .contentType("application/json").content("{\"status\":1,\"expectedVersion\":0}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void membershipPutRequiresStatusAndRoleCapabilitiesBeyondUserUpdate() throws Exception {
        long roleId = jdbc.queryForObject("SELECT nextval('iam_id_seq')", Long.class);
        long grantId = jdbc.queryForObject("SELECT nextval('iam_id_seq')", Long.class);
        long dimensionId = jdbc.queryForObject("SELECT nextval('iam_id_seq')", Long.class);
        jdbc.update("""
            INSERT INTO iam_role(id,tenant_id,role_code,role_name,applicable_tenant_type,
                                 assignable,system_role,status)
            VALUES(?,1,?,?,'PLATFORM',true,false,'ACTIVE')
            """, roleId, "restricted-update-" + roleId, "Restricted Update " + roleId);
        assertThat(jdbc.update("""
            INSERT INTO iam_role_grant(id,tenant_id,role_id,permission_id,grant_key,status)
            SELECT ?,1,?,id,'update-only','ACTIVE'
              FROM iam_permission WHERE permission_code='user:update'
            """, grantId, roleId)).isOne();
        jdbc.update("""
            INSERT INTO iam_grant_dimension(id,grant_id,dimension_code,scope_mode)
            VALUES(?,?,'TENANT','TENANT_ALL')
            """, dimensionId, grantId);
        jdbc.update("""
            INSERT INTO iam_membership_role(tenant_id,membership_id,role_id,assigned_by)
            VALUES(1,802,?,802)
            """, roleId);
        jdbc.update("""
            UPDATE iam_membership SET permission_version=permission_version+1
             WHERE tenant_id=1 AND id=802
            """);

        try {
            Cookie cookie = cookie(login("restricted", RESTRICTED_LOGIN_INPUT));
            var membershipBefore = jdbc.queryForMap("""
                SELECT department_id,status,row_version FROM iam_membership
                 WHERE tenant_id=1 AND id=802
                """);
            List<Long> rolesBefore = jdbc.queryForList("""
                SELECT role_id FROM iam_membership_role
                 WHERE tenant_id=1 AND membership_id=802 ORDER BY role_id
                """, Long.class);

            mvc.perform(put("/api/system/user/801").cookie(cookie).header("Origin", ORIGIN)
                    .contentType("application/json")
                    .content("{\"deptId\":\"10\",\"roleIds\":[\"" + roleId
                        + "\"],\"status\":0,\"userVersion\":0}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error").value("PERMISSION_DENIED"));

            assertThat(jdbc.queryForMap("""
                SELECT department_id,status,row_version FROM iam_membership
                 WHERE tenant_id=1 AND id=802
                """)).isEqualTo(membershipBefore);
            assertThat(jdbc.queryForList("""
                SELECT role_id FROM iam_membership_role
                 WHERE tenant_id=1 AND membership_id=802 ORDER BY role_id
                """, Long.class)).isEqualTo(rolesBefore);
        } finally {
            jdbc.update("DELETE FROM iam_membership_role WHERE tenant_id=1 AND membership_id=802 AND role_id=?", roleId);
            jdbc.update("DELETE FROM iam_grant_dimension WHERE id=?", dimensionId);
            jdbc.update("DELETE FROM iam_role_grant WHERE id=?", grantId);
            jdbc.update("DELETE FROM iam_role WHERE tenant_id=1 AND id=?", roleId);
            jdbc.update("""
                UPDATE iam_membership SET permission_version=permission_version+1
                 WHERE tenant_id=1 AND id=802
                """);
        }
    }

    @Test
    void disabledMembershipInvalidatesTheExistingSessionWith401() throws Exception {
        Cookie cookie = cookie(login("restricted", RESTRICTED_LOGIN_INPUT));
        jdbc.update("UPDATE iam_membership SET status='DISABLED' WHERE tenant_id=1 AND id=802");

        assertSessionInvalid(cookie);
    }

    @Test
    void disabledCredentialInvalidatesTheExistingSessionWith401() throws Exception {
        Cookie cookie = cookie(login("restricted", RESTRICTED_LOGIN_INPUT));
        try {
            jdbc.update("UPDATE iam_authentication_credential SET status='DISABLED' WHERE user_id=801");
            assertSessionInvalid(cookie);
        } finally {
            jdbc.update("UPDATE iam_authentication_credential SET status='ACTIVE' WHERE user_id=801");
        }
    }

    @Test
    void disabledUserInvalidatesTheExistingSessionWith401() throws Exception {
        Cookie cookie = cookie(login("restricted", RESTRICTED_LOGIN_INPUT));
        try {
            jdbc.update("UPDATE iam_user SET status='DISABLED' WHERE id=801");
            assertSessionInvalid(cookie);
        } finally {
            jdbc.update("UPDATE iam_user SET status='ACTIVE' WHERE id=801");
        }
    }

    @Test
    void disabledTenantInvalidatesTheExistingSessionWith401() throws Exception {
        Cookie cookie = cookie(login("restricted", RESTRICTED_LOGIN_INPUT));
        try {
            jdbc.update("UPDATE iam_tenant SET status='DISABLED' WHERE id=1");
            assertSessionInvalid(cookie);
        } finally {
            jdbc.update("UPDATE iam_tenant SET status='ACTIVE' WHERE id=1");
        }
    }

    @Test
    void ordinaryUserManagementCannotAssignOrRemoveSystemRoles() throws Exception {
        Cookie cookie = cookie(login("admin", ADMIN_LOGIN_INPUT));

        mvc.perform(post("/api/system/user").cookie(cookie).header("Origin", ORIGIN)
                .contentType("application/json")
                .content("""
                    {"username":"system-role-escalation","name":"Escalation Attempt","deptId":"10",
                     "roleIds":["2000"],"status":1,"userVersion":0}
                    """))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.error").value("IAM_ROLE_NOT_ASSIGNABLE"));

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/system/user/100").cookie(cookie).header("Origin", ORIGIN)
                .contentType("application/json")
                .content("""
                    {"username":"admin","name":"Platform Administrator","deptId":"10",
                     "roleIds":[],"status":1,"userVersion":0}
                    """))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.error").value("IAM_ROLE_NOT_ASSIGNABLE"));
    }

    @Test
    void lastActiveSystemAdministratorCannotBeDisabled() throws Exception {
        Cookie cookie = cookie(login("admin", ADMIN_LOGIN_INPUT));

        mvc.perform(patch("/api/system/user/100/status").cookie(cookie).header("Origin", ORIGIN)
                .contentType("application/json").content("{\"status\":0,\"userVersion\":0}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.error").value("IAM_LAST_ADMIN_PROTECTED"));
    }

    @Test
    void unreachableSystemAdministratorsDoNotDefeatLastAdministratorProtection() throws Exception {
        Cookie cookie = cookie(login("admin", ADMIN_LOGIN_INPUT));
        List<TestIdentity> unreachable = new ArrayList<>();
        // Simulate a corrupted/pre-V13 store. A healthy migrated database rejects this row,
        // while the repository still must fail closed if its database invariant is bypassed.
        jdbc.execute("""
            ALTER TABLE iam_authentication_credential
            DROP CONSTRAINT ck_iam_authentication_bcrypt_hash
            """);

        try {
            unreachable.add(seedSystemAdministrator("DISABLED", "ACTIVE", "not-used"));
            unreachable.add(seedSystemAdministrator("ACTIVE", "LOCKED", "not-used"));
            unreachable.add(seedSystemAdministrator("ACTIVE", "ACTIVE", null));
            unreachable.add(seedSystemAdministrator("ACTIVE", "ACTIVE", "not-a-bcrypt-hash"));
            mvc.perform(patch("/api/system/user/100/status").cookie(cookie).header("Origin", ORIGIN)
                    .contentType("application/json").content("{\"status\":0,\"userVersion\":0}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error").value("IAM_LAST_ADMIN_PROTECTED"));
            assertThat(jdbc.queryForMap("""
                SELECT status,row_version FROM iam_membership WHERE tenant_id=1 AND id=1000
                """)).containsEntry("status", "ACTIVE").containsEntry("row_version", 0L);
        } finally {
            for (TestIdentity identity : unreachable) {
                jdbc.update("DELETE FROM iam_membership_role WHERE tenant_id=1 AND membership_id=?",
                    identity.membershipId());
                jdbc.update("DELETE FROM iam_authentication_credential WHERE user_id=?", identity.userId());
                jdbc.update("DELETE FROM iam_membership WHERE tenant_id=1 AND id=?", identity.membershipId());
                jdbc.update("DELETE FROM iam_user WHERE id=?", identity.userId());
            }
            jdbc.execute("""
                ALTER TABLE iam_authentication_credential
                ADD CONSTRAINT ck_iam_authentication_bcrypt_hash
                CHECK (
                    password_hash IS NULL
                    OR password_hash ~ '^[$]2[aby][$](1[0-4])[$][./A-Za-z0-9]{53}$'
                )
                """);
        }
    }

    @Test
    void userRoleIdsAreRequiredButEmptyAssignmentsAreAccepted() throws Exception {
        Cookie cookie = cookie(login("admin", ADMIN_LOGIN_INPUT));
        String base = "{\"username\":\"no-roles\",\"name\":\"No Roles\",\"deptId\":\"10\",\"status\":1,\"userVersion\":0";
        mvc.perform(post("/api/system/user").cookie(cookie).header("Origin", ORIGIN)
                .contentType("application/json").content(base + "}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/system/user").cookie(cookie).header("Origin", ORIGIN)
                .contentType("application/json").content(base + ",\"roleIds\":null}"))
            .andExpect(status().isBadRequest());
        mvc.perform(post("/api/system/user").cookie(cookie).header("Origin", ORIGIN)
                .contentType("application/json").content(base + ",\"roleIds\":[]}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.id").isString());
    }

    @Test
    void userStatusUsesOptimisticVersionAndDoesNotDisableGlobalCredential() throws Exception {
        Cookie cookie = cookie(login("admin", ADMIN_LOGIN_INPUT));
        jdbc.update("UPDATE iam_membership SET status='ACTIVE' WHERE tenant_id=2 AND id=803");
        mvc.perform(patch("/api/system/user/801/status").cookie(cookie).header("Origin", ORIGIN)
                .contentType("application/json").content("{\"status\":0,\"userVersion\":0}"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.userVersion").value(1));
        mvc.perform(patch("/api/system/user/801/status").cookie(cookie).header("Origin", ORIGIN)
                .contentType("application/json").content("{\"status\":1,\"userVersion\":0}"))
            .andExpect(status().isConflict());
        assertThat(jdbc.queryForObject("SELECT status FROM iam_user WHERE id=801", String.class)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT status FROM iam_authentication_credential WHERE user_id=801", String.class))
            .isEqualTo("ACTIVE");
        assertThat(jdbc.queryForObject("SELECT status FROM iam_membership WHERE tenant_id=2 AND id=803", String.class))
            .isEqualTo("ACTIVE");
    }

    @Test
    void staleRoleWritesReturn409WithoutOverwritingOrDeletingTheCurrentRow() throws Exception {
        Cookie cookie = cookie(login("admin", ADMIN_LOGIN_INPUT));
        String body = mvc.perform(post("/api/system/role").cookie(cookie).header("Origin", ORIGIN)
                .contentType("application/json")
                .content("{\"name\":\"Versioned Role\",\"menuIds\":[],\"status\":1}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long roleId = Long.parseLong(JsonPath.read(body, "$.data.id"));

        mvc.perform(put("/api/system/role/" + roleId).cookie(cookie).header("Origin", ORIGIN)
                .contentType("application/json")
                .content("{\"name\":\"Current Role\",\"menuIds\":[],\"status\":1,\"expectedVersion\":0}"))
            .andExpect(status().isOk());
        mvc.perform(put("/api/system/role/" + roleId).cookie(cookie).header("Origin", ORIGIN)
                .contentType("application/json")
                .content("{\"name\":\"Stale Role\",\"menuIds\":[],\"status\":1,\"expectedVersion\":0}"))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value(40902))
            .andExpect(jsonPath("$.error").value("OPTIMISTIC_LOCK_CONFLICT"))
            .andExpect(jsonPath("$.message").value("The record has changed; reload and retry"));
        mvc.perform(patch("/api/system/role/" + roleId + "/status").cookie(cookie).header("Origin", ORIGIN)
                .contentType("application/json")
                .content("{\"status\":0,\"expectedVersion\":0}"))
            .andExpect(status().isConflict());
        mvc.perform(delete("/api/system/role/" + roleId).queryParam("expectedVersion", "0")
                .cookie(cookie).header("Origin", ORIGIN))
            .andExpect(status().isConflict());

        assertThat(jdbc.queryForMap("SELECT role_name,status,row_version FROM iam_role WHERE tenant_id=1 AND id=?", roleId))
            .containsEntry("role_name", "Current Role")
            .containsEntry("status", "ACTIVE")
            .containsEntry("row_version", 1L);

        mvc.perform(put("/api/system/role/9223372036854775807").cookie(cookie).header("Origin", ORIGIN)
                .contentType("application/json")
                .content("{\"name\":\"Missing Role\",\"menuIds\":[],\"status\":1,\"expectedVersion\":0}"))
            .andExpect(status().isNotFound())
            .andExpect(jsonPath("$.error").value("RESOURCE_NOT_FOUND"));
        mvc.perform(delete("/api/system/role/9223372036854775807").queryParam("expectedVersion", "0")
                .cookie(cookie).header("Origin", ORIGIN))
            .andExpect(status().isNotFound());
    }

    @Test
    void staleDepartmentWritesReturn409WithoutOverwritingOrDeletingTheCurrentRow() throws Exception {
        Cookie cookie = cookie(login("admin", ADMIN_LOGIN_INPUT));
        String body = mvc.perform(post("/api/system/dept").cookie(cookie).header("Origin", ORIGIN)
                .contentType("application/json")
                .content("{\"pid\":\"10\",\"name\":\"Versioned Department\",\"status\":1}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long departmentId = Long.parseLong(JsonPath.read(body, "$.data.id"));

        mvc.perform(put("/api/system/dept/" + departmentId).cookie(cookie).header("Origin", ORIGIN)
                .contentType("application/json")
                .content("{\"pid\":\"10\",\"name\":\"Current Department\",\"status\":1,\"expectedVersion\":0}"))
            .andExpect(status().isOk());
        mvc.perform(put("/api/system/dept/" + departmentId).cookie(cookie).header("Origin", ORIGIN)
                .contentType("application/json")
                .content("{\"pid\":\"10\",\"name\":\"Stale Department\",\"status\":1,\"expectedVersion\":0}"))
            .andExpect(status().isConflict());
        mvc.perform(delete("/api/system/dept/" + departmentId).queryParam("expectedVersion", "0")
                .cookie(cookie).header("Origin", ORIGIN))
            .andExpect(status().isConflict());

        assertThat(jdbc.queryForMap("SELECT department_name,row_version FROM iam_department WHERE tenant_id=1 AND id=?", departmentId))
            .containsEntry("department_name", "Current Department")
            .containsEntry("row_version", 1L);
    }

    @Test
    void staleMenuWritesReturn409WithoutOverwritingOrDeletingTheCurrentRow() throws Exception {
        Cookie cookie = cookie(login("admin", ADMIN_LOGIN_INPUT));
        String path = "/versioned-menu-" + jdbc.queryForObject("SELECT nextval('iam_id_seq')", Long.class);
        String body = mvc.perform(post("/api/system/menu").cookie(cookie).header("Origin", ORIGIN)
                .contentType("application/json")
                .content("{\"pid\":\"6000\",\"type\":\"menu\",\"name\":\"VersionedMenu\",\"path\":\""
                    + path + "\",\"component\":\"/system/user/list\",\"meta\":{\"title\":\"system.user.title\"},\"status\":1}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long menuId = Long.parseLong(JsonPath.read(body, "$.data.id"));

        mvc.perform(put("/api/system/menu/" + menuId).cookie(cookie).header("Origin", ORIGIN)
                .contentType("application/json")
                .content("{\"pid\":\"6000\",\"type\":\"menu\",\"name\":\"CurrentMenu\",\"path\":\""
                    + path + "\",\"component\":\"/system/user/list\",\"meta\":{\"title\":\"system.user.title\"},"
                    + "\"status\":1,\"expectedVersion\":0}"))
            .andExpect(status().isOk());
        mvc.perform(put("/api/system/menu/" + menuId).cookie(cookie).header("Origin", ORIGIN)
                .contentType("application/json")
                .content("{\"pid\":\"6000\",\"type\":\"menu\",\"name\":\"StaleMenu\",\"path\":\""
                    + path + "\",\"component\":\"/system/user/list\",\"meta\":{\"title\":\"system.user.title\"},"
                    + "\"status\":1,\"expectedVersion\":0}"))
            .andExpect(status().isConflict());
        mvc.perform(delete("/api/system/menu/" + menuId).queryParam("expectedVersion", "0")
                .cookie(cookie).header("Origin", ORIGIN))
            .andExpect(status().isConflict());

        assertThat(jdbc.queryForMap("SELECT menu_name,row_version FROM iam_menu WHERE tenant_id=1 AND id=?", menuId))
            .containsEntry("menu_name", "CurrentMenu")
            .containsEntry("row_version", 1L);
    }

    @Test
    void staleUserDeleteReturns409WithoutTerminatingTheCurrentMembership() throws Exception {
        Cookie cookie = cookie(login("admin", ADMIN_LOGIN_INPUT));
        String username = "versioned-delete-user-" + jdbc.queryForObject("SELECT nextval('iam_id_seq')", Long.class);
        String body = mvc.perform(post("/api/system/user").cookie(cookie).header("Origin", ORIGIN)
                .contentType("application/json")
                .content("{\"username\":\"" + username + "\",\"name\":\"Versioned Delete User\","
                    + "\"deptId\":\"10\",\"roleIds\":[],\"status\":0}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long userId = Long.parseLong(JsonPath.read(body, "$.data.id"));

        mvc.perform(put("/api/system/user/" + userId).cookie(cookie).header("Origin", ORIGIN)
                .contentType("application/json")
                .content("{\"deptId\":\"10\",\"roleIds\":[],\"status\":0,\"userVersion\":0}"))
            .andExpect(status().isOk());
        mvc.perform(delete("/api/system/user/" + userId).queryParam("expectedVersion", "0")
                .cookie(cookie).header("Origin", ORIGIN))
            .andExpect(status().isConflict());

        assertThat(jdbc.queryForMap("SELECT status,row_version FROM iam_membership WHERE tenant_id=1 AND user_id=?", userId))
            .containsEntry("status", "DISABLED")
            .containsEntry("row_version", 1L);
    }

    @Test
    void createdIdentityStaysPendingUntilAControlledActivationCompletes() throws Exception {
        Cookie cookie = cookie(login("admin", ADMIN_LOGIN_INPUT));
        String username = "recoverable-disabled-user";
        String activationInput = "Recoverable-Disabled-Password-2026";
        String body = mvc.perform(post("/api/system/user").cookie(cookie).header("Origin", ORIGIN)
                .contentType("application/json")
                .content("{\"username\":\"" + username + "\",\"name\":\"Recoverable Disabled User\","
                    + "\"deptId\":\"10\",\"roleIds\":[],\"status\":0}"))
            .andExpect(status().isOk())
            .andReturn().getResponse().getContentAsString();
        long userId = Long.parseLong(JsonPath.read(body, "$.data.id"));

        assertThat(jdbc.queryForObject("SELECT status FROM iam_user WHERE id=?", String.class, userId))
            .isEqualTo("PENDING_ACTIVATION");
        assertThat(jdbc.queryForObject(
            "SELECT status FROM iam_authentication_credential WHERE user_id=?", String.class, userId))
            .isEqualTo("DISABLED");
        assertThat(jdbc.queryForObject(
            "SELECT password_hash FROM iam_authentication_credential WHERE user_id=?", String.class, userId))
            .isNull();
        assertThat(jdbc.queryForObject(
            "SELECT status FROM iam_membership WHERE tenant_id=1 AND user_id=?", String.class, userId))
            .isEqualTo("DISABLED");

        mvc.perform(get("/api/system/user/list?page=1&pageSize=20&username=" + username).cookie(cookie))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.items[0].identityStatus").value("PENDING_ACTIVATION"))
            .andExpect(jsonPath("$.data.items[0].status").value(0));
        mvc.perform(patch("/api/system/user/" + userId + "/status").cookie(cookie).header("Origin", ORIGIN)
                .contentType("application/json").content("{\"status\":1,\"userVersion\":0}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.userVersion").value(1));

        mvc.perform(post("/api/auth/login").header("Origin", ORIGIN).contentType("application/json")
                .content("{\"username\":\"" + username + "\",\"password\":\"" + activationInput + "\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("INVALID_CREDENTIALS"));

        jdbc.update("UPDATE iam_user SET status='ACTIVE' WHERE id=?", userId);
        jdbc.update("""
            UPDATE iam_authentication_credential
               SET password_hash=?,status='ACTIVE'
             WHERE user_id=?
            """, passwords.encode(activationInput), userId);

        assertThat(login(username, activationInput)).isNotBlank();
    }

    @Test
    void tenantMembershipUpdateNeverMutatesGlobalIdentityOrAnotherTenant() throws Exception {
        Cookie cookie = cookie(login("admin", ADMIN_LOGIN_INPUT));
        jdbc.update("UPDATE iam_membership SET status='ACTIVE' WHERE tenant_id=2 AND id=803");
        var userBefore = jdbc.queryForMap("""
            SELECT display_name,status,remark,row_version FROM iam_user WHERE id=801
            """);
        var credentialBefore = jdbc.queryForMap("""
            SELECT username,status,row_version FROM iam_authentication_credential WHERE user_id=801
            """);

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/system/user/801").cookie(cookie).header("Origin", ORIGIN)
                .contentType("application/json")
                .content("""
                    {"deptId":"10","roleIds":[],"status":0,"userVersion":0}
                    """))
            .andExpect(status().isOk());

        assertThat(jdbc.queryForObject("""
            SELECT status FROM iam_membership WHERE tenant_id=1 AND user_id=801
            """, String.class)).isEqualTo("DISABLED");
        assertThat(jdbc.queryForObject("""
            SELECT status FROM iam_membership WHERE tenant_id=2 AND user_id=801
            """, String.class)).isEqualTo("ACTIVE");
        assertThat(jdbc.queryForMap("""
            SELECT display_name,status,remark,row_version FROM iam_user WHERE id=801
            """)).isEqualTo(userBefore);
        assertThat(jdbc.queryForMap("""
            SELECT username,status,row_version FROM iam_authentication_credential WHERE user_id=801
            """)).isEqualTo(credentialBefore);
    }

    @Test
    void departmentTreeRejectsCyclesAndOrphaningChildren() throws Exception {
        Cookie cookie = cookie(login("admin", ADMIN_LOGIN_INPUT));
        String parentBody = mvc.perform(post("/api/system/dept").cookie(cookie).header("Origin", ORIGIN)
                .contentType("application/json").content("{\"pid\":\"10\",\"name\":\"Cycle Parent\",\"status\":1}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String parentId = JsonPath.read(parentBody, "$.data.id");
        String childBody = mvc.perform(post("/api/system/dept").cookie(cookie).header("Origin", ORIGIN)
                .contentType("application/json").content("{\"pid\":\"" + parentId + "\",\"name\":\"Cycle Child\",\"status\":1}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String childId = JsonPath.read(childBody, "$.data.id");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/system/dept/" + parentId).cookie(cookie).header("Origin", ORIGIN)
                .contentType("application/json").content("{\"pid\":\"" + childId
                    + "\",\"name\":\"Cycle Parent\",\"status\":1,\"expectedVersion\":0}"))
            .andExpect(status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/system/dept/" + parentId).queryParam("expectedVersion", "0")
                .cookie(cookie).header("Origin", ORIGIN))
            .andExpect(status().isConflict());
    }

    @Test
    void departmentWritesCannotCreateATreeDeeperThanThirtyTwoLevels() throws Exception {
        Cookie cookie = cookie(login("admin", ADMIN_LOGIN_INPUT));
        long parentId = 10L;
        for (int depth = 2; depth <= 32; depth++) {
            long id = jdbc.queryForObject("SELECT nextval('iam_id_seq')", Long.class);
            jdbc.update("""
                INSERT INTO iam_department(id,tenant_id,parent_id,department_code,department_name,status)
                VALUES(?,1,?,?,?,'ACTIVE')
                """, id, parentId, "depth-" + id, "Depth " + depth);
            parentId = id;
        }

        mvc.perform(post("/api/system/dept").cookie(cookie).header("Origin", ORIGIN)
                .contentType("application/json")
                .content("{\"pid\":\"" + parentId + "\",\"name\":\"Too Deep\",\"status\":1}"))
            .andExpect(status().isBadRequest());

        String movableBody = mvc.perform(post("/api/system/dept").cookie(cookie).header("Origin", ORIGIN)
                .contentType("application/json")
                .content("{\"pid\":\"10\",\"name\":\"Movable\",\"status\":1}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String movableId = JsonPath.read(movableBody, "$.data.id");
        mvc.perform(put("/api/system/dept/" + movableId).cookie(cookie).header("Origin", ORIGIN)
                .contentType("application/json")
                .content("{\"pid\":\"" + parentId
                    + "\",\"name\":\"Movable\",\"status\":1,\"expectedVersion\":0}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void putCannotDisableDepartmentWithActiveChild() throws Exception {
        Cookie cookie = cookie(login("admin", ADMIN_LOGIN_INPUT));
        String parentBody = mvc.perform(post("/api/system/dept").cookie(cookie).header("Origin", ORIGIN)
                .contentType("application/json").content("{\"pid\":\"10\",\"name\":\"Protected Parent\",\"status\":1}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String parentId = JsonPath.read(parentBody, "$.data.id");
        mvc.perform(post("/api/system/dept").cookie(cookie).header("Origin", ORIGIN)
                .contentType("application/json").content("{\"pid\":\"" + parentId
                    + "\",\"name\":\"Active Child\",\"status\":1}"))
            .andExpect(status().isOk());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/system/dept/" + parentId).cookie(cookie).header("Origin", ORIGIN)
                .contentType("application/json")
                .content("{\"pid\":\"10\",\"name\":\"Protected Parent\",\"status\":0,\"expectedVersion\":0}"))
            .andExpect(status().isConflict());
    }

    @Test
    void putCannotDisableDepartmentWithActiveMembership() throws Exception {
        Cookie cookie = cookie(login("admin", ADMIN_LOGIN_INPUT));
        String departmentBody = mvc.perform(post("/api/system/dept").cookie(cookie).header("Origin", ORIGIN)
                .contentType("application/json").content("{\"pid\":\"10\",\"name\":\"Assigned Department\",\"status\":1}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String departmentId = JsonPath.read(departmentBody, "$.data.id");
        mvc.perform(post("/api/system/user").cookie(cookie).header("Origin", ORIGIN)
                .contentType("application/json").content("{\"username\":\"assigned-department-user\","
                    + "\"name\":\"Assigned User\",\"deptId\":\"" + departmentId
                    + "\",\"roleIds\":[],\"status\":1}"))
            .andExpect(status().isOk());

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .put("/api/system/dept/" + departmentId).cookie(cookie).header("Origin", ORIGIN)
                .contentType("application/json")
                .content("{\"pid\":\"10\",\"name\":\"Assigned Department\",\"status\":0,\"expectedVersion\":0}"))
            .andExpect(status().isConflict());
    }

    @Test
    void cannotActivateMembershipInDisabledDepartment() throws Exception {
        Cookie cookie = cookie(login("admin", ADMIN_LOGIN_INPUT));
        String departmentBody = mvc.perform(post("/api/system/dept").cookie(cookie).header("Origin", ORIGIN)
                .contentType("application/json").content("{\"pid\":\"10\",\"name\":\"Disabled Department\",\"status\":0}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String departmentId = JsonPath.read(departmentBody, "$.data.id");

        mvc.perform(post("/api/system/user").cookie(cookie).header("Origin", ORIGIN)
                .contentType("application/json").content("{\"username\":\"disabled-department-user\","
                    + "\"name\":\"Disabled Department User\",\"deptId\":\"" + departmentId
                    + "\",\"roleIds\":[],\"status\":1}"))
            .andExpect(status().isConflict());
    }

    @Test
    void cannotCreateActiveDepartmentBelowDisabledParent() throws Exception {
        Cookie cookie = cookie(login("admin", ADMIN_LOGIN_INPUT));
        String parentBody = mvc.perform(post("/api/system/dept").cookie(cookie).header("Origin", ORIGIN)
                .contentType("application/json").content("{\"pid\":\"10\",\"name\":\"Disabled Parent\",\"status\":0}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String parentId = JsonPath.read(parentBody, "$.data.id");

        mvc.perform(post("/api/system/dept").cookie(cookie).header("Origin", ORIGIN)
                .contentType("application/json").content("{\"pid\":\"" + parentId
                    + "\",\"name\":\"Invalid Active Child\",\"status\":1}"))
            .andExpect(status().isBadRequest());
    }

    @Test
    void failedJooqRoleReplacementRollsBackRoleMenusAndMembershipVersion() throws Exception {
        Cookie cookie = cookie(login("admin", ADMIN_LOGIN_INPUT));
        String roleBody = mvc.perform(post("/api/system/role").cookie(cookie).header("Origin", ORIGIN)
                .contentType("application/json")
                .content("{\"name\":\"Jooq Transaction Role\",\"menuIds\":[\"6000\"],\"status\":1}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long roleId = Long.parseLong(JsonPath.read(roleBody, "$.data.id"));
        String userBody = mvc.perform(post("/api/system/user").cookie(cookie).header("Origin", ORIGIN)
                .contentType("application/json")
                .content("{\"username\":\"jooq-transaction-user\",\"name\":\"Jooq Transaction User\","
                    + "\"deptId\":\"10\",\"roleIds\":[\"" + roleId + "\"],\"status\":1}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        long userId = Long.parseLong(JsonPath.read(userBody, "$.data.id"));
        long membershipVersionBefore = jdbc.queryForObject("""
            SELECT permission_version FROM iam_membership WHERE tenant_id=1 AND user_id=?
            """, Long.class, userId);

        AdministrationActor actor = administrationActor();
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> roles.updateRole(
                1L,
                actor,
                roleId,
                new IdentityModels.RoleCommand(
                    "Must Roll Back", List.of(Long.MAX_VALUE), 1, "must roll back"),
                0L))
            .isInstanceOf(IdentityAdministrationService.ResourceNotFoundException.class);

        long crossTenantMenuId = 8_910_999L;
        jdbc.update("""
            INSERT INTO iam_menu(id,tenant_id,menu_type,menu_name,route_path,status)
            VALUES(?,2,'PAGE','Cross Tenant Menu','/cross-tenant-menu','ACTIVE')
            ON CONFLICT(id) DO NOTHING
            """, crossTenantMenuId);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> roles.updateRole(
                1L,
                actor,
                roleId,
                new IdentityModels.RoleCommand(
                    "Must Still Roll Back", List.of(crossTenantMenuId), 1, "cross tenant"),
                0L))
            .isInstanceOf(IdentityAdministrationService.ResourceNotFoundException.class);

        assertThat(jdbc.queryForObject("SELECT role_name FROM iam_role WHERE id=?", String.class, roleId))
            .isEqualTo("Jooq Transaction Role");
        assertThat(jdbc.queryForList(
            "SELECT menu_id FROM iam_role_menu WHERE tenant_id=1 AND role_id=? ORDER BY menu_id",
            Long.class,
            roleId)).containsExactly(6000L);
        assertThat(jdbc.queryForObject("""
            SELECT permission_version FROM iam_membership WHERE tenant_id=1 AND user_id=?
            """, Long.class, userId)).isEqualTo(membershipVersionBefore);
    }

    private String login(String username, String password) throws Exception {
        String header = mvc.perform(post("/api/auth/login").header("Origin", ORIGIN).contentType("application/json")
                .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.accessToken").value("cookie-session"))
            .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.allOf(
                org.hamcrest.Matchers.containsString("PAYMENT_SESSION="),
                org.hamcrest.Matchers.containsString("HttpOnly"),
                org.hamcrest.Matchers.containsString("SameSite=Strict"))))
            .andReturn().getResponse().getHeader("Set-Cookie");
        return header.substring("PAYMENT_SESSION=".length(), header.indexOf(';'));
    }

    private AdministrationActor administrationActor() {
        long permissionVersion = jdbc.queryForObject("""
            SELECT permission_version FROM iam_membership WHERE tenant_id=1 AND id=1000
            """, Long.class);
        long sessionVersion = jdbc.queryForObject("""
            SELECT session_version FROM iam_membership WHERE tenant_id=1 AND id=1000
            """, Long.class);
        return new AdministrationActor(1000L, 100L, permissionVersion, sessionVersion);
    }

    private void assertSessionInvalid(Cookie cookie) throws Exception {
        mvc.perform(get("/api/user/info").cookie(cookie))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(40102))
            .andExpect(jsonPath("$.error").value("SESSION_INVALID"))
            .andExpect(header().string("Set-Cookie", org.hamcrest.Matchers.allOf(
                org.hamcrest.Matchers.containsString("PAYMENT_SESSION="),
                org.hamcrest.Matchers.containsString("Max-Age=0"))));
    }

    private TestIdentity seedSystemAdministrator(String userStatus, String credentialStatus,
                                                  String passwordHash) {
        long userId = jdbc.queryForObject("SELECT nextval('iam_id_seq')", Long.class);
        long membershipId = jdbc.queryForObject("SELECT nextval('iam_id_seq')", Long.class);
        String username = "unreachable-admin-" + userId;
        jdbc.update("""
            INSERT INTO iam_user(id,idp_issuer,idp_subject,display_name,status)
            VALUES(?,'integration-test',?,?,?)
            """, userId, username, "Unreachable Administrator " + userId, userStatus);
        jdbc.update("""
            INSERT INTO iam_authentication_credential(user_id,username,password_hash,status)
            VALUES(?,?,?,?)
            """, userId, username, passwordHash, credentialStatus);
        jdbc.update("""
            INSERT INTO iam_membership(id,tenant_id,user_id,department_id,status)
            VALUES(?,1,?,10,'ACTIVE')
            """, membershipId, userId);
        jdbc.update("""
            INSERT INTO iam_membership_role(tenant_id,membership_id,role_id,assigned_by)
            VALUES(1,?,2000,1000)
            """, membershipId);
        return new TestIdentity(userId, membershipId);
    }

    private static Cookie cookie(String value) {
        return new Cookie("PAYMENT_SESSION", value);
    }

    private record TestIdentity(long userId, long membershipId) {
    }
}
