package com.niv.payment.identity.lifecycle;

import com.niv.payment.permission.domain.AccountDomain;
import com.niv.payment.permission.domain.AuthorizationSubject;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;
import org.jooq.TableField;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static com.niv.payment.permission.persistence.jooq.generated.Sequences.IAM_ID_SEQ;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_AUTHENTICATION_CREDENTIAL;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_AUDIT_EVENT;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_IDENTITY_LIFECYCLE_OUTBOX;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_IDENTITY_LIFECYCLE_RELAY_STATE;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_MEMBERSHIP;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_MEMBERSHIP_ROLE;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_MFA_RECOVERY;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_ROLE;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_TENANT;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_USER;

public final class JooqMfaRecoveryRepository implements MfaRecoveryRepository {
    private static final String ACTIVE = "ACTIVE";
    private static final String DISABLED = "DISABLED";
    private static final String PENDING = "RECOVERY_PENDING";
    private static final String COMPLETED = "COMPLETED";
    private static final JSONB EMPTY_JSON = JSONB.valueOf("{}");

    private final DSLContext dsl;
    private final Supplier<String> traceIdSupplier;

    public JooqMfaRecoveryRepository(DSLContext dsl, Supplier<String> traceIdSupplier) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
        this.traceIdSupplier = Objects.requireNonNull(traceIdSupplier, "traceIdSupplier");
    }

    @Override
    public RecoveryRequest request(AccountDomain accountDomain, AuthorizationSubject actor,
                                   long targetMembershipId, UUID idempotencyKey) {
        Objects.requireNonNull(accountDomain, "accountDomain");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(idempotencyKey, "idempotencyKey");
        return dsl.transactionResult(configuration -> {
            DSLContext tx = configuration.dsl();
            tx.execute("select pg_advisory_xact_lock(hashtextextended(?::text, 0))",
                idempotencyKey.toString());
            var existing = tx.select(IAM_MFA_RECOVERY.ID, IAM_MFA_RECOVERY.ACCOUNT_DOMAIN,
                    IAM_MFA_RECOVERY.TENANT_ID, IAM_MFA_RECOVERY.TARGET_MEMBERSHIP_ID,
                    IAM_MFA_RECOVERY.REQUESTED_BY_MEMBERSHIP_ID, IAM_MFA_RECOVERY.STATUS)
                .from(IAM_MFA_RECOVERY)
                .where(IAM_MFA_RECOVERY.IDEMPOTENCY_KEY.eq(idempotencyKey))
                .fetchOne();
            if (existing != null) {
                if (!accountDomain.name().equals(existing.get(IAM_MFA_RECOVERY.ACCOUNT_DOMAIN))
                    || actor.tenantId() != existing.get(IAM_MFA_RECOVERY.TENANT_ID)
                    || targetMembershipId != existing.get(IAM_MFA_RECOVERY.TARGET_MEMBERSHIP_ID)
                    || actor.membershipId() != existing.get(IAM_MFA_RECOVERY.REQUESTED_BY_MEMBERSHIP_ID)) {
                    throw new IllegalArgumentException("Idempotency key belongs to another MFA recovery request");
                }
                return new RecoveryRequest(existing.get(IAM_MFA_RECOVERY.ID),
                    Status.valueOf(existing.get(IAM_MFA_RECOVERY.STATUS)));
            }
            return create(tx, accountDomain, actor, targetMembershipId, idempotencyKey);
        });
    }

    private RecoveryRequest create(DSLContext tx, AccountDomain accountDomain, AuthorizationSubject actor,
                                   long targetMembershipId, UUID idempotencyKey) {
        String domain = accountDomain.name();
        Long tenant = tx.select(IAM_TENANT.ID).from(IAM_TENANT)
            .where(IAM_TENANT.ID.eq(actor.tenantId())
                .and(IAM_TENANT.ACCOUNT_DOMAIN.eq(domain))
                .and(IAM_TENANT.STATUS.eq(ACTIVE)))
            .forUpdate().fetchOne(IAM_TENANT.ID);
        if (tenant == null) {
            throw new SecurityException("Active tenant in the fixed account domain is required");
        }

        var actorRow = tx.select(IAM_MEMBERSHIP.USER_ID, IAM_MEMBERSHIP.PERMISSION_VERSION,
                IAM_MEMBERSHIP.SESSION_VERSION)
            .from(IAM_MEMBERSHIP)
            .join(IAM_USER).on(IAM_USER.ID.eq(IAM_MEMBERSHIP.USER_ID)
                .and(IAM_USER.ACCOUNT_DOMAIN.eq(domain)).and(IAM_USER.STATUS.eq(ACTIVE)))
            .join(IAM_AUTHENTICATION_CREDENTIAL)
                .on(IAM_AUTHENTICATION_CREDENTIAL.USER_ID.eq(IAM_USER.ID)
                    .and(IAM_AUTHENTICATION_CREDENTIAL.ACCOUNT_DOMAIN.eq(domain))
                    .and(IAM_AUTHENTICATION_CREDENTIAL.STATUS.eq(ACTIVE)))
            .where(IAM_MEMBERSHIP.ID.eq(actor.membershipId())
                .and(IAM_MEMBERSHIP.TENANT_ID.eq(actor.tenantId()))
                .and(IAM_MEMBERSHIP.ACCOUNT_DOMAIN.eq(domain))
                .and(IAM_MEMBERSHIP.USER_ID.eq(actor.userId()))
                .and(IAM_MEMBERSHIP.STATUS.eq(ACTIVE)))
            .forUpdate().of(IAM_MEMBERSHIP, IAM_USER, IAM_AUTHENTICATION_CREDENTIAL).fetchOne();
        if (actorRow == null
            || actorRow.get(IAM_MEMBERSHIP.PERMISSION_VERSION) != actor.permissionVersion()
            || actorRow.get(IAM_MEMBERSHIP.SESSION_VERSION) != actor.sessionVersion()) {
            throw new SecurityException("MFA recovery actor session is stale");
        }
        boolean systemAdministrator = tx.fetchExists(tx.selectOne()
            .from(IAM_MEMBERSHIP_ROLE)
            .join(IAM_ROLE).on(IAM_ROLE.ID.eq(IAM_MEMBERSHIP_ROLE.ROLE_ID)
                .and(IAM_ROLE.TENANT_ID.eq(IAM_MEMBERSHIP_ROLE.TENANT_ID)))
            .where(IAM_MEMBERSHIP_ROLE.TENANT_ID.eq(actor.tenantId())
                .and(IAM_MEMBERSHIP_ROLE.MEMBERSHIP_ID.eq(actor.membershipId()))
                .and(IAM_ROLE.SYSTEM_ROLE.isTrue())
                .and(IAM_ROLE.STATUS.eq(ACTIVE))
                .and(IAM_ROLE.DELETED_AT.isNull())));
        if (!systemAdministrator) {
            throw new SecurityException("MFA recovery requires an active tenant system administrator");
        }

        var target = tx.select(IAM_MEMBERSHIP.USER_ID, IAM_USER.IDP_ISSUER,
                IAM_USER.IDP_SUBJECT, IAM_USER.IDP_PROVISIONING_STATUS,
                IAM_AUTHENTICATION_CREDENTIAL.STATUS)
            .from(IAM_MEMBERSHIP)
            .join(IAM_USER).on(IAM_USER.ID.eq(IAM_MEMBERSHIP.USER_ID)
                .and(IAM_USER.ACCOUNT_DOMAIN.eq(domain)))
            .join(IAM_AUTHENTICATION_CREDENTIAL)
                .on(IAM_AUTHENTICATION_CREDENTIAL.USER_ID.eq(IAM_USER.ID)
                    .and(IAM_AUTHENTICATION_CREDENTIAL.ACCOUNT_DOMAIN.eq(domain)))
            .where(IAM_MEMBERSHIP.ID.eq(targetMembershipId)
                .and(IAM_MEMBERSHIP.TENANT_ID.eq(actor.tenantId()))
                .and(IAM_MEMBERSHIP.ACCOUNT_DOMAIN.eq(domain))
                .and(IAM_MEMBERSHIP.STATUS.eq(ACTIVE))
                .and(IAM_USER.STATUS.eq(ACTIVE)))
            .forUpdate().of(IAM_MEMBERSHIP, IAM_USER, IAM_AUTHENTICATION_CREDENTIAL).fetchOne();
        if (target == null || "local".equals(target.get(IAM_USER.IDP_ISSUER))
            || !"PROVISIONED".equals(target.get(IAM_USER.IDP_PROVISIONING_STATUS))
            || !ACTIVE.equals(target.get(IAM_AUTHENTICATION_CREDENTIAL.STATUS))) {
            throw new IllegalStateException("Target is not an active provisioned OIDC identity");
        }
        long targetUserId = target.get(IAM_MEMBERSHIP.USER_ID);
        if (targetUserId == actor.userId()) {
            throw new IllegalArgumentException("MFA recovery requires another user");
        }
        if (tx.fetchExists(tx.selectOne().from(IAM_MFA_RECOVERY)
            .where(IAM_MFA_RECOVERY.ACCOUNT_DOMAIN.eq(domain)
                .and(IAM_MFA_RECOVERY.USER_ID.eq(targetUserId))
                .and(IAM_MFA_RECOVERY.STATUS.eq(PENDING))))) {
            throw new IllegalStateException("An MFA recovery is already pending for the target identity");
        }

        long eventId = nextId(tx);
        long recoveryId = nextId(tx);
        tx.insertInto(IAM_IDENTITY_LIFECYCLE_OUTBOX)
            .set(IAM_IDENTITY_LIFECYCLE_OUTBOX.ID, eventId)
            .set(IAM_IDENTITY_LIFECYCLE_OUTBOX.USER_ID, targetUserId)
            .set(IAM_IDENTITY_LIFECYCLE_OUTBOX.TENANT_ID, actor.tenantId())
            .set(IAM_IDENTITY_LIFECYCLE_OUTBOX.REALM, domain)
            .set(IAM_IDENTITY_LIFECYCLE_OUTBOX.OPERATION_TYPE, "MFA_RECOVERY")
            .set(IAM_IDENTITY_LIFECYCLE_OUTBOX.IDEMPOTENCY_KEY, idempotencyKey)
            .execute();
        tx.insertInto(IAM_MFA_RECOVERY)
            .set(IAM_MFA_RECOVERY.ID, recoveryId)
            .set(IAM_MFA_RECOVERY.USER_ID, targetUserId)
            .set(IAM_MFA_RECOVERY.TENANT_ID, actor.tenantId())
            .set(IAM_MFA_RECOVERY.TARGET_MEMBERSHIP_ID, targetMembershipId)
            .set(IAM_MFA_RECOVERY.REQUESTED_BY_MEMBERSHIP_ID, actor.membershipId())
            .set(IAM_MFA_RECOVERY.ACCOUNT_DOMAIN, domain)
            .set(IAM_MFA_RECOVERY.IDEMPOTENCY_KEY, idempotencyKey)
            .set(IAM_MFA_RECOVERY.LIFECYCLE_EVENT_RECORD_ID, eventId)
            .execute();
        tx.update(IAM_USER)
            .set(IAM_USER.IDP_PROVISIONING_STATUS, PENDING)
            .set(IAM_USER.IDENTITY_VERSION, IAM_USER.IDENTITY_VERSION.plus(1L))
            .set(IAM_USER.ROW_VERSION, IAM_USER.ROW_VERSION.plus(1L))
            .set(IAM_USER.UPDATED_AT, now())
            .where(IAM_USER.ID.eq(targetUserId).and(IAM_USER.ACCOUNT_DOMAIN.eq(domain)))
            .execute();
        tx.update(IAM_AUTHENTICATION_CREDENTIAL)
            .set(IAM_AUTHENTICATION_CREDENTIAL.STATUS, DISABLED)
            .set(IAM_AUTHENTICATION_CREDENTIAL.ROW_VERSION,
                IAM_AUTHENTICATION_CREDENTIAL.ROW_VERSION.plus(1L))
            .set(IAM_AUTHENTICATION_CREDENTIAL.UPDATED_AT, now())
            .where(IAM_AUTHENTICATION_CREDENTIAL.USER_ID.eq(targetUserId)
                .and(IAM_AUTHENTICATION_CREDENTIAL.ACCOUNT_DOMAIN.eq(domain)))
            .execute();
        advanceMembershipSessions(tx, domain, targetUserId);
        audit(tx, actor.tenantId(), actor.membershipId(), targetUserId,
            "MFA_RECOVERY_REQUEST");
        return new RecoveryRequest(recoveryId, Status.RECOVERY_PENDING);
    }

    @Override
    public Optional<MfaRecoveryTask> claimNext(AccountDomain accountDomain, Instant now,
                                               Duration leaseDuration) {
        Objects.requireNonNull(accountDomain, "accountDomain");
        Objects.requireNonNull(now, "now");
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("MFA recovery lease must be positive");
        }
        return dsl.transactionResult(configuration -> {
            DSLContext tx = configuration.dsl();
            OffsetDateTime current = at(now);
            var row = tx.select(IAM_MFA_RECOVERY.ID,
                    IAM_MFA_RECOVERY.LIFECYCLE_EVENT_RECORD_ID, IAM_MFA_RECOVERY.USER_ID,
                    IAM_MFA_RECOVERY.TENANT_ID, IAM_MFA_RECOVERY.TARGET_MEMBERSHIP_ID,
                    IAM_MFA_RECOVERY.MFA_CREDENTIALS_REVOKED_AT,
                    IAM_MFA_RECOVERY.RECOVERY_CODES_REVOKED_AT,
                    IAM_MFA_RECOVERY.KEYCLOAK_SESSIONS_REVOKED_AT,
                    IAM_MFA_RECOVERY.APPLICATION_SESSIONS_REVOKED_AT,
                    IAM_MFA_RECOVERY.ATTEMPTS,
                    IAM_USER.IDP_ISSUER, IAM_USER.IDP_SUBJECT)
                .from(IAM_MFA_RECOVERY)
                .join(IAM_USER).on(IAM_USER.ID.eq(IAM_MFA_RECOVERY.USER_ID)
                    .and(IAM_USER.ACCOUNT_DOMAIN.eq(IAM_MFA_RECOVERY.ACCOUNT_DOMAIN)))
                .where(IAM_MFA_RECOVERY.ACCOUNT_DOMAIN.eq(accountDomain.name())
                    .and(IAM_MFA_RECOVERY.STATUS.eq(PENDING))
                    .and(IAM_MFA_RECOVERY.AVAILABLE_AT.le(current))
                    .and(IAM_MFA_RECOVERY.LEASE_UNTIL.isNull()
                        .or(IAM_MFA_RECOVERY.LEASE_UNTIL.le(current))))
                .orderBy(IAM_MFA_RECOVERY.ID)
                .limit(1).forUpdate().of(IAM_MFA_RECOVERY).skipLocked().fetchOne();
            if (row == null) {
                return Optional.empty();
            }
            long recoveryId = row.get(IAM_MFA_RECOVERY.ID);
            tx.update(IAM_MFA_RECOVERY)
                .set(IAM_MFA_RECOVERY.LEASE_UNTIL, at(now.plus(leaseDuration)))
                .set(IAM_MFA_RECOVERY.ATTEMPTS, IAM_MFA_RECOVERY.ATTEMPTS.plus(1))
                .set(IAM_MFA_RECOVERY.LAST_ERROR_CODE, (String) null)
                .set(IAM_MFA_RECOVERY.UPDATED_AT, current)
                .set(IAM_MFA_RECOVERY.ROW_VERSION, IAM_MFA_RECOVERY.ROW_VERSION.plus(1L))
                .where(IAM_MFA_RECOVERY.ID.eq(recoveryId)).execute();
            tx.update(IAM_IDENTITY_LIFECYCLE_RELAY_STATE)
                .set(IAM_IDENTITY_LIFECYCLE_RELAY_STATE.STATUS, "PUBLISHING")
                .set(IAM_IDENTITY_LIFECYCLE_RELAY_STATE.ATTEMPTS,
                    IAM_IDENTITY_LIFECYCLE_RELAY_STATE.ATTEMPTS.plus(1))
                .set(IAM_IDENTITY_LIFECYCLE_RELAY_STATE.LEASE_UNTIL, at(now.plus(leaseDuration)))
                .set(IAM_IDENTITY_LIFECYCLE_RELAY_STATE.LAST_ERROR_CODE, (String) null)
                .set(IAM_IDENTITY_LIFECYCLE_RELAY_STATE.UPDATED_AT, current)
                .where(IAM_IDENTITY_LIFECYCLE_RELAY_STATE.EVENT_RECORD_ID.eq(
                    row.get(IAM_MFA_RECOVERY.LIFECYCLE_EVENT_RECORD_ID))).execute();
            long userId = row.get(IAM_MFA_RECOVERY.USER_ID);
            List<Long> memberships = tx.select(IAM_MEMBERSHIP.ID).from(IAM_MEMBERSHIP)
                .where(IAM_MEMBERSHIP.USER_ID.eq(userId)
                    .and(IAM_MEMBERSHIP.ACCOUNT_DOMAIN.eq(accountDomain.name()))
                    .and(IAM_MEMBERSHIP.STATUS.ne("TERMINATED")))
                .orderBy(IAM_MEMBERSHIP.ID).fetch(IAM_MEMBERSHIP.ID);
            return Optional.of(new MfaRecoveryTask(recoveryId,
                row.get(IAM_MFA_RECOVERY.LIFECYCLE_EVENT_RECORD_ID), userId,
                row.get(IAM_MFA_RECOVERY.TENANT_ID), row.get(IAM_MFA_RECOVERY.TARGET_MEMBERSHIP_ID),
                accountDomain, row.get(IAM_USER.IDP_ISSUER), row.get(IAM_USER.IDP_SUBJECT),
                memberships, row.get(IAM_MFA_RECOVERY.ATTEMPTS) + 1, nextStep(row)));
        });
    }

    @Override
    public void completeStep(long recoveryId, MfaRecoveryStep step, Instant completedAt) {
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(completedAt, "completedAt");
        dsl.transaction(configuration -> completeStep(configuration.dsl(), recoveryId, step, completedAt));
    }

    private void completeStep(DSLContext tx, long recoveryId, MfaRecoveryStep step, Instant completedAt) {
        var row = tx.selectFrom(IAM_MFA_RECOVERY).where(IAM_MFA_RECOVERY.ID.eq(recoveryId))
            .forUpdate().fetchOne();
        if (row == null || !PENDING.equals(row.getStatus())) {
            throw new IllegalStateException("MFA recovery is not pending");
        }
        TableField<?, OffsetDateTime> field = timestampField(step);
        if (row.get(field) != null) {
            return;
        }
        if (nextStep(row) != step) {
            throw new IllegalStateException("MFA recovery step is out of order");
        }
        OffsetDateTime completed = at(completedAt);
        boolean finalStep = step == MfaRecoveryStep.APPLICATION_SESSIONS;
        var update = tx.update(IAM_MFA_RECOVERY)
            .set(field, completed)
            .set(IAM_MFA_RECOVERY.LEASE_UNTIL, (OffsetDateTime) null)
            .set(IAM_MFA_RECOVERY.LAST_ERROR_CODE, (String) null)
            .set(IAM_MFA_RECOVERY.AVAILABLE_AT, completed)
            .set(IAM_MFA_RECOVERY.UPDATED_AT, completed)
            .set(IAM_MFA_RECOVERY.ROW_VERSION, IAM_MFA_RECOVERY.ROW_VERSION.plus(1L));
        if (finalStep) {
            update.set(IAM_MFA_RECOVERY.STATUS, COMPLETED)
                .set(IAM_MFA_RECOVERY.COMPLETED_AT, completed);
        }
        update.where(IAM_MFA_RECOVERY.ID.eq(recoveryId)).execute();
        if (!finalStep) {
            return;
        }

        int activated = tx.update(IAM_USER)
            .set(IAM_USER.IDP_PROVISIONING_STATUS, "PROVISIONED")
            .set(IAM_USER.IDENTITY_VERSION, IAM_USER.IDENTITY_VERSION.plus(1L))
            .set(IAM_USER.ROW_VERSION, IAM_USER.ROW_VERSION.plus(1L))
            .set(IAM_USER.UPDATED_AT, completed)
            .where(IAM_USER.ID.eq(row.getUserId())
                .and(IAM_USER.ACCOUNT_DOMAIN.eq(row.getAccountDomain()))
                .and(IAM_USER.STATUS.eq(ACTIVE))
                .and(IAM_USER.IDP_PROVISIONING_STATUS.eq(PENDING)))
            .execute();
        if (activated != 1) {
            throw new IllegalStateException("Recovered identity is no longer eligible for activation");
        }
        tx.update(IAM_AUTHENTICATION_CREDENTIAL)
            .set(IAM_AUTHENTICATION_CREDENTIAL.STATUS, ACTIVE)
            .set(IAM_AUTHENTICATION_CREDENTIAL.ROW_VERSION,
                IAM_AUTHENTICATION_CREDENTIAL.ROW_VERSION.plus(1L))
            .set(IAM_AUTHENTICATION_CREDENTIAL.UPDATED_AT, completed)
            .where(IAM_AUTHENTICATION_CREDENTIAL.USER_ID.eq(row.getUserId())
                .and(IAM_AUTHENTICATION_CREDENTIAL.ACCOUNT_DOMAIN.eq(row.getAccountDomain()))
                .and(IAM_AUTHENTICATION_CREDENTIAL.STATUS.eq(DISABLED)))
            .execute();
        advanceMembershipSessions(tx, row.getAccountDomain(), row.getUserId());
        tx.update(IAM_IDENTITY_LIFECYCLE_RELAY_STATE)
            .set(IAM_IDENTITY_LIFECYCLE_RELAY_STATE.STATUS, "PUBLISHED")
            .set(IAM_IDENTITY_LIFECYCLE_RELAY_STATE.PUBLISHED_AT, completed)
            .set(IAM_IDENTITY_LIFECYCLE_RELAY_STATE.LEASE_UNTIL, (OffsetDateTime) null)
            .set(IAM_IDENTITY_LIFECYCLE_RELAY_STATE.LAST_ERROR_CODE, (String) null)
            .set(IAM_IDENTITY_LIFECYCLE_RELAY_STATE.UPDATED_AT, completed)
            .where(IAM_IDENTITY_LIFECYCLE_RELAY_STATE.EVENT_RECORD_ID.eq(
                row.getLifecycleEventRecordId())).execute();
        audit(tx, row.getTenantId(), row.getRequestedByMembershipId(), row.getUserId(),
            "MFA_RECOVERY_COMPLETE");
    }

    @Override
    public void reschedule(long recoveryId, Instant availableAt, String errorCode) {
        Objects.requireNonNull(availableAt, "availableAt");
        String boundedCode = boundedErrorCode(errorCode);
        dsl.transaction(configuration -> {
            DSLContext tx = configuration.dsl();
            Long eventId = tx.select(IAM_MFA_RECOVERY.LIFECYCLE_EVENT_RECORD_ID)
                .from(IAM_MFA_RECOVERY)
                .where(IAM_MFA_RECOVERY.ID.eq(recoveryId).and(IAM_MFA_RECOVERY.STATUS.eq(PENDING)))
                .forUpdate().fetchOne(IAM_MFA_RECOVERY.LIFECYCLE_EVENT_RECORD_ID);
            if (eventId == null) {
                return;
            }
            OffsetDateTime available = at(availableAt);
            tx.update(IAM_MFA_RECOVERY)
                .set(IAM_MFA_RECOVERY.AVAILABLE_AT, available)
                .set(IAM_MFA_RECOVERY.LEASE_UNTIL, (OffsetDateTime) null)
                .set(IAM_MFA_RECOVERY.LAST_ERROR_CODE, boundedCode)
                .set(IAM_MFA_RECOVERY.UPDATED_AT, now())
                .set(IAM_MFA_RECOVERY.ROW_VERSION, IAM_MFA_RECOVERY.ROW_VERSION.plus(1L))
                .where(IAM_MFA_RECOVERY.ID.eq(recoveryId)).execute();
            tx.update(IAM_IDENTITY_LIFECYCLE_RELAY_STATE)
                .set(IAM_IDENTITY_LIFECYCLE_RELAY_STATE.STATUS, "FAILED")
                .set(IAM_IDENTITY_LIFECYCLE_RELAY_STATE.AVAILABLE_AT, available)
                .set(IAM_IDENTITY_LIFECYCLE_RELAY_STATE.LEASE_UNTIL, (OffsetDateTime) null)
                .set(IAM_IDENTITY_LIFECYCLE_RELAY_STATE.LAST_ERROR_CODE, boundedCode)
                .set(IAM_IDENTITY_LIFECYCLE_RELAY_STATE.UPDATED_AT, now())
                .where(IAM_IDENTITY_LIFECYCLE_RELAY_STATE.EVENT_RECORD_ID.eq(eventId)).execute();
        });
    }

    private static MfaRecoveryStep nextStep(Record row) {
        if (row.get(IAM_MFA_RECOVERY.MFA_CREDENTIALS_REVOKED_AT) == null) {
            return MfaRecoveryStep.MFA_CREDENTIALS;
        }
        if (row.get(IAM_MFA_RECOVERY.RECOVERY_CODES_REVOKED_AT) == null) {
            return MfaRecoveryStep.RECOVERY_CODES;
        }
        if (row.get(IAM_MFA_RECOVERY.KEYCLOAK_SESSIONS_REVOKED_AT) == null) {
            return MfaRecoveryStep.KEYCLOAK_SESSIONS;
        }
        return MfaRecoveryStep.APPLICATION_SESSIONS;
    }

    private static TableField<?, OffsetDateTime> timestampField(MfaRecoveryStep step) {
        return switch (step) {
            case MFA_CREDENTIALS -> IAM_MFA_RECOVERY.MFA_CREDENTIALS_REVOKED_AT;
            case RECOVERY_CODES -> IAM_MFA_RECOVERY.RECOVERY_CODES_REVOKED_AT;
            case KEYCLOAK_SESSIONS -> IAM_MFA_RECOVERY.KEYCLOAK_SESSIONS_REVOKED_AT;
            case APPLICATION_SESSIONS -> IAM_MFA_RECOVERY.APPLICATION_SESSIONS_REVOKED_AT;
        };
    }

    private static void advanceMembershipSessions(DSLContext tx, String domain, long userId) {
        tx.update(IAM_MEMBERSHIP)
            .set(IAM_MEMBERSHIP.SESSION_VERSION, IAM_MEMBERSHIP.SESSION_VERSION.plus(1L))
            .set(IAM_MEMBERSHIP.ROW_VERSION, IAM_MEMBERSHIP.ROW_VERSION.plus(1L))
            .set(IAM_MEMBERSHIP.UPDATED_AT, now())
            .where(IAM_MEMBERSHIP.USER_ID.eq(userId)
                .and(IAM_MEMBERSHIP.ACCOUNT_DOMAIN.eq(domain))
                .and(IAM_MEMBERSHIP.STATUS.ne("TERMINATED")))
            .execute();
    }

    private void audit(DSLContext tx, long tenantId, long actorMembershipId, long userId,
                       String action) {
        tx.insertInto(IAM_AUDIT_EVENT)
            .set(IAM_AUDIT_EVENT.ID, nextId(tx))
            .set(IAM_AUDIT_EVENT.TENANT_ID, tenantId)
            .set(IAM_AUDIT_EVENT.OPERATOR_MEMBERSHIP_ID, actorMembershipId)
            .set(IAM_AUDIT_EVENT.TARGET_TYPE, "USER")
            .set(IAM_AUDIT_EVENT.TARGET_REF, Long.toString(userId))
            .set(IAM_AUDIT_EVENT.ACTION_CODE, action)
            .set(IAM_AUDIT_EVENT.DECISION, "ALLOW")
            .set(IAM_AUDIT_EVENT.REASON_CODE, "SYSTEM_ADMIN_STEP_UP")
            .set(IAM_AUDIT_EVENT.AFTER_VALUE, EMPTY_JSON)
            .set(IAM_AUDIT_EVENT.TRACE_ID, traceIdSupplier.get())
            .execute();
    }

    private static long nextId(DSLContext tx) {
        var next = IAM_ID_SEQ.nextval();
        Long id = tx.select(next).fetchOne(next);
        if (id == null) {
            throw new IllegalStateException("IAM sequence did not return an identifier");
        }
        return id;
    }

    private static OffsetDateTime now() {
        return OffsetDateTime.now(ZoneOffset.UTC);
    }

    private static OffsetDateTime at(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static String boundedErrorCode(String value) {
        if (value == null || !value.matches("[A-Z][A-Z0-9_]{0,63}")) {
            return "UNCLASSIFIED_FAILURE";
        }
        return value;
    }
}
