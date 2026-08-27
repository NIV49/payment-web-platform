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

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class SystemDictionarySingleRouteMigrationTest {
    private static final long PLATFORM_TENANT = 95_101L;
    private static final long MERCHANT_TENANT = 95_102L;
    private static final long AGENT_TENANT = 95_103L;

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse(
        "postgres:18.4-alpine@sha256:9a8afca54e7861fd90fab5fdf4c42477a6b1cb7d293595148e674e0a3181de15")
        .asCompatibleSubstituteFor("postgres"));

    @BeforeEach
    void migrateThroughV29() throws Exception {
        flyway(null).clean();
        flyway("27").migrate();
        execute("""
            INSERT INTO iam_tenant(id,tenant_code,tenant_name,tenant_type,status,account_domain)
            VALUES
              (95101,'v30-platform','V30 Platform','PLATFORM','ACTIVE','PLATFORM'),
              (95102,'v30-merchant','V30 Merchant','DIRECT_MERCHANT','ACTIVE','MERCHANT'),
              (95103,'v30-agent','V30 Agent','AGENT','ACTIVE','AGENT');
            INSERT INTO iam_role(
                id,tenant_id,role_code,role_name,applicable_tenant_type,
                assignable,system_role,status
            ) VALUES
              (95201,95101,'v30-platform-admin','V30 Platform Admin','PLATFORM',false,true,'ACTIVE'),
              (95202,95102,'v30-merchant-admin','V30 Merchant Admin','DIRECT_MERCHANT',false,true,'ACTIVE'),
              (95203,95103,'v30-agent-admin','V30 Agent Admin','AGENT',false,true,'ACTIVE');
            INSERT INTO iam_menu(
                id,tenant_id,menu_type,menu_name,route_name,route_path,component_path,
                sort_order,status,meta_json,system_managed
            ) VALUES
              (95301,95101,'DIRECTORY','System Management','System','/system','BasicLayout',
               100,'ACTIVE','{"title":"system.title"}'::jsonb,true),
              (95302,95102,'DIRECTORY','System Management','System','/system','BasicLayout',
               100,'ACTIVE','{"title":"system.title"}'::jsonb,true),
              (95303,95103,'DIRECTORY','System Management','System','/system','BasicLayout',
               100,'ACTIVE','{"title":"system.title"}'::jsonb,true);
            """);
        flyway("29").migrate();
    }

    @Test
    void v30SeedsCommonStatusAndTombstonesOnlyTheDynamicPagesAtomically() throws Exception {
        assertThat(singleLong("SELECT revision FROM sys_dictionary_catalog_revision WHERE singleton_id=1"))
            .isZero();
        assertThat(singleLong("""
            SELECT count(*) FROM iam_role_menu role_menu
            JOIN iam_menu menu ON menu.tenant_id=role_menu.tenant_id AND menu.id=role_menu.menu_id
            WHERE menu.route_name='SystemDictionaryData'
            """)).isEqualTo(3);

        flyway("30").migrate();

        assertThat(singleLong("SELECT revision FROM sys_dictionary_catalog_revision WHERE singleton_id=1"))
            .isOne();
        assertThat(singleLong("""
            SELECT count(*) FROM sys_dictionary_type
             WHERE dict_type='SYS_COMMON_STATUS' AND dict_name='系统-通用状态'
               AND sort_order=0 AND remark='' AND row_version=0 AND deleted_at IS NULL
            """)).isOne();
        assertThat(singleLong("""
            SELECT count(*) FROM sys_dictionary_data data
            JOIN sys_dictionary_type type_row ON type_row.id=data.dictionary_type_id
            WHERE type_row.dict_type='SYS_COMMON_STATUS' AND data.deleted_at IS NULL
              AND data.row_version=0 AND data.remark=''
              AND ((data.value='1' AND data.label='启用' AND data.color='success' AND data.sort_order=1)
                OR (data.value='0' AND data.label='禁用' AND data.color='error' AND data.sort_order=2))
            """)).isEqualTo(2);
        assertThat(singleLong("""
            SELECT count(*) FROM iam_menu
             WHERE route_name='SystemDictionaryData' AND status='DISABLED'
               AND deleted_at IS NOT NULL AND row_version=1
            """)).isEqualTo(3);
        assertThat(singleLong("""
            SELECT count(*) FROM iam_role_menu role_menu
            JOIN iam_menu menu ON menu.tenant_id=role_menu.tenant_id AND menu.id=role_menu.menu_id
            WHERE menu.route_name='SystemDictionaryData'
            """)).isEqualTo(3);
        assertThat(singleLong("""
            SELECT count(*) FROM iam_menu
             WHERE route_name='SystemDictionaryDataIndex' AND status='ACTIVE' AND deleted_at IS NULL
            """)).isEqualTo(3);
    }

    @Test
    void exactExistingCommonStatusDoesNotAdvanceRevision() throws Exception {
        insertExactCommonStatus();
        execute("UPDATE sys_dictionary_catalog_revision SET revision=7 WHERE singleton_id=1");

        flyway("30").migrate();

        assertThat(singleLong("SELECT revision FROM sys_dictionary_catalog_revision WHERE singleton_id=1"))
            .isEqualTo(7);
    }

    @Test
    void disabledTenantAndDisplayPermissionDoNotBlockLegacyRouteRetirement() throws Exception {
        execute("""
            UPDATE iam_tenant SET status='DISABLED' WHERE id=95102;
            UPDATE iam_permission SET status='DISABLED'
             WHERE permission_code='dictionary-data:view';
            """);

        flyway("30").migrate();

        assertThat(singleLong("""
            SELECT count(*) FROM iam_menu
             WHERE route_name='SystemDictionaryData' AND status='DISABLED'
               AND deleted_at IS NOT NULL
            """)).isEqualTo(3);
    }

    @ParameterizedTest
    @EnumSource(DictionaryDrift.class)
    void partialModifiedOrTombstonedCommonStatusFailsWithoutChangingMenus(DictionaryDrift drift)
        throws Exception {
        drift.apply();

        assertThatThrownBy(() -> flyway("30").migrate()).isInstanceOf(RuntimeException.class);

        assertThat(singleLong("SELECT revision FROM sys_dictionary_catalog_revision WHERE singleton_id=1"))
            .isZero();
        assertThat(singleLong("""
            SELECT count(*) FROM iam_menu
             WHERE route_name='SystemDictionaryData' AND status='ACTIVE' AND deleted_at IS NULL
            """)).isEqualTo(3);
    }

    @ParameterizedTest
    @EnumSource(MenuDrift.class)
    void nonCanonicalDynamicMenuOrActiveChildFailsBeforeAnyMutation(MenuDrift drift)
        throws Exception {
        drift.apply();
        long activeDynamicMenusBefore = singleLong("""
            SELECT count(*) FROM iam_menu
             WHERE route_name='SystemDictionaryData' AND status='ACTIVE' AND deleted_at IS NULL
            """);
        long disabledDynamicMenusBefore = singleLong("""
            SELECT count(*) FROM iam_menu
             WHERE route_name='SystemDictionaryData' AND status='DISABLED' AND deleted_at IS NOT NULL
            """);

        assertThatThrownBy(() -> flyway("30").migrate()).isInstanceOf(RuntimeException.class);

        assertThat(singleLong("SELECT count(*) FROM sys_dictionary_type WHERE dict_type='SYS_COMMON_STATUS'"))
            .isZero();
        assertThat(singleLong("SELECT revision FROM sys_dictionary_catalog_revision WHERE singleton_id=1"))
            .isZero();
        assertThat(singleLong("""
            SELECT count(*) FROM iam_menu
             WHERE route_name='SystemDictionaryData' AND status='ACTIVE' AND deleted_at IS NULL
            """)).isEqualTo(activeDynamicMenusBefore);
        assertThat(singleLong("""
            SELECT count(*) FROM iam_menu
             WHERE route_name='SystemDictionaryData' AND status='DISABLED' AND deleted_at IS NOT NULL
            """)).isEqualTo(disabledDynamicMenusBefore);
    }

    private enum DictionaryDrift {
        PARTIAL {
            @Override void apply() throws Exception {
                execute("""
                    INSERT INTO sys_dictionary_type(id,dict_type,dict_name,sort_order,remark)
                    VALUES (95901,'SYS_COMMON_STATUS','系统-通用状态',0,'')
                    """);
            }
        },
        MODIFIED {
            @Override void apply() throws Exception {
                insertExactCommonStatus();
                execute("UPDATE sys_dictionary_data SET label='Enabled' WHERE value='1'");
            }
        },
        TOMBSTONED {
            @Override void apply() throws Exception {
                insertExactCommonStatus();
                execute("""
                    UPDATE sys_dictionary_type SET deleted_at=now()
                     WHERE dict_type='SYS_COMMON_STATUS'
                    """);
            }
        };

        abstract void apply() throws Exception;
    }

    private enum MenuDrift {
        NON_CANONICAL {
            @Override void apply() throws Exception {
                execute("""
                    UPDATE iam_menu SET component_path='/unexpected'
                     WHERE tenant_id=95102 AND route_name='SystemDictionaryData'
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
                    SELECT 95999,tenant_id,id,'PAGE','Unexpected Child','UnexpectedChild',
                           '/system/dict/data/unexpected','/system/dict/data/list',999,
                           'ACTIVE','{}'::jsonb,true
                      FROM iam_menu WHERE tenant_id=95103 AND route_name='SystemDictionaryData'
                    """);
            }
        },
        PRE_TOMBSTONED {
            @Override void apply() throws Exception {
                execute("""
                    UPDATE iam_menu
                       SET status='DISABLED', deleted_at=now(), row_version=1
                     WHERE tenant_id=95101 AND route_name='SystemDictionaryData'
                    """);
            }
        };

        abstract void apply() throws Exception;
    }

    private static void insertExactCommonStatus() throws Exception {
        execute("""
            INSERT INTO sys_dictionary_type(id,dict_type,dict_name,sort_order,remark)
            VALUES (95901,'SYS_COMMON_STATUS','系统-通用状态',0,'');
            INSERT INTO sys_dictionary_data(
                id,dictionary_type_id,label,value,color,sort_order,remark
            ) VALUES
              (95902,95901,'启用','1','success',1,''),
              (95903,95901,'禁用','0','error',2,'');
            """);
    }

    private static void execute(String sql) throws Exception {
        try (Connection connection = connection(); Statement statement = connection.createStatement()) {
            statement.execute(sql);
        }
    }

    private static long singleLong(String sql) throws Exception {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
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
