package com.niv.payment.adminapi;

import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers
@SpringBootTest(classes = AdminApiApplication.class)
@AutoConfigureMockMvc
@ActiveProfiles("local")
class AdminApiContractIntegrationTest {
    private static final String ORIGIN = "http://localhost:5999";
    private static final String ADMIN_PASSWORD = "Admin-Test-Password-2026";
    private static final String LOW_PASSWORD = "Low-Test-Password-2026";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse("postgres:18.4-alpine@sha256:9a8afca54e7861fd90fab5fdf4c42477a6b1cb7d293595148e674e0a3181de15").asCompatibleSubstituteFor("postgres"))
        .withDatabaseName("payment_platform").withUsername("payment_dev").withPassword("payment_dev");

    @Container
    static final GenericContainer<?> VALKEY = new GenericContainer<>("valkey/valkey:7.2.13-alpine@sha256:ac32d5e70f29e2be83384f5173180911b666c79a0e91ac0d074de5771638ed91").withExposedPorts(6379);

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", VALKEY::getHost);
        registry.add("spring.data.redis.port", () -> VALKEY.getMappedPort(6379));
        registry.add("PAYMENT_BOOTSTRAP_PASSWORD", () -> ADMIN_PASSWORD);
        registry.add("payment.security.allowed-origins", () -> ORIGIN);
        registry.add("payment.security.cookie-secure", () -> false);
    }

    @Autowired MockMvc mvc;
    @Autowired StringRedisTemplate redis;
    @Autowired JdbcTemplate jdbc;
    @Autowired BCryptPasswordEncoder passwords;

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
            """, passwords.encode(LOW_PASSWORD));
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
        String cookie = login("admin", ADMIN_PASSWORD);
        assertThat(cookie).isNotBlank();
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
    void multipleActiveMembershipsRequireExplicitTenantSelection() throws Exception {
        jdbc.update("UPDATE iam_membership SET status='ACTIVE' WHERE tenant_id=2 AND id=803");

        mvc.perform(post("/api/auth/login").header("Origin", ORIGIN).contentType("application/json")
                .content("{\"username\":\"restricted\",\"password\":\"" + LOW_PASSWORD + "\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("INVALID_CREDENTIALS"));

        mvc.perform(post("/api/auth/login").header("Origin", ORIGIN).contentType("application/json")
                .content("{\"username\":\"restricted\",\"password\":\"" + LOW_PASSWORD
                    + "\",\"tenantId\":\"1\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.accessToken").value("cookie-session"));

        mvc.perform(post("/api/auth/login").header("Origin", ORIGIN).contentType("application/json")
                .content("{\"username\":\"restricted\",\"password\":\"" + LOW_PASSWORD
                    + "\",\"tenantId\":\"999\"}"))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.error").value("INVALID_CREDENTIALS"));
    }

    @Test
    void currentUserCodesAndDynamicMenuMatchVbenContract() throws Exception {
        Cookie cookie = cookie(login("admin", ADMIN_PASSWORD));
        mvc.perform(get("/api/user/info").cookie(cookie)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.userId").value("100"))
            .andExpect(jsonPath("$.data.homePath").value("/dashboard"));
        mvc.perform(get("/api/auth/codes").cookie(cookie)).andExpect(status().isOk())
            .andExpect(jsonPath("$.data.length()").value(14))
            .andExpect(jsonPath("$.data[?(@ == 'user:assign-role')]").exists());
        mvc.perform(get("/api/menu/all").cookie(cookie)).andExpect(status().isOk())
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
            .andExpect(jsonPath("$.data[1].children[3].name").value("SystemDept"));
        mvc.perform(get("/api/system/user/list?page=1&pageSize=20&status=1").cookie(cookie))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].id").isString());
        mvc.perform(get("/api/system/role/list?page=1&pageSize=200&status=1").cookie(cookie))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data.items[0].status").value(1));
        mvc.perform(get("/api/system/dept/list").cookie(cookie))
            .andExpect(status().isOk()).andExpect(jsonPath("$.data[0].pid").value("0"));
        mvc.perform(get("/api/system/menu/list").cookie(cookie))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data[0].component").doesNotExist())
            .andExpect(jsonPath("$.data[1].component").doesNotExist())
            .andExpect(jsonPath("$.data[1].meta.title").value("system.title"))
            .andExpect(jsonPath("$.data[1].children[0].component").value("/system/user/list"));
    }

    @Test
    void unregisteredApiRoutesAreDeniedByDefault() throws Exception {
        Cookie cookie = cookie(login("admin", ADMIN_PASSWORD));

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
        Cookie cookie = cookie(login("admin", ADMIN_PASSWORD));

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
                    {"pid":"6000","type":"link","name":"ContractDocumentation","path":"/contract/docs",
                     "component":"IFrameView","meta":{"title":"system.title","link":"https://docs.example.com"},
                     "status":1}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.id").isString());
    }
    @Test
    void auditEventUsesTheHttpRequestTraceId() throws Exception {
        Cookie cookie = cookie(login("admin", ADMIN_PASSWORD));
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
        Cookie cookie = cookie(login("admin", ADMIN_PASSWORD));
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
    void everySystemEndpointRequiresServerSidePermission() throws Exception {
        Cookie cookie = cookie(login("restricted", LOW_PASSWORD));
        mvc.perform(get("/api/system/user/list?page=1&pageSize=20").cookie(cookie))
            .andExpect(status().isForbidden()).andExpect(jsonPath("$.error").value("PERMISSION_DENIED"));
        mvc.perform(patch("/api/system/role/2000/status").cookie(cookie).header("Origin", ORIGIN)
                .contentType("application/json").content("{\"status\":1}"))
            .andExpect(status().isForbidden());
    }

    @Test
    void disabledMembershipInvalidatesTheExistingSessionWith401() throws Exception {
        Cookie cookie = cookie(login("restricted", LOW_PASSWORD));
        jdbc.update("UPDATE iam_membership SET status='DISABLED' WHERE tenant_id=1 AND id=802");

        assertSessionInvalid(cookie);
    }

    @Test
    void disabledCredentialInvalidatesTheExistingSessionWith401() throws Exception {
        Cookie cookie = cookie(login("restricted", LOW_PASSWORD));
        try {
            jdbc.update("UPDATE iam_authentication_credential SET status='DISABLED' WHERE user_id=801");
            assertSessionInvalid(cookie);
        } finally {
            jdbc.update("UPDATE iam_authentication_credential SET status='ACTIVE' WHERE user_id=801");
        }
    }

    @Test
    void disabledUserInvalidatesTheExistingSessionWith401() throws Exception {
        Cookie cookie = cookie(login("restricted", LOW_PASSWORD));
        try {
            jdbc.update("UPDATE iam_user SET status='DISABLED' WHERE id=801");
            assertSessionInvalid(cookie);
        } finally {
            jdbc.update("UPDATE iam_user SET status='ACTIVE' WHERE id=801");
        }
    }

    @Test
    void disabledTenantInvalidatesTheExistingSessionWith401() throws Exception {
        Cookie cookie = cookie(login("restricted", LOW_PASSWORD));
        try {
            jdbc.update("UPDATE iam_tenant SET status='DISABLED' WHERE id=1");
            assertSessionInvalid(cookie);
        } finally {
            jdbc.update("UPDATE iam_tenant SET status='ACTIVE' WHERE id=1");
        }
    }

    @Test
    void ordinaryUserManagementCannotAssignOrRemoveSystemRoles() throws Exception {
        Cookie cookie = cookie(login("admin", ADMIN_PASSWORD));

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
        Cookie cookie = cookie(login("admin", ADMIN_PASSWORD));

        mvc.perform(patch("/api/system/user/100/status").cookie(cookie).header("Origin", ORIGIN)
                .contentType("application/json").content("{\"status\":0,\"userVersion\":0}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.error").value("IAM_LAST_ADMIN_PROTECTED"));
    }

    @Test
    void userRoleIdsAreRequiredButEmptyAssignmentsAreAccepted() throws Exception {
        Cookie cookie = cookie(login("admin", ADMIN_PASSWORD));
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
        Cookie cookie = cookie(login("admin", ADMIN_PASSWORD));
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
    void tenantMembershipUpdateNeverMutatesGlobalIdentityOrAnotherTenant() throws Exception {
        Cookie cookie = cookie(login("admin", ADMIN_PASSWORD));
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
        Cookie cookie = cookie(login("admin", ADMIN_PASSWORD));
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
                .contentType("application/json").content("{\"pid\":\"" + childId + "\",\"name\":\"Cycle Parent\",\"status\":1}"))
            .andExpect(status().isBadRequest());
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                .delete("/api/system/dept/" + parentId).cookie(cookie).header("Origin", ORIGIN))
            .andExpect(status().isConflict());
    }

    @Test
    void putCannotDisableDepartmentWithActiveChild() throws Exception {
        Cookie cookie = cookie(login("admin", ADMIN_PASSWORD));
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
                .contentType("application/json").content("{\"pid\":\"10\",\"name\":\"Protected Parent\",\"status\":0}"))
            .andExpect(status().isConflict());
    }

    @Test
    void putCannotDisableDepartmentWithActiveMembership() throws Exception {
        Cookie cookie = cookie(login("admin", ADMIN_PASSWORD));
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
                .contentType("application/json").content("{\"pid\":\"10\",\"name\":\"Assigned Department\",\"status\":0}"))
            .andExpect(status().isConflict());
    }

    @Test
    void cannotActivateMembershipInDisabledDepartment() throws Exception {
        Cookie cookie = cookie(login("admin", ADMIN_PASSWORD));
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
        Cookie cookie = cookie(login("admin", ADMIN_PASSWORD));
        String parentBody = mvc.perform(post("/api/system/dept").cookie(cookie).header("Origin", ORIGIN)
                .contentType("application/json").content("{\"pid\":\"10\",\"name\":\"Disabled Parent\",\"status\":0}"))
            .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String parentId = JsonPath.read(parentBody, "$.data.id");

        mvc.perform(post("/api/system/dept").cookie(cookie).header("Origin", ORIGIN)
                .contentType("application/json").content("{\"pid\":\"" + parentId
                    + "\",\"name\":\"Invalid Active Child\",\"status\":1}"))
            .andExpect(status().isBadRequest());
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

    private void assertSessionInvalid(Cookie cookie) throws Exception {
        mvc.perform(get("/api/user/info").cookie(cookie))
            .andExpect(status().isUnauthorized())
            .andExpect(jsonPath("$.code").value(40102))
            .andExpect(jsonPath("$.error").value("SESSION_INVALID"));
    }

    private static Cookie cookie(String value) {
        return new Cookie("PAYMENT_SESSION", value);
    }
}
