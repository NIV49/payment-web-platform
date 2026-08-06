package com.niv.payment.adminapi;

import com.niv.payment.identity.oidc.JooqOidcIdentityRepository;
import com.niv.payment.identity.oidc.JooqTrustedEntryResolver;
import com.niv.payment.identity.lifecycle.JooqMfaRecoveryRepository;
import com.niv.payment.identity.lifecycle.FederatedIdentity;
import com.niv.payment.identity.lifecycle.IdentityInvitationRepository;
import com.niv.payment.identity.lifecycle.IdentityInvitationStep;
import com.niv.payment.identity.lifecycle.JooqIdentityInvitationRepository;
import com.niv.payment.identity.lifecycle.MemberInvitationCommand;
import com.niv.payment.identity.lifecycle.MfaRecoveryStep;
import com.niv.payment.identity.lifecycle.TenantBootstrapCommand;
import com.niv.payment.identity.lifecycle.TenantType;
import com.niv.payment.permission.domain.AccountDomain;
import com.niv.payment.permission.domain.AuthorizationSubject;
import org.flywaydb.core.Flyway;
import org.jooq.SQLDialect;
import org.jooq.impl.DSL;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class ProductionIdentityFoundationMigrationTest {
    private static final String GLOBAL_USERNAME_GUARD =
        "IAM-002 migration blocked: global username uniqueness must remain during expand";

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>(DockerImageName.parse(
        "postgres:18.4-alpine@sha256:9a8afca54e7861fd90fab5fdf4c42477a6b1cb7d293595148e674e0a3181de15")
        .asCompatibleSubstituteFor("postgres"))
        .withDatabaseName("payment_platform")
        .withUsername("payment_dev")
        .withPassword("payment_dev");

    @BeforeEach
    void cleanDatabase() {
        flyway(null).clean();
    }

    @Test
    void freshSchemaCreatesProductionIdentityFoundationWithoutContractingUsername() throws Exception {
        flyway(null).migrate();

        assertThat(currentSuccessfulVersion()).isEqualTo("27");
        assertThat(singleLong("""
            SELECT count(*) FROM information_schema.columns
             WHERE table_schema='public' AND table_name='iam_user'
               AND column_name IN ('identity_version','idp_provisioning_status')
               AND is_nullable='NO'
            """)).isEqualTo(2L);
        assertThat(singleLong("""
            SELECT count(*) FROM pg_constraint
             WHERE conrelid='iam_authentication_credential'::regclass
               AND contype='u'
               AND conname IN ('uk_iam_authentication_username',
                               'uk_iam_authentication_domain_username')
            """)).isEqualTo(2L);
        assertThat(singleLong("""
            SELECT count(*) FROM pg_constraint
             WHERE conrelid='iam_user'::regclass
               AND contype='u' AND conname='uk_iam_user_idp_identity'
            """)).isOne();
        assertThat(singleLong("""
            SELECT count(*) FROM information_schema.tables
             WHERE table_schema='public'
               AND table_name IN ('iam_tenant_entry_host',
                                  'iam_identity_lifecycle_outbox',
                                  'iam_identity_lifecycle_relay_state',
                                  'iam_mfa_recovery',
                                  'iam_identity_invitation',
                                  'iam_identity_invitation_role')
            """)).isEqualTo(6L);
        assertThat(singleLong("""
            SELECT count(*) FROM information_schema.columns
             WHERE table_schema='public' AND table_name='iam_identity_lifecycle_outbox'
               AND column_name IN ('payload','email','token','password','secret','recovery_code')
            """)).isZero();
        assertThat(singleLong("""
            SELECT count(*) FROM information_schema.columns
             WHERE table_schema='public'
               AND table_name IN ('iam_identity_invitation','iam_identity_invitation_role')
               AND column_name IN ('email','email_hash','payload','token','password','secret',
                                   'totp_secret','recovery_code')
            """)).isZero();
    }

    @Test
    void invitationReservationConstrainsActorRolesAndTargetToTheirDeclaredTenants() throws Exception {
        flyway(null).migrate();
        execute("""
            INSERT INTO iam_tenant(id,tenant_code,tenant_name,tenant_type,status,account_domain) VALUES
              (20501,'merchant-20501','Merchant 20501','DIRECT_MERCHANT','ACTIVE','MERCHANT'),
              (20502,'merchant-20502','Merchant 20502','DIRECT_MERCHANT','ACTIVE','MERCHANT');
            INSERT INTO iam_user(
                id,idp_issuer,idp_subject,display_name,status,account_domain,idp_provisioning_status
            ) VALUES(20503,'https://idp.example.test/realms/MERCHANT','actor-20503',
                     'Actor','ACTIVE','MERCHANT','PROVISIONED');
            INSERT INTO iam_authentication_credential(
                user_id,username,password_hash,status,account_domain
            ) VALUES(20503,'actor-20503',NULL,'ACTIVE','MERCHANT');
            INSERT INTO iam_membership(
                id,tenant_id,user_id,status,account_domain
            ) VALUES(20504,20501,20503,'ACTIVE','MERCHANT');
            INSERT INTO iam_role(
                id,tenant_id,role_code,role_name,applicable_tenant_type,
                assignable,system_role,status
            ) VALUES
              (20505,20501,'member','Member','DIRECT_MERCHANT',true,false,'ACTIVE'),
              (20506,20502,'other-member','Other Member','DIRECT_MERCHANT',true,false,'ACTIVE');
            INSERT INTO iam_identity_invitation(
                id,invitation_kind,tenant_id,account_domain,requested_by_tenant_id,
                requested_by_membership_id,idempotency_key,display_name,status
            ) VALUES(20507,'MEMBER',20501,'MERCHANT',20501,20504,
                     '2dc0c7a8-6d5b-468f-a26e-3d4bb0ebdd55','Invited Member','RESERVED');
            INSERT INTO iam_identity_invitation_role(invitation_id,tenant_id,role_id)
            VALUES(20507,20501,20505);
            """);

        assertThatThrownBy(() -> execute("""
            INSERT INTO iam_identity_invitation_role(invitation_id,tenant_id,role_id)
            VALUES(20507,20501,20506)
            """))
            .hasStackTraceContaining("fk_iam_identity_invitation_role_role");
        assertThatThrownBy(() -> execute("""
            INSERT INTO iam_identity_invitation(
                invitation_kind,tenant_id,account_domain,requested_by_tenant_id,
                requested_by_membership_id,idempotency_key,display_name,status
            ) VALUES('MEMBER',20502,'MERCHANT',20502,20504,
                     '3dc0c7a8-6d5b-468f-a26e-3d4bb0ebdd55','Cross Tenant','RESERVED')
            """))
            .hasStackTraceContaining("fk_iam_identity_invitation_actor");
    }

    @Test
    void invitationRepositoryActivatesOnlyOrdinaryRoleMembershipAfterOrderedRelay() throws Exception {
        flyway(null).migrate();
        execute("""
            INSERT INTO iam_tenant(id,tenant_code,tenant_name,tenant_type,status,account_domain)
            VALUES(20701,'merchant-20701','Merchant 20701','DIRECT_MERCHANT','ACTIVE','MERCHANT');
            INSERT INTO iam_user(
                id,idp_issuer,idp_subject,display_name,status,account_domain,idp_provisioning_status
            ) VALUES(20702,'https://idp.example.test/realms/MERCHANT','actor-20702',
                     'Merchant Administrator','ACTIVE','MERCHANT','PROVISIONED');
            INSERT INTO iam_authentication_credential(
                user_id,username,password_hash,status,account_domain
            ) VALUES(20702,'merchant-administrator',NULL,'ACTIVE','MERCHANT');
            INSERT INTO iam_membership(id,tenant_id,user_id,status,account_domain)
            VALUES(20703,20701,20702,'ACTIVE','MERCHANT');
            INSERT INTO iam_role(
                id,tenant_id,role_code,role_name,applicable_tenant_type,
                assignable,system_role,status
            ) VALUES
              (20704,20701,'tenant-system-admin','Tenant System Administrator',
               'DIRECT_MERCHANT',false,true,'ACTIVE'),
              (20705,20701,'settlement-reader','Settlement Reader',
               'DIRECT_MERCHANT',true,false,'ACTIVE');
            INSERT INTO iam_membership_role(tenant_id,membership_id,role_id,assigned_by)
            VALUES(20701,20703,20704,20703);
            """);

        UUID key = UUID.fromString("92d8b47e-ec71-4421-a121-e2b5e5e7ee9a");
        AuthorizationSubject actor = new AuthorizationSubject(20702, 20703, 20701,
            null, 0, 0, true);
        try (Connection connection = connection()) {
            var repository = new JooqIdentityInvitationRepository(
                DSL.using(connection, SQLDialect.POSTGRES), () -> "trace-invitation");
            assertThatThrownBy(() -> repository.reserveMember(AccountDomain.MERCHANT, actor,
                new MemberInvitationCommand("blocked@example.test", "Blocked", List.of(20704L), key)))
                .isInstanceOf(SecurityException.class);

            IdentityInvitationRepository.Reservation reservation = repository.reserveMember(
                AccountDomain.MERCHANT, actor,
                new MemberInvitationCommand("member@example.test", "Invited Member",
                    List.of(20705L), key));
            var invitation = repository.attachIdentity(reservation, new FederatedIdentity(
                "https://idp.example.test/realms/MERCHANT", "invited-subject-20706",
                "invite-" + key));
            assertThat(invitation.status())
                .isEqualTo(IdentityInvitationRepository.Status.PROVISION_PENDING);

            Instant clock = Instant.parse("2030-08-06T01:00:00Z");
            for (IdentityInvitationStep expected : IdentityInvitationStep.values()) {
                var task = repository.claimNext(AccountDomain.MERCHANT, clock,
                    Duration.ofSeconds(30)).orElseThrow();
                assertThat(task.nextStep()).isEqualTo(expected);
                repository.completeStep(task.invitationId(), expected, clock.plusSeconds(1));
                clock = clock.plusSeconds(2);
            }

            assertThat(singleString("SELECT status FROM iam_identity_invitation WHERE id="
                + invitation.invitationId())).isEqualTo("COMPLETED");
            assertThat(singleString("SELECT status FROM iam_membership WHERE id="
                + invitation.membershipId())).isEqualTo("ACTIVE");
            assertThat(singleLong("SELECT count(*) FROM iam_membership_role WHERE membership_id="
                + invitation.membershipId() + " AND role_id=20705")).isOne();
            assertThat(singleString("SELECT status FROM iam_identity_lifecycle_relay_state"))
                .isEqualTo("PUBLISHED");
        }
    }

    @Test
    void tenantBootstrapReusesExistingRealmIdentityAndActivatesBoundaryOnlyAtCompletion()
        throws Exception {
        flyway(null).migrate();
        execute("""
            INSERT INTO iam_tenant(id,tenant_code,tenant_name,tenant_type,status,account_domain)
            VALUES
              (20801,'platform-20801','Platform Operations','PLATFORM','ACTIVE','PLATFORM'),
              (20802,'merchant-source','Merchant Source','DIRECT_MERCHANT','ACTIVE','MERCHANT');
            INSERT INTO iam_user(
                id,idp_issuer,idp_subject,display_name,status,account_domain,idp_provisioning_status
            ) VALUES
              (20803,'https://idp.example.test/realms/PLATFORM','platform-actor-20803',
               'Platform Administrator','ACTIVE','PLATFORM','PROVISIONED'),
              (20804,'https://idp.example.test/realms/MERCHANT','merchant-admin-20804',
               'Existing Merchant Administrator','ACTIVE','MERCHANT','PROVISIONED');
            INSERT INTO iam_authentication_credential(
                user_id,username,password_hash,status,account_domain
            ) VALUES
              (20803,'platform-administrator',NULL,'ACTIVE','PLATFORM'),
              (20804,'existing-merchant-administrator',NULL,'ACTIVE','MERCHANT');
            INSERT INTO iam_membership(id,tenant_id,user_id,status,account_domain)
            VALUES
              (20805,20801,20803,'ACTIVE','PLATFORM'),
              (20806,20802,20804,'ACTIVE','MERCHANT');
            INSERT INTO iam_role(
                id,tenant_id,role_code,role_name,applicable_tenant_type,
                assignable,system_role,status
            ) VALUES(20807,20801,'platform-system-admin','Platform System Administrator',
                     'PLATFORM',false,true,'ACTIVE');
            INSERT INTO iam_membership_role(tenant_id,membership_id,role_id,assigned_by)
            VALUES(20801,20805,20807,20805);
            """);

        UUID key = UUID.fromString("a30a651a-61aa-44ea-9496-22c7ae0dc288");
        AuthorizationSubject actor = new AuthorizationSubject(20803, 20805, 20801,
            null, 0, 0, true);
        try (Connection connection = connection()) {
            var repository = new JooqIdentityInvitationRepository(
                DSL.using(connection, SQLDialect.POSTGRES), () -> "trace-bootstrap");
            var reservation = repository.reserve(actor, AccountDomain.MERCHANT,
                new TenantBootstrapCommand("merchant-new", "Merchant New",
                    TenantType.DIRECT_MERCHANT, "merchant-new.admin.example.test",
                    "existing@example.test", "Existing Merchant Administrator", key));
            assertThat(singleString("SELECT status FROM iam_tenant WHERE id="
                + reservation.tenantId())).isEqualTo("DISABLED");
            assertThat(singleString("SELECT status FROM iam_tenant_entry_host WHERE tenant_id="
                + reservation.tenantId())).isEqualTo("DISABLED");

            var bootstrap = repository.attachIdentity(reservation, new FederatedIdentity(
                "https://idp.example.test/realms/MERCHANT", "merchant-admin-20804",
                "existing-merchant-administrator", FederatedIdentity.Mode.EXISTING_ACTIVE));
            Instant clock = Instant.parse("2030-08-06T02:00:00Z");
            var task = repository.claimNext(AccountDomain.MERCHANT, clock,
                Duration.ofSeconds(30)).orElseThrow();
            assertThat(task.nextStep()).isEqualTo(IdentityInvitationStep.APPLICATION_ACTIVATION);
            repository.completeStep(task.invitationId(), task.nextStep(), clock.plusSeconds(1));

            assertThat(singleString("SELECT status FROM iam_tenant WHERE id="
                + bootstrap.tenantId())).isEqualTo("ACTIVE");
            assertThat(singleString("SELECT status FROM iam_tenant_entry_host WHERE tenant_id="
                + bootstrap.tenantId())).isEqualTo("ACTIVE");
            assertThat(singleString("SELECT status FROM iam_membership WHERE id="
                + bootstrap.firstAdministratorMembershipId())).isEqualTo("ACTIVE");
            assertThat(singleLong("SELECT count(*) FROM iam_identity_invitation WHERE id="
                + bootstrap.invitationId()
                + " AND identity_mode='EXISTING_ACTIVE'"
                + " AND keycloak_user_enabled_at IS NULL AND action_email_sent_at IS NULL"))
                .isOne();
        }
    }

    @Test
    void v20UpgradeBackfillsProvisioningStateAndPreservesDomainUsernameRollout() throws Exception {
        flyway("20").migrate();
        execute("""
            INSERT INTO iam_tenant(id,tenant_code,tenant_name,tenant_type,status,account_domain) VALUES
              (21001,'merchant-21001','Merchant 21001','DIRECT_MERCHANT','ACTIVE','MERCHANT'),
              (21002,'agent-21002','Agent 21002','AGENT','ACTIVE','AGENT');
            INSERT INTO iam_user(id,idp_issuer,idp_subject,display_name,status,account_domain) VALUES
              (21003,'local','legacy-local','Legacy Local','ACTIVE','MERCHANT'),
              (21004,'https://agent-id.example.test/realms/agent','external-21004',
               'External Agent','ACTIVE','AGENT');
            INSERT INTO iam_authentication_credential(
                user_id,username,password_hash,status,account_domain
            ) VALUES
              (21003,'legacy-local',NULL,'DISABLED','MERCHANT'),
              (21004,'external-agent',NULL,'DISABLED','AGENT');
            """);

        flyway(null).migrate();

        assertThat(singleString("""
            SELECT idp_provisioning_status FROM iam_user WHERE id=21003
            """)).isEqualTo("LOCAL_ONLY");
        assertThat(singleString("""
            SELECT idp_provisioning_status FROM iam_user WHERE id=21004
            """)).isEqualTo("PROVISIONED");
        assertThat(singleLong("SELECT identity_version FROM iam_user WHERE id=21004"))
            .isZero();

        execute("ALTER TABLE iam_authentication_credential "
            + "DROP CONSTRAINT uk_iam_authentication_username");
        execute("""
            INSERT INTO iam_user(
                id,idp_issuer,idp_subject,display_name,status,account_domain,
                idp_provisioning_status
            ) VALUES
              (21005,'https://merchant-id.example.test/realms/merchant','merchant-21005',
               'Merchant Same Login','ACTIVE','MERCHANT','PROVISIONED'),
              (21006,'https://agent-id.example.test/realms/agent','agent-21006',
               'Agent Same Login','ACTIVE','AGENT','PROVISIONED'),
              (21007,'https://merchant-id.example.test/realms/merchant','merchant-21007',
               'Merchant Duplicate Login','ACTIVE','MERCHANT','PROVISIONED');
            INSERT INTO iam_authentication_credential(
                user_id,username,password_hash,status,account_domain
            ) VALUES
              (21005,'shared-login',NULL,'DISABLED','MERCHANT'),
              (21006,'shared-login',NULL,'DISABLED','AGENT');
            """);

        assertThatThrownBy(() -> execute("""
            INSERT INTO iam_authentication_credential(
                user_id,username,password_hash,status,account_domain
            ) VALUES(21007,'shared-login',NULL,'DISABLED','MERCHANT')
            """))
            .hasStackTraceContaining("uk_iam_authentication_domain_username");
    }

    @Test
    void hostRegistryAndLifecycleOutboxFailClosedAcrossDomains() throws Exception {
        flyway(null).migrate();
        execute("""
            INSERT INTO iam_tenant(id,tenant_code,tenant_name,tenant_type,status,account_domain) VALUES
              (22001,'merchant-22001','Merchant 22001','DIRECT_MERCHANT','ACTIVE','MERCHANT'),
              (22002,'agent-22002','Agent 22002','AGENT','ACTIVE','AGENT');
            INSERT INTO iam_user(
                id,idp_issuer,idp_subject,display_name,status,account_domain,
                idp_provisioning_status
            ) VALUES(22003,'https://merchant-id.example.test/realms/merchant','merchant-22003',
                     'Merchant User','ACTIVE','MERCHANT','PROVISIONED');
            INSERT INTO iam_tenant_entry_host(entry_host,account_domain,tenant_id,status)
            VALUES('merchant-22001.admin.example.test','MERCHANT',22001,'ACTIVE');
            INSERT INTO iam_identity_lifecycle_outbox(
                user_id,tenant_id,realm,operation_type,idempotency_key
            ) VALUES(
                22003,22001,'MERCHANT','MFA_RECOVERY',
                '2dc0c7a8-6d5b-468f-a26e-3d4bb0ebdd55'
            );
            """);

        assertThat(singleLong("SELECT count(*) FROM iam_identity_lifecycle_relay_state"))
            .isOne();
        assertThatThrownBy(() -> execute("""
            UPDATE iam_identity_lifecycle_outbox SET operation_type='DISABLE' WHERE user_id=22003
            """))
            .hasStackTraceContaining("iam_identity_lifecycle_outbox is append-only");
        assertThatThrownBy(() -> execute("""
            INSERT INTO iam_tenant_entry_host(entry_host,account_domain,tenant_id,status)
            VALUES('wrong-domain.admin.example.test','MERCHANT',22002,'ACTIVE')
            """))
            .hasStackTraceContaining("fk_iam_entry_host_tenant_domain");
        assertThatThrownBy(() -> execute("""
            INSERT INTO iam_tenant_entry_host(entry_host,account_domain,tenant_id,status)
            VALUES('Uppercase.admin.example.test','MERCHANT',22001,'ACTIVE')
            """))
            .hasStackTraceContaining("ck_iam_entry_host_canonical");
        assertThatThrownBy(() -> execute("""
            INSERT INTO iam_identity_lifecycle_outbox(
                user_id,tenant_id,realm,operation_type,idempotency_key
            ) VALUES(
                22003,22002,'AGENT','DISABLE',
                '0f0680d6-3ff1-4c2d-b1d8-12cf546f0dfa'
            )
            """))
            .hasStackTraceContaining("fk_iam_identity_outbox_user_realm");
    }

    @Test
    void mfaRecoveryRequiresDistinctSameTenantActorAndAllFourRevocations() throws Exception {
        flyway(null).migrate();
        execute("""
            INSERT INTO iam_tenant(id,tenant_code,tenant_name,tenant_type,status,account_domain)
            VALUES(22501,'merchant-22501','Merchant 22501','DIRECT_MERCHANT','ACTIVE','MERCHANT');
            INSERT INTO iam_user(
                id,idp_issuer,idp_subject,display_name,status,account_domain,idp_provisioning_status
            ) VALUES
              (22502,'https://idp.example.test/realms/MERCHANT','subject-22502',
               'Recovery Target','ACTIVE','MERCHANT','RECOVERY_PENDING'),
              (22503,'https://idp.example.test/realms/MERCHANT','subject-22503',
               'Recovery Actor','ACTIVE','MERCHANT','PROVISIONED');
            INSERT INTO iam_membership(id,tenant_id,user_id,status,account_domain) VALUES
              (22504,22501,22502,'ACTIVE','MERCHANT'),
              (22505,22501,22503,'ACTIVE','MERCHANT');
            INSERT INTO iam_identity_lifecycle_outbox(
                id,user_id,tenant_id,realm,operation_type,idempotency_key
            ) VALUES(
                22506,22502,22501,'MERCHANT','MFA_RECOVERY',
                'e74042d8-f211-463f-8548-74b3a963938c'
            );
            INSERT INTO iam_mfa_recovery(
                id,user_id,tenant_id,target_membership_id,requested_by_membership_id,
                account_domain,idempotency_key,lifecycle_event_record_id
            ) VALUES(
                22507,22502,22501,22504,22505,'MERCHANT',
                'e74042d8-f211-463f-8548-74b3a963938c',22506
            );
            """);

        assertThat(singleString("SELECT status FROM iam_mfa_recovery WHERE id=22507"))
            .isEqualTo("RECOVERY_PENDING");
        assertThatThrownBy(() -> execute("""
            UPDATE iam_mfa_recovery
               SET status='COMPLETED', completed_at=now()
             WHERE id=22507
            """))
            .hasStackTraceContaining("ck_iam_mfa_recovery_completion");
        assertThatThrownBy(() -> execute("""
            INSERT INTO iam_identity_lifecycle_outbox(
                id,user_id,tenant_id,realm,operation_type,idempotency_key
            ) VALUES(
                22508,22502,22501,'MERCHANT','MFA_RECOVERY',
                '7026a736-9ce7-4dbc-a0bb-905304f1fa74'
            );
            INSERT INTO iam_mfa_recovery(
                user_id,tenant_id,target_membership_id,requested_by_membership_id,
                account_domain,idempotency_key,lifecycle_event_record_id
            ) VALUES(
                22502,22501,22504,22504,'MERCHANT',
                '7026a736-9ce7-4dbc-a0bb-905304f1fa74',22508
            )
            """))
            .hasStackTraceContaining("ck_iam_mfa_recovery_distinct_actor");

        execute("""
            UPDATE iam_mfa_recovery
               SET mfa_credentials_revoked_at=now(),
                   recovery_codes_revoked_at=now(),
                   keycloak_sessions_revoked_at=now(),
                   application_sessions_revoked_at=now(),
                   status='COMPLETED', completed_at=now()
             WHERE id=22507
            """);
        assertThat(singleString("SELECT status FROM iam_mfa_recovery WHERE id=22507"))
            .isEqualTo("COMPLETED");
    }

    @Test
    void mfaRecoveryRepositoryBlocksLoginAndCompletesOnlyAfterOrderedRevocation() throws Exception {
        flyway(null).migrate();
        execute("""
            INSERT INTO iam_tenant(id,tenant_code,tenant_name,tenant_type,status,account_domain)
            VALUES(22601,'merchant-22601','Merchant 22601','DIRECT_MERCHANT','ACTIVE','MERCHANT');
            INSERT INTO iam_user(
                id,idp_issuer,idp_subject,display_name,status,account_domain,idp_provisioning_status
            ) VALUES
              (22602,'https://idp.example.test/realms/MERCHANT','subject-22602',
               'Recovery Target','ACTIVE','MERCHANT','PROVISIONED'),
              (22603,'https://idp.example.test/realms/MERCHANT','subject-22603',
               'Recovery Actor','ACTIVE','MERCHANT','PROVISIONED');
            INSERT INTO iam_authentication_credential(
                user_id,username,password_hash,status,account_domain
            ) VALUES
              (22602,'recovery-target',NULL,'ACTIVE','MERCHANT'),
              (22603,'recovery-actor',NULL,'ACTIVE','MERCHANT');
            INSERT INTO iam_membership(id,tenant_id,user_id,status,account_domain) VALUES
              (22604,22601,22602,'ACTIVE','MERCHANT'),
              (22605,22601,22603,'ACTIVE','MERCHANT');
            INSERT INTO iam_role(
                id,tenant_id,role_code,role_name,applicable_tenant_type,
                assignable,system_role,status
            ) VALUES(
                22606,22601,'tenant-system-admin','Tenant System Administrator',
                'DIRECT_MERCHANT',false,true,'ACTIVE'
            );
            INSERT INTO iam_membership_role(tenant_id,membership_id,role_id,assigned_by)
            VALUES(22601,22605,22606,22605);
            """);

        UUID key = UUID.fromString("6e9057e7-aeb8-49f8-b09d-f4661e00d0d5");
        AuthorizationSubject actor = new AuthorizationSubject(22603, 22605, 22601,
            null, 0, 0, true);
        try (Connection connection = connection()) {
            var dsl = DSL.using(connection, SQLDialect.POSTGRES);
            var recoveries = new JooqMfaRecoveryRepository(dsl, () -> "trace-mfa-recovery");

            var requested = recoveries.request(AccountDomain.MERCHANT, actor, 22604, key);
            assertThat(recoveries.request(AccountDomain.MERCHANT, actor, 22604, key))
                .isEqualTo(requested);
            assertThat(singleString("SELECT idp_provisioning_status FROM iam_user WHERE id=22602"))
                .isEqualTo("RECOVERY_PENDING");
            assertThat(singleString("SELECT status FROM iam_authentication_credential WHERE user_id=22602"))
                .isEqualTo("DISABLED");
            assertThat(singleLong("SELECT identity_version FROM iam_user WHERE id=22602")).isOne();
            assertThat(singleLong("SELECT session_version FROM iam_membership WHERE id=22604")).isOne();

            Instant clock = Instant.parse("2030-08-05T10:00:00Z");
            for (MfaRecoveryStep expected : MfaRecoveryStep.values()) {
                var task = recoveries.claimNext(AccountDomain.MERCHANT, clock, Duration.ofSeconds(30))
                    .orElseThrow();
                assertThat(task.nextStep()).isEqualTo(expected);
                assertThat(task.issuer()).isEqualTo("https://idp.example.test/realms/MERCHANT");
                assertThat(task.subject()).isEqualTo("subject-22602");
                recoveries.completeStep(task.recoveryId(), expected, clock.plusSeconds(1));
                clock = clock.plusSeconds(2);
            }

            assertThat(singleString("SELECT status FROM iam_mfa_recovery WHERE id="
                + requested.recoveryId())).isEqualTo("COMPLETED");
            assertThat(singleString("SELECT status FROM iam_identity_lifecycle_relay_state"))
                .isEqualTo("PUBLISHED");
            assertThat(singleString("SELECT idp_provisioning_status FROM iam_user WHERE id=22602"))
                .isEqualTo("PROVISIONED");
            assertThat(singleString("SELECT status FROM iam_authentication_credential WHERE user_id=22602"))
                .isEqualTo("ACTIVE");
            assertThat(singleLong("SELECT identity_version FROM iam_user WHERE id=22602"))
                .isEqualTo(2L);
            assertThat(singleLong("SELECT session_version FROM iam_membership WHERE id=22604"))
                .isEqualTo(2L);
            assertThat(singleLong("SELECT count(*) FROM iam_audit_event "
                + "WHERE action_code IN ('MFA_RECOVERY_REQUEST','MFA_RECOVERY_COMPLETE')"))
                .isEqualTo(2L);
        }
    }

    @Test
    void missingGlobalUsernameConstraintBlocksExpandAtomically() throws Exception {
        flyway("20").migrate();
        execute("ALTER TABLE iam_authentication_credential "
            + "DROP CONSTRAINT uk_iam_authentication_username");

        assertThatThrownBy(() -> flyway(null).migrate())
            .hasStackTraceContaining(GLOBAL_USERNAME_GUARD);

        assertThat(currentSuccessfulVersion()).isEqualTo("20");
        assertThat(singleLong("""
            SELECT count(*) FROM information_schema.columns
             WHERE table_schema='public' AND table_name='iam_user'
               AND column_name='identity_version'
            """)).isZero();
        assertThat(singleLong("""
            SELECT count(*) FROM information_schema.tables
             WHERE table_schema='public' AND table_name='iam_tenant_entry_host'
            """)).isZero();
    }

    @Test
    void oidcLookupUsesTrustedHostAndExactIssuerSubjectWithoutRequiringAPasswordHash() throws Exception {
        flyway(null).migrate();
        execute("""
            INSERT INTO iam_tenant(
                id,tenant_code,tenant_name,tenant_type,status,account_domain
            ) VALUES(23001,'platform-oidc','Platform OIDC','PLATFORM','ACTIVE','PLATFORM');
            INSERT INTO iam_user(
                id,idp_issuer,idp_subject,display_name,status,account_domain,
                idp_provisioning_status
            ) VALUES(
                23002,'https://idp.example.test/realms/PLATFORM','platform-subject-23002',
                'Platform OIDC User','ACTIVE','PLATFORM','PROVISIONED'
            );
            INSERT INTO iam_authentication_credential(
                user_id,username,password_hash,status,account_domain
            ) VALUES(23002,'platform-oidc-user',NULL,'ACTIVE','PLATFORM');
            INSERT INTO iam_membership(
                id,tenant_id,user_id,department_id,status,account_domain
            ) VALUES(23003,23001,23002,NULL,'ACTIVE','PLATFORM');
            INSERT INTO iam_role(
                id,tenant_id,role_code,role_name,applicable_tenant_type,
                assignable,system_role,status
            ) VALUES(23004,23001,'oidc-admin','OIDC Administrator','PLATFORM',false,true,'ACTIVE');
            INSERT INTO iam_membership_role(tenant_id,membership_id,role_id,assigned_by)
            VALUES(23001,23003,23004,23003);
            INSERT INTO iam_role_grant(
                id,tenant_id,role_id,permission_id,grant_key,status,created_by,updated_by
            ) SELECT 23005,23001,23004,id,'system-backoffice-access','ACTIVE',23003,23003
                FROM iam_permission WHERE permission_code='backoffice:platform-access';
            INSERT INTO iam_grant_dimension(id,grant_id,dimension_code,scope_mode)
            VALUES(23006,23005,'TENANT','TENANT_ALL');
            INSERT INTO iam_tenant_entry_host(entry_host,account_domain,tenant_id,status)
            VALUES('ops.example.test','PLATFORM',23001,'ACTIVE');
            """);

        try (Connection connection = connection()) {
            var dsl = DSL.using(connection, SQLDialect.POSTGRES);
            var entries = new JooqTrustedEntryResolver(dsl);
            var identities = new JooqOidcIdentityRepository(dsl);

            var entry = entries.findActive("ops.example.test").orElseThrow();
            assertThat(entry.accountDomain()).isEqualTo(AccountDomain.PLATFORM);
            assertThat(entry.tenantId()).isEqualTo(23001L);
            var account = identities.findActive(AccountDomain.PLATFORM, 23001L,
                "https://idp.example.test/realms/PLATFORM", "platform-subject-23002").orElseThrow();
            assertThat(account.userId()).isEqualTo(23002L);
            assertThat(account.membershipId()).isEqualTo(23003L);
            assertThat(identities.findActive(AccountDomain.PLATFORM, 23001L,
                "https://idp.example.test/realms/PLATFORM", "wrong-subject")).isEmpty();
        }
    }

    private static String currentSuccessfulVersion() throws Exception {
        return singleString("""
            SELECT version FROM flyway_schema_history
             WHERE success ORDER BY installed_rank DESC LIMIT 1
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

    private static String singleString(String sql) throws Exception {
        try (Connection connection = connection();
             Statement statement = connection.createStatement();
             ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private static Flyway flyway(String target) {
        var configuration = PostgresFlywayTestSupport.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .cleanDisabled(false);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private static Connection connection() throws Exception {
        return DriverManager.getConnection(
            POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
