package com.niv.payment.adminapi;

import com.niv.payment.dictionary.core.DictionaryCommands;
import com.niv.payment.dictionary.core.DictionaryCatalogRepository;
import com.niv.payment.dictionary.core.DictionaryModels;
import com.niv.payment.dictionary.core.SystemDictionaryException;
import com.niv.payment.dictionary.core.SystemDictionaryService;
import com.niv.payment.dictionary.persistence.JooqDictionaryCatalogRepository;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.jooq.DSLContext;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.junit.jupiter.api.Timeout;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.TransactionAwareDataSourceProxy;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
@Timeout(value = 3, unit = TimeUnit.MINUTES)
class SystemDictionaryRepositoryIntegrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse(
        "postgres:18.4-alpine@sha256:9a8afca54e7861fd90fab5fdf4c42477a6b1cb7d293595148e674e0a3181de15")
        .asCompatibleSubstituteFor("postgres"));

    private static DSLContext dsl;
    private static DictionaryCatalogRepository repository;
    private static SystemDictionaryService service;
    private static DictionaryModels.Actor actor;
    private static AnnotationConfigApplicationContext transactionContext;
    private static final AtomicLong trace = new AtomicLong();

    @BeforeAll
    static void migrateWithExistingThreeDomainRoles() {
        Flyway flyway27 = PostgresFlywayTestSupport.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .target(MigrationVersion.fromVersion("27"))
            .cleanDisabled(false).load();
        flyway27.clean();
        flyway27.migrate();
        try (var connection = DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())) {
            connection.setAutoCommit(true);
            seedPreV28IdentitiesAndMenus(DSL.using(connection));
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot seed the pre-V28 dictionary fixture", exception);
        }

        PostgresFlywayTestSupport.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .target(MigrationVersion.fromVersion("31"))
            .load().migrate();

        dsl = DSL.using(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());

        DriverManagerDataSource dataSource = new DriverManagerDataSource();
        dataSource.setDriverClassName("org.postgresql.Driver");
        dataSource.setUrl(POSTGRES.getJdbcUrl());
        dataSource.setUsername(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        DSLContext transactionalDsl = DSL.using(
            new TransactionAwareDataSourceProxy(dataSource), SQLDialect.POSTGRES);

        transactionContext = new AnnotationConfigApplicationContext();
        transactionContext.register(TransactionManagementConfiguration.class);
        transactionContext.registerBean(PlatformTransactionManager.class,
            () -> new DataSourceTransactionManager(dataSource));
        transactionContext.registerBean(JooqDictionaryCatalogRepository.class,
            () -> new JooqDictionaryCatalogRepository(
                transactionalDsl, () -> "dictionary-test-" + trace.incrementAndGet()));
        transactionContext.refresh();

        repository = transactionContext.getBean(DictionaryCatalogRepository.class);
        service = new SystemDictionaryService(repository, new NoCache());
        actor = new DictionaryModels.Actor(91001L, 91301L, 91201L, 1L, 0L, "PLATFORM");
    }

    @AfterAll
    static void closeTransactionContext() {
        if (transactionContext != null) transactionContext.close();
    }

    @Test
    @Order(1)
    void migrationGrantsAndMenusAreDomainSpecific() {
        assertThat(count("""
            SELECT count(*) FROM iam_role_grant grant_row
            JOIN iam_permission permission ON permission.id=grant_row.permission_id
            WHERE grant_row.role_id=91401
              AND permission.permission_code LIKE 'dictionary%'
            """)).isEqualTo(8);
        assertThat(count("""
            SELECT count(*) FROM iam_role_grant grant_row
            JOIN iam_permission permission ON permission.id=grant_row.permission_id
            WHERE grant_row.role_id IN (91402,91403)
              AND permission.permission_code='dictionary-data:view'
            """)).isEqualTo(2);
        assertThat(count("""
            SELECT count(*) FROM iam_role_grant grant_row
            JOIN iam_permission permission ON permission.id=grant_row.permission_id
            WHERE grant_row.role_id IN (91402,91403)
              AND permission.permission_code IN (
                'dictionary:create','dictionary:update','dictionary:delete',
                'dictionary-data:create','dictionary-data:update','dictionary-data:delete')
            """)).isZero();
        assertThat(count("""
            SELECT count(*) FROM iam_menu
             WHERE tenant_id=91001 AND route_name IN (
                'SystemDictionary','SystemDictionaryDataIndex','SystemDictionaryData')
            """)).isEqualTo(3);
        assertThat(count("""
            SELECT count(*) FROM iam_menu
             WHERE tenant_id IN (91002,91003) AND route_name='SystemDictionary'
            """)).isZero();
        assertThat(count("""
            SELECT count(*) FROM iam_menu
             WHERE tenant_id IN (91002,91003)
               AND route_name IN ('SystemDictionaryDataIndex','SystemDictionaryData')
            """)).isEqualTo(4);
        assertThat(count("""
            SELECT count(*) FROM iam_menu menu
            JOIN iam_tenant tenant ON tenant.id=menu.tenant_id
            WHERE tenant.account_domain='PLATFORM'
              AND menu.route_name IN ('SystemDictionaryDataIndex','SystemDictionaryData')
              AND menu.meta_json->>'hideInMenu'='true'
              AND menu.meta_json->>'activePath'='/system/dict'
            """)).isEqualTo(2);
        assertThat(count("""
            SELECT count(*) FROM iam_menu menu
            JOIN iam_tenant tenant ON tenant.id=menu.tenant_id
            WHERE tenant.account_domain IN ('MERCHANT','AGENT')
              AND menu.route_name='SystemDictionaryDataIndex'
              AND COALESCE(menu.meta_json->>'hideInMenu','false')='false'
            """)).isEqualTo(2);
        assertThat(count("""
            SELECT count(*) FROM iam_menu menu
            JOIN iam_tenant tenant ON tenant.id=menu.tenant_id
            WHERE tenant.account_domain IN ('MERCHANT','AGENT')
              AND menu.route_name='SystemDictionaryData'
              AND menu.meta_json->>'hideInMenu'='true'
              AND menu.meta_json->>'activePath'='/system/dict/data'
            """)).isEqualTo(2);
        assertThat(count("""
            SELECT count(*) FROM iam_menu
             WHERE route_name='SystemDictionaryData'
               AND status='DISABLED'
               AND deleted_at IS NOT NULL
               AND row_version=1
            """)).isEqualTo(3);
        assertThat(count("""
            SELECT count(*) FROM iam_menu
             WHERE route_name='SystemDictionaryData'
               AND status='ACTIVE'
               AND deleted_at IS NULL
            """)).isZero();
        assertThat(count("""
            SELECT count(*) FROM iam_menu
             WHERE route_name='SystemDictionaryDataIndex'
               AND status='ACTIVE'
               AND deleted_at IS NULL
            """)).isEqualTo(1);
        assertThat(count("""
            SELECT count(*) FROM iam_menu
             WHERE tenant_id IN (91002,91003)
               AND route_name IN ('SystemDictionaryDataIndex','DictionaryDataView')
               AND status='DISABLED'
               AND deleted_at IS NOT NULL
               AND row_version=1
            """)).isEqualTo(4);
        assertThat(count("""
            SELECT count(*) FROM iam_role_menu role_menu
            JOIN iam_menu menu ON menu.id=role_menu.menu_id
            WHERE menu.route_name='SystemDictionaryData'
            """)).isEqualTo(3);
        assertThat(count("""
            SELECT count(*) FROM iam_menu
             WHERE (tenant_id=91001 AND route_name='DictionaryView' AND sort_order=151
                    AND auth_code='dictionary:view')
                OR (tenant_id IN (91001,91002,91003) AND route_name='DictionaryDataView'
                    AND sort_order=162 AND auth_code='dictionary-data:view')
            """)).isEqualTo(4);
    }

    @Test
    @Order(2)
    void mutationsCanonicalizeTypeAndAdvanceRevisionAtomically() {
        long before = repository.currentRevision();
        long typeId = service.createType(actor,
            new DictionaryCommands.CreateType("sys_user_sex", "User sex", 0, ""));
        assertThat(repository.currentRevision()).isEqualTo(before + 1);
        assertThat(service.findTypes(new DictionaryCommands.TypeQuery(
            "SYS_USER_SEX", null, 1, 20)).items().getFirst().dictType())
            .isEqualTo("SYS_USER_SEX");

        long dataId = service.createData(actor, new DictionaryCommands.CreateData(
            "sys_user_sex", "Male", "M", null, 1, ""));
        assertThat(repository.currentRevision()).isEqualTo(before + 2);
        assertThat(service.findData(new DictionaryCommands.DataQuery(
            "sys_user_sex", null, null, 1, 20)).items().getFirst().color())
            .isEqualTo("default");
        assertThat(service.queryBatch(List.of("sys_user_sex")))
            .containsOnlyKeys("SYS_USER_SEX");

        assertThatThrownBy(() -> service.createData(actor, new DictionaryCommands.CreateData(
            "SYS_USER_SEX", "Duplicate", "M", "success", 2, "")))
            .isInstanceOf(SystemDictionaryException.DataConflict.class);
        assertThat(repository.currentRevision()).isEqualTo(before + 2);

        assertThatThrownBy(() -> service.updateType(actor, typeId,
            new DictionaryCommands.UpdateType("SYS_USER_SEX", "Changed", 0, "", 99L)))
            .isInstanceOf(SystemDictionaryException.OptimisticLockConflict.class);
        assertThatThrownBy(() -> service.deleteType(actor, typeId, 0L))
            .isInstanceOf(SystemDictionaryException.DataConflict.class);

        service.deleteData(actor, dataId, 0L);
        service.deleteType(actor, typeId, 0L);
    }

    @Test
    @Order(3)
    void dictionaryUpdatePermissionOwnsEveryDictionaryDataMutation() {
        long typeId = service.createType(actor,
            new DictionaryCommands.CreateType("CONSOLIDATED_DATA", "Consolidated data", 0, ""));
        dsl.execute("""
            UPDATE iam_role_grant grant_row
               SET status='DISABLED'
              FROM iam_permission permission
             WHERE grant_row.permission_id=permission.id
               AND grant_row.role_id=91401
               AND permission.permission_code IN (
                 'dictionary-data:create','dictionary-data:update','dictionary-data:delete')
            """);
        try {
            long dataId = service.createData(actor, new DictionaryCommands.CreateData(
                "CONSOLIDATED_DATA", "Enabled", "1", "success", 1, ""));
            service.updateData(actor, dataId, new DictionaryCommands.UpdateData(
                "CONSOLIDATED_DATA", "Active", "1", "processing", 2, "", 0L));
            service.deleteData(actor, dataId, 1L);

            assertThat(count("""
                SELECT count(*) FROM iam_audit_event
                 WHERE target_type='DICTIONARY_DATA'
                   AND target_ref='%s'
                   AND permission_code='dictionary:update'
                """.formatted(dataId))).isEqualTo(3);
        } finally {
            dsl.execute("""
                UPDATE iam_role_grant grant_row
                   SET status='ACTIVE'
                  FROM iam_permission permission
                 WHERE grant_row.permission_id=permission.id
                   AND grant_row.role_id=91401
                   AND permission.permission_code IN (
                     'dictionary-data:create','dictionary-data:update','dictionary-data:delete')
                """);
        }
        service.deleteType(actor, typeId, 0L);
    }

    @Test
    @Order(4)
    void capacityAndDatabaseColorConstraintFailClosed() {
        service.createType(actor,
            new DictionaryCommands.CreateType("CAPACITY_TYPE", "Capacity", 0, ""));
        Long typeId = dsl.fetchOne(
            "SELECT id FROM sys_dictionary_type WHERE dict_type='CAPACITY_TYPE'")
            .get(0, Long.class);
        dsl.execute("""
            INSERT INTO sys_dictionary_data(dictionary_type_id,label,value,color,sort_order)
            SELECT ?, 'Label ' || value, value::text, 'default', 0
              FROM generate_series(1,1000) value
            """, typeId);
        assertThatThrownBy(() -> service.createData(actor, new DictionaryCommands.CreateData(
            "CAPACITY_TYPE", "Overflow", "overflow", "default", 0, "")))
            .isInstanceOf(SystemDictionaryException.DataConflict.class);
        assertThatThrownBy(() -> dsl.execute("""
            INSERT INTO sys_dictionary_data(dictionary_type_id,label,value,color,sort_order)
            VALUES (?, 'Invalid', 'invalid-color', 'blue', 0)
            """, typeId)).isInstanceOf(RuntimeException.class);
    }

    @Test
    @Order(5)
    void multipleRolesWithTheSameWritePermissionRemainDeterministic() {
        dsl.execute("""
            INSERT INTO iam_role(id,tenant_id,role_code,role_name,applicable_tenant_type,
                                 assignable,system_role,status)
            VALUES (91411,91001,'second-writer','Second Writer','PLATFORM',true,false,'ACTIVE')
            """);
        dsl.execute("INSERT INTO iam_membership_role(tenant_id,membership_id,role_id) VALUES (91001,91301,91411)");
        dsl.execute("""
            INSERT INTO iam_role_grant(id,tenant_id,role_id,permission_id,grant_key,status)
            SELECT 91711,91001,91411,id,'second-dictionary-create','ACTIVE'
              FROM iam_permission WHERE permission_code='dictionary:create'
            """);
        dsl.execute("""
            INSERT INTO iam_grant_dimension(id,grant_id,dimension_code,scope_mode)
            VALUES (91811,91711,'TENANT','TENANT_ALL')
            """);

        assertThat(service.createType(actor,
            new DictionaryCommands.CreateType("MULTI_ROLE_TYPE", "Multi role", 0, "")))
            .isPositive();
    }

    @Test
    @Order(6)
    void springManagedMutationRollsBackDataAuditAndRevisionTogether() {
        assertThat(AopUtils.isAopProxy(repository)).isTrue();
        long revisionBefore = repository.currentRevision();
        long auditBefore = count("""
            SELECT count(*) FROM iam_audit_event
             WHERE target_type='DICTIONARY_TYPE' AND action_code='CREATE'
            """);
        dsl.execute("""
            CREATE FUNCTION sys_test_reject_dictionary_revision() RETURNS trigger
            LANGUAGE plpgsql AS $$
            BEGIN
                RAISE EXCEPTION 'forced dictionary revision failure';
            END
            $$
            """);
        dsl.execute("""
            CREATE TRIGGER sys_test_reject_dictionary_revision
            BEFORE UPDATE ON sys_dictionary_catalog_revision
            FOR EACH ROW EXECUTE FUNCTION sys_test_reject_dictionary_revision()
            """);
        try {
            assertThatThrownBy(() -> service.createType(actor,
                new DictionaryCommands.CreateType(
                    "ROLLBACK_PROBE", "Rollback probe", 0, "")))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("forced dictionary revision failure");

            assertThat(count("""
                SELECT count(*) FROM sys_dictionary_type
                 WHERE dict_type='ROLLBACK_PROBE'
                """)).isZero();
            assertThat(count("""
                SELECT count(*) FROM iam_audit_event
                 WHERE target_type='DICTIONARY_TYPE' AND action_code='CREATE'
                """)).isEqualTo(auditBefore);
            assertThat(repository.currentRevision()).isEqualTo(revisionBefore);
        } finally {
            dsl.execute("DROP TRIGGER sys_test_reject_dictionary_revision ON sys_dictionary_catalog_revision");
            dsl.execute("DROP FUNCTION sys_test_reject_dictionary_revision()");
        }
    }

    private static void seedPreV28IdentitiesAndMenus(DSLContext seedDsl) {
        seedDsl.execute("""
            INSERT INTO iam_tenant(id,tenant_code,tenant_name,tenant_type,status,account_domain)
            VALUES
              (91001,'dict-platform','Dictionary Platform','PLATFORM','ACTIVE','PLATFORM'),
              (91002,'dict-merchant','Dictionary Merchant','DIRECT_MERCHANT','ACTIVE','MERCHANT'),
              (91003,'dict-agent','Dictionary Agent','AGENT','ACTIVE','AGENT')
            """);
        seedDsl.execute("""
            INSERT INTO iam_user(id,idp_issuer,idp_subject,display_name,status,account_domain,
                                 idp_provisioning_status)
            VALUES (91201,'local','dictionary-admin','Dictionary Admin','ACTIVE','PLATFORM','LOCAL_ONLY')
            """);
        seedDsl.execute("""
            INSERT INTO iam_authentication_credential(user_id,username,password_hash,status,account_domain)
            VALUES (91201,'dictionary-admin@example.test',NULL,'ACTIVE','PLATFORM')
            """);
        seedDsl.execute("""
            INSERT INTO iam_membership(id,tenant_id,user_id,status,account_domain)
            VALUES (91301,91001,91201,'ACTIVE','PLATFORM')
            """);
        seedDsl.execute("""
            INSERT INTO iam_role(id,tenant_id,role_code,role_name,applicable_tenant_type,
                                 assignable,system_role,status)
            VALUES
              (91401,91001,'dict-platform-admin','Dictionary Platform Admin','PLATFORM',false,true,'ACTIVE'),
              (91402,91002,'dict-merchant-admin','Dictionary Merchant Admin','DIRECT_MERCHANT',false,true,'ACTIVE'),
              (91403,91003,'dict-agent-admin','Dictionary Agent Admin','AGENT',false,true,'ACTIVE')
            """);
        seedDsl.execute("""
            INSERT INTO iam_membership_role(tenant_id,membership_id,role_id)
            VALUES (91001,91301,91401)
            """);
        seedDsl.execute("""
            INSERT INTO iam_menu(id,tenant_id,menu_type,menu_name,route_name,route_path,
                                 sort_order,status,meta_json,system_managed)
            VALUES
              (91501,91001,'DIRECTORY','System','System','/system',100,'ACTIVE','{}',true),
              (91502,91002,'DIRECTORY','System','System','/system',100,'ACTIVE','{}',true),
              (91503,91003,'DIRECTORY','System','System','/system',100,'ACTIVE','{}',true)
            """);
    }

    private static long count(String sql) {
        return dsl.fetchOne(sql).get(0, Long.class);
    }

    private static final class NoCache implements com.niv.payment.dictionary.core.DictionaryBatchCache {
        @Override
        public Optional<Map<String, List<DictionaryModels.DisplayValue>>> find(
            long revision, List<String> types) {
            return Optional.of(Map.of());
        }

        @Override
        public void store(long revision, Map<String, List<DictionaryModels.DisplayValue>> values) { }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableTransactionManagement
    static class TransactionManagementConfiguration { }
}
