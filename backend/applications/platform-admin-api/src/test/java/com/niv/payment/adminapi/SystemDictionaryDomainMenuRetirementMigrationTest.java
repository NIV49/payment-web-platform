package com.niv.payment.adminapi;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class SystemDictionaryDomainMenuRetirementMigrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse(
        "postgres:18.4-alpine@sha256:9a8afca54e7861fd90fab5fdf4c42477a6b1cb7d293595148e674e0a3181de15")
        .asCompatibleSubstituteFor("postgres"));

    @BeforeEach
    void migrateThroughV30() throws Exception {
        flyway(null).clean();
        flyway("27").migrate();
        execute("""
            INSERT INTO iam_tenant(id,tenant_code,tenant_name,tenant_type,status,account_domain)
            VALUES
              (96101,'v31-platform','V31 Platform','PLATFORM','ACTIVE','PLATFORM'),
              (96102,'v31-merchant','V31 Merchant','DIRECT_MERCHANT','ACTIVE','MERCHANT'),
              (96103,'v31-agent','V31 Agent','AGENT','ACTIVE','AGENT');
            INSERT INTO iam_role(
                id,tenant_id,role_code,role_name,applicable_tenant_type,
                assignable,system_role,status
            ) VALUES
              (96201,96101,'v31-platform-admin','V31 Platform Admin','PLATFORM',false,true,'ACTIVE'),
              (96202,96102,'v31-merchant-admin','V31 Merchant Admin','DIRECT_MERCHANT',false,true,'ACTIVE'),
              (96203,96103,'v31-agent-admin','V31 Agent Admin','AGENT',false,true,'ACTIVE');
            INSERT INTO iam_menu(
                id,tenant_id,menu_type,menu_name,route_name,route_path,component_path,
                sort_order,status,meta_json,system_managed
            ) VALUES
              (96301,96101,'DIRECTORY','System Management','System','/system','BasicLayout',
               100,'ACTIVE','{"title":"system.title"}'::jsonb,true),
              (96302,96102,'DIRECTORY','System Management','System','/system','BasicLayout',
               100,'ACTIVE','{"title":"system.title"}'::jsonb,true),
              (96303,96103,'DIRECTORY','System Management','System','/system','BasicLayout',
               100,'ACTIVE','{"title":"system.title"}'::jsonb,true);
            """);
        flyway("30").migrate();
    }

    @Test
    void v31RetiresOnlyMerchantAndAgentMenusAndPreservesReadAuthorization() throws Exception {
        execute("""
            UPDATE iam_tenant SET status='DISABLED' WHERE id=96102;
            UPDATE iam_permission SET status='DISABLED'
             WHERE permission_code='dictionary-data:view';
            UPDATE iam_menu
               SET status='DISABLED',deleted_at=now(),row_version=1
             WHERE tenant_id=96102 AND route_name='System';
            """);
        long roleMenuCountBefore = singleLong("""
            SELECT count(*) FROM iam_role_menu role_menu
            JOIN iam_menu menu ON menu.tenant_id=role_menu.tenant_id AND menu.id=role_menu.menu_id
            JOIN iam_tenant tenant ON tenant.id=menu.tenant_id
            WHERE tenant.account_domain IN ('MERCHANT','AGENT')
              AND menu.route_name IN ('SystemDictionaryDataIndex','SystemDictionaryData')
            """);

        flyway("31").migrate();

        assertThat(currentSuccessfulVersion()).isEqualTo("31");
        assertThat(singleLong("""
            SELECT count(*) FROM iam_menu menu
            JOIN iam_tenant tenant ON tenant.id=menu.tenant_id
            WHERE tenant.account_domain IN ('MERCHANT','AGENT')
              AND menu.route_name IN ('SystemDictionaryDataIndex','DictionaryDataView')
              AND menu.status='DISABLED' AND menu.deleted_at IS NOT NULL AND menu.row_version=1
            """)).isEqualTo(4);
        assertThat(singleLong("""
            SELECT count(*) FROM iam_menu menu
            JOIN iam_tenant tenant ON tenant.id=menu.tenant_id
            WHERE tenant.account_domain IN ('MERCHANT','AGENT')
              AND menu.route_name IN ('SystemDictionaryDataIndex','DictionaryDataView')
              AND menu.status='ACTIVE' AND menu.deleted_at IS NULL
            """)).isZero();
        assertThat(singleLong("""
            SELECT count(*) FROM iam_menu menu
            JOIN iam_tenant tenant ON tenant.id=menu.tenant_id
            WHERE tenant.account_domain='PLATFORM'
              AND menu.route_name IN ('SystemDictionaryDataIndex','DictionaryDataView')
              AND menu.status='ACTIVE' AND menu.deleted_at IS NULL
            """)).isEqualTo(2);
        assertThat(singleLong("""
            SELECT count(*) FROM iam_role_menu role_menu
            JOIN iam_menu menu ON menu.tenant_id=role_menu.tenant_id AND menu.id=role_menu.menu_id
            JOIN iam_tenant tenant ON tenant.id=menu.tenant_id
            WHERE tenant.account_domain IN ('MERCHANT','AGENT')
              AND menu.route_name IN ('SystemDictionaryDataIndex','SystemDictionaryData')
            """)).isEqualTo(roleMenuCountBefore);
        assertThat(singleLong("""
            SELECT count(*) FROM iam_role_grant grant_row
            JOIN iam_permission permission ON permission.id=grant_row.permission_id
            JOIN iam_grant_dimension dimension ON dimension.grant_id=grant_row.id
            JOIN iam_tenant tenant ON tenant.id=grant_row.tenant_id
            WHERE tenant.account_domain IN ('MERCHANT','AGENT')
              AND permission.permission_code='dictionary-data:view'
              AND grant_row.status='ACTIVE' AND dimension.dimension_code='TENANT'
              AND dimension.scope_mode='TENANT_ALL'
            """)).isEqualTo(2);
        assertThat(singleLong(
            "SELECT revision FROM sys_dictionary_catalog_revision WHERE singleton_id=1"))
            .isOne();
    }

    @Test
    void migrationLocksMenuWritesBeforeRunningItsPreflight() throws Exception {
        try (var input = getClass().getResourceAsStream(
            "/db/migration/V31__retire_merchant_agent_dictionary_menus.sql")) {
            assertThat(input).isNotNull();
            assertThat(new String(input.readAllBytes(), StandardCharsets.UTF_8).stripLeading())
                .startsWith("LOCK TABLE iam_menu IN SHARE ROW EXCLUSIVE MODE;");
        }
    }

    @Test
    void exactExistingTombstonesAreAcceptedWithoutRewritingThem() throws Exception {
        execute("""
            UPDATE iam_menu menu
               SET status='DISABLED', deleted_at=TIMESTAMPTZ '2026-01-01 00:00:00+00',
                   updated_at=TIMESTAMPTZ '2026-01-01 00:00:00+00', row_version=1
              FROM iam_tenant tenant
             WHERE tenant.id=menu.tenant_id
               AND tenant.account_domain IN ('MERCHANT','AGENT')
               AND menu.route_name IN ('SystemDictionaryDataIndex','DictionaryDataView');
            """);

        flyway("31").migrate();

        assertThat(singleLong("""
            SELECT count(*) FROM iam_menu menu
            JOIN iam_tenant tenant ON tenant.id=menu.tenant_id
            WHERE tenant.account_domain IN ('MERCHANT','AGENT')
              AND menu.route_name IN ('SystemDictionaryDataIndex','DictionaryDataView')
              AND menu.deleted_at=TIMESTAMPTZ '2026-01-01 00:00:00+00'
              AND menu.updated_at=TIMESTAMPTZ '2026-01-01 00:00:00+00'
              AND menu.row_version=1
            """)).isEqualTo(4);
    }

    @ParameterizedTest
    @EnumSource(MenuDrift.class)
    void partialMixedModifiedOrChildMenuStateFailsAtomically(MenuDrift drift) throws Exception {
        drift.apply();
        long activeBefore = targetMenuCount("ACTIVE", false);
        long disabledBefore = targetMenuCount("DISABLED", true);

        assertThatThrownBy(() -> flyway("31").migrate()).isInstanceOf(RuntimeException.class);

        assertThat(currentSuccessfulVersion()).isEqualTo("30");
        assertThat(targetMenuCount("ACTIVE", false)).isEqualTo(activeBefore);
        assertThat(targetMenuCount("DISABLED", true)).isEqualTo(disabledBefore);
        assertThat(singleLong(
            "SELECT revision FROM sys_dictionary_catalog_revision WHERE singleton_id=1"))
            .isOne();
    }

    private enum MenuDrift {
        MODIFIED {
            @Override void apply() throws Exception {
                execute("""
                    UPDATE iam_menu SET component_path='/unexpected'
                     WHERE tenant_id=96102 AND route_name='SystemDictionaryDataIndex'
                    """);
            }
        },
        MISSING_BUTTON {
            @Override void apply() throws Exception {
                execute("DELETE FROM iam_menu WHERE tenant_id=96102 AND route_name='DictionaryDataView'");
            }
        },
        MIXED_STATE {
            @Override void apply() throws Exception {
                execute("""
                    UPDATE iam_menu SET status='DISABLED',deleted_at=now(),row_version=1
                     WHERE tenant_id=96103 AND route_name='SystemDictionaryDataIndex'
                    """);
            }
        },
        ACTIVE_CHILD {
            @Override void apply() throws Exception {
                execute("""
                    INSERT INTO iam_menu(
                        id,tenant_id,parent_id,menu_type,menu_name,route_name,route_path,
                        component_path,sort_order,status,meta_json,system_managed
                    )
                    SELECT 96999,tenant_id,id,'PAGE','Unexpected Child','UnexpectedDictionaryChild',
                           '/system/dict/data/unexpected','/system/dict/data/list',999,
                           'ACTIVE','{}'::jsonb,true
                      FROM iam_menu
                     WHERE tenant_id=96103 AND route_name='SystemDictionaryDataIndex'
                    """);
            }
        };

        abstract void apply() throws Exception;
    }

    private static long targetMenuCount(String status, boolean deleted) throws Exception {
        return singleLong("""
            SELECT count(*) FROM iam_menu menu
            JOIN iam_tenant tenant ON tenant.id=menu.tenant_id
            WHERE tenant.account_domain IN ('MERCHANT','AGENT')
              AND menu.route_name IN ('SystemDictionaryDataIndex','DictionaryDataView')
              AND menu.status='%s' AND menu.deleted_at IS %s NULL
            """.formatted(status, deleted ? "NOT" : ""));
    }

    private static String currentSuccessfulVersion() throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery("""
                 SELECT version FROM flyway_schema_history
                  WHERE success ORDER BY installed_rank DESC LIMIT 1
                 """)) {
            result.next();
            return result.getString(1);
        }
    }

    private static void execute(String sql) throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static long singleLong(String sql) throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private static Flyway flyway(String target) {
        var configuration = PostgresFlywayTestSupport.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .cleanDisabled(false);
        if (target != null) configuration.target(target);
        return configuration.load();
    }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
