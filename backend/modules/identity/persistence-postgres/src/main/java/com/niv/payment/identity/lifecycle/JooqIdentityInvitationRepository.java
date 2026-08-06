package com.niv.payment.identity.lifecycle;

import com.niv.payment.permission.domain.AccountDomain;
import com.niv.payment.permission.domain.AuthorizationSubject;
import org.jooq.DSLContext;
import org.jooq.JSONB;
import org.jooq.Record;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import static com.niv.payment.permission.persistence.jooq.generated.Sequences.IAM_ID_SEQ;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_AUDIT_EVENT;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_AUTHENTICATION_CREDENTIAL;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_GRANT_DIMENSION;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_IDENTITY_INVITATION;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_IDENTITY_INVITATION_ROLE;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_IDENTITY_LIFECYCLE_OUTBOX;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_IDENTITY_LIFECYCLE_RELAY_STATE;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_MEMBERSHIP;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_MEMBERSHIP_ROLE;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_PERMISSION;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_ROLE;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_ROLE_GRANT;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_TENANT;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_TENANT_ENTRY_HOST;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_USER;

public final class JooqIdentityInvitationRepository implements IdentityInvitationRepository,
    TenantBootstrapRepository, IdentityInvitationRelayRepository, IdentityGovernanceRepository {
    private static final String ACTIVE = "ACTIVE";
    private static final String DISABLED = "DISABLED";
    private static final String RESERVED = "RESERVED";
    private static final String PROVISION_PENDING = "PROVISION_PENDING";
    private static final String COMPLETED = "COMPLETED";
    private static final String MEMBER = "MEMBER";
    private static final String TENANT_FIRST_ADMIN = "TENANT_FIRST_ADMIN";
    private static final JSONB EMPTY_JSON = JSONB.valueOf("{}");

    private final DSLContext dsl;
    private final Supplier<String> traceIdSupplier;

    public JooqIdentityInvitationRepository(DSLContext dsl, Supplier<String> traceIdSupplier) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
        this.traceIdSupplier = Objects.requireNonNull(traceIdSupplier, "traceIdSupplier");
    }

    @Override
    public Reservation reserveMember(AccountDomain accountDomain, AuthorizationSubject actor,
                                     MemberInvitationCommand command) {
        Objects.requireNonNull(accountDomain, "accountDomain");
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(command, "command");
        return dsl.transactionResult(configuration -> {
            DSLContext tx = configuration.dsl();
            lockIdempotency(tx, command.idempotencyKey());
            requireSystemAdministrator(tx, accountDomain, actor);
            var existing = invitationByIdempotency(tx, command.idempotencyKey());
            if (existing != null) {
                requireExistingMemberRequest(tx, existing, accountDomain, actor, command);
                return reservation(existing);
            }
            requireOrdinaryRoles(tx, actor.tenantId(), command.roleIds());
            long invitationId = nextId(tx);
            tx.insertInto(IAM_IDENTITY_INVITATION)
                .set(IAM_IDENTITY_INVITATION.ID, invitationId)
                .set(IAM_IDENTITY_INVITATION.INVITATION_KIND, MEMBER)
                .set(IAM_IDENTITY_INVITATION.TENANT_ID, actor.tenantId())
                .set(IAM_IDENTITY_INVITATION.ACCOUNT_DOMAIN, accountDomain.name())
                .set(IAM_IDENTITY_INVITATION.REQUESTED_BY_TENANT_ID, actor.tenantId())
                .set(IAM_IDENTITY_INVITATION.REQUESTED_BY_MEMBERSHIP_ID, actor.membershipId())
                .set(IAM_IDENTITY_INVITATION.IDEMPOTENCY_KEY, command.idempotencyKey())
                .set(IAM_IDENTITY_INVITATION.DISPLAY_NAME, command.displayName())
                .execute();
            command.roleIds().forEach(roleId -> tx.insertInto(IAM_IDENTITY_INVITATION_ROLE)
                .set(IAM_IDENTITY_INVITATION_ROLE.INVITATION_ID, invitationId)
                .set(IAM_IDENTITY_INVITATION_ROLE.TENANT_ID, actor.tenantId())
                .set(IAM_IDENTITY_INVITATION_ROLE.ROLE_ID, roleId)
                .execute());
            audit(tx, actor.tenantId(), actor.membershipId(), "IDENTITY_INVITATION",
                Long.toString(invitationId), "RESERVE_MEMBER_INVITATION", "SYSTEM_ADMIN_STEP_UP");
            return new Reservation(invitationId, actor.tenantId(), accountDomain,
                command.idempotencyKey(), command.displayName(), Status.RESERVED, null);
        });
    }

    @Override
    public TenantReservation reserve(AuthorizationSubject actor, AccountDomain targetDomain,
                                     TenantBootstrapCommand command) {
        Objects.requireNonNull(actor, "actor");
        Objects.requireNonNull(targetDomain, "targetDomain");
        Objects.requireNonNull(command, "command");
        if (targetDomain == AccountDomain.PLATFORM
            || command.tenantType().accountDomain() != targetDomain) {
            throw new IllegalArgumentException("Tenant type does not match the target account domain");
        }
        return dsl.transactionResult(configuration -> {
            DSLContext tx = configuration.dsl();
            lockIdempotency(tx, command.idempotencyKey());
            requireSystemAdministrator(tx, AccountDomain.PLATFORM, actor);
            var existing = invitationByIdempotency(tx, command.idempotencyKey());
            if (existing != null) {
                requireExistingBootstrapRequest(tx, existing, actor, targetDomain, command);
                return new TenantReservation(existing.getTenantId(), reservation(existing));
            }

            long tenantId = nextId(tx);
            long systemRoleId = nextId(tx);
            long memberRoleId = nextId(tx);
            long invitationId = nextId(tx);
            tx.insertInto(IAM_TENANT)
                .set(IAM_TENANT.ID, tenantId)
                .set(IAM_TENANT.TENANT_CODE, command.tenantCode())
                .set(IAM_TENANT.TENANT_NAME, command.tenantName())
                .set(IAM_TENANT.TENANT_TYPE, command.tenantType().name())
                .set(IAM_TENANT.STATUS, DISABLED)
                .set(IAM_TENANT.ACCOUNT_DOMAIN, targetDomain.name())
                .execute();
            tx.insertInto(IAM_TENANT_ENTRY_HOST)
                .set(IAM_TENANT_ENTRY_HOST.ID, nextId(tx))
                .set(IAM_TENANT_ENTRY_HOST.ENTRY_HOST, command.entryHost())
                .set(IAM_TENANT_ENTRY_HOST.ACCOUNT_DOMAIN, targetDomain.name())
                .set(IAM_TENANT_ENTRY_HOST.TENANT_ID, tenantId)
                .set(IAM_TENANT_ENTRY_HOST.STATUS, DISABLED)
                .execute();
            insertBootstrapRole(tx, tenantId, systemRoleId, "tenant-system-administrator",
                "Tenant Administrator", command.tenantType(), false, true);
            insertBootstrapRole(tx, tenantId, memberRoleId, "tenant-member", "Tenant Member",
                command.tenantType(), true, false);
            addPortalGrant(tx, targetDomain, tenantId, systemRoleId);
            addPortalGrant(tx, targetDomain, tenantId, memberRoleId);
            tx.insertInto(IAM_IDENTITY_INVITATION)
                .set(IAM_IDENTITY_INVITATION.ID, invitationId)
                .set(IAM_IDENTITY_INVITATION.INVITATION_KIND, TENANT_FIRST_ADMIN)
                .set(IAM_IDENTITY_INVITATION.TENANT_ID, tenantId)
                .set(IAM_IDENTITY_INVITATION.ACCOUNT_DOMAIN, targetDomain.name())
                .set(IAM_IDENTITY_INVITATION.REQUESTED_BY_TENANT_ID, actor.tenantId())
                .set(IAM_IDENTITY_INVITATION.REQUESTED_BY_MEMBERSHIP_ID, actor.membershipId())
                .set(IAM_IDENTITY_INVITATION.IDEMPOTENCY_KEY, command.idempotencyKey())
                .set(IAM_IDENTITY_INVITATION.DISPLAY_NAME, command.firstAdministratorDisplayName())
                .execute();
            tx.insertInto(IAM_IDENTITY_INVITATION_ROLE)
                .set(IAM_IDENTITY_INVITATION_ROLE.INVITATION_ID, invitationId)
                .set(IAM_IDENTITY_INVITATION_ROLE.TENANT_ID, tenantId)
                .set(IAM_IDENTITY_INVITATION_ROLE.ROLE_ID, systemRoleId)
                .execute();
            audit(tx, actor.tenantId(), actor.membershipId(), "TENANT", Long.toString(tenantId),
                "RESERVE_TENANT_FIRST_ADMIN", "PLATFORM_SYSTEM_ADMIN_STEP_UP");
            return new TenantReservation(tenantId, new Reservation(invitationId, tenantId,
                targetDomain, command.idempotencyKey(), command.firstAdministratorDisplayName(),
                Status.RESERVED, null));
        });
    }

    @Override
    public Invitation attachIdentity(Reservation reservation, FederatedIdentity identity) {
        Objects.requireNonNull(reservation, "reservation");
        Objects.requireNonNull(identity, "identity");
        return dsl.transactionResult(configuration -> attachIdentity(
            configuration.dsl(), reservation, identity));
    }

    @Override
    public TenantBootstrap attachIdentity(TenantReservation reservation, FederatedIdentity identity) {
        Objects.requireNonNull(reservation, "reservation");
        Invitation invitation = attachIdentity(reservation.invitation(), identity);
        return new TenantBootstrap(reservation.tenantId(), invitation.invitationId(),
            invitation.membershipId(), invitation.status());
    }

    @Override
    public MemberPage members(AccountDomain accountDomain, AuthorizationSubject actor,
                              int page, int pageSize) {
        Objects.requireNonNull(accountDomain, "accountDomain");
        Objects.requireNonNull(actor, "actor");
        return dsl.transactionResult(configuration -> {
            DSLContext tx = configuration.dsl();
            requireSystemAdministratorRead(tx, accountDomain, actor);
            long total = tx.fetchCount(tx.selectOne().from(IAM_MEMBERSHIP)
                .where(IAM_MEMBERSHIP.TENANT_ID.eq(actor.tenantId())
                    .and(IAM_MEMBERSHIP.ACCOUNT_DOMAIN.eq(accountDomain.name()))
                    .and(IAM_MEMBERSHIP.STATUS.ne("TERMINATED"))));
            var systemAdministrator = org.jooq.impl.DSL.exists(
                org.jooq.impl.DSL.selectOne().from(IAM_MEMBERSHIP_ROLE)
                    .join(IAM_ROLE).on(IAM_ROLE.ID.eq(IAM_MEMBERSHIP_ROLE.ROLE_ID)
                        .and(IAM_ROLE.TENANT_ID.eq(IAM_MEMBERSHIP_ROLE.TENANT_ID)))
                    .where(IAM_MEMBERSHIP_ROLE.TENANT_ID.eq(IAM_MEMBERSHIP.TENANT_ID)
                        .and(IAM_MEMBERSHIP_ROLE.MEMBERSHIP_ID.eq(IAM_MEMBERSHIP.ID))
                        .and(IAM_ROLE.SYSTEM_ROLE.isTrue()).and(IAM_ROLE.STATUS.eq(ACTIVE))
                        .and(IAM_ROLE.DELETED_AT.isNull())))
                .as("system_administrator");
            List<Member> items = tx.select(IAM_MEMBERSHIP.ID, IAM_USER.DISPLAY_NAME,
                    IAM_MEMBERSHIP.STATUS, IAM_USER.STATUS,
                    IAM_USER.IDP_PROVISIONING_STATUS, systemAdministrator)
                .from(IAM_MEMBERSHIP)
                .join(IAM_USER).on(IAM_USER.ID.eq(IAM_MEMBERSHIP.USER_ID)
                    .and(IAM_USER.ACCOUNT_DOMAIN.eq(IAM_MEMBERSHIP.ACCOUNT_DOMAIN)))
                .where(IAM_MEMBERSHIP.TENANT_ID.eq(actor.tenantId())
                    .and(IAM_MEMBERSHIP.ACCOUNT_DOMAIN.eq(accountDomain.name()))
                    .and(IAM_MEMBERSHIP.STATUS.ne("TERMINATED")))
                .orderBy(IAM_MEMBERSHIP.ID)
                .limit(pageSize).offset((page - 1) * pageSize)
                .fetch(row -> new Member(row.get(IAM_MEMBERSHIP.ID),
                    row.get(IAM_USER.DISPLAY_NAME), row.get(IAM_MEMBERSHIP.STATUS),
                    row.get(IAM_USER.STATUS), row.get(IAM_USER.IDP_PROVISIONING_STATUS),
                    Boolean.TRUE.equals(row.get(systemAdministrator)),
                    row.get(IAM_MEMBERSHIP.ID) == actor.membershipId()));
            return new MemberPage(items, total);
        });
    }

    @Override
    public List<InvitationRole> invitationRoles(AccountDomain accountDomain,
                                                AuthorizationSubject actor) {
        Objects.requireNonNull(accountDomain, "accountDomain");
        Objects.requireNonNull(actor, "actor");
        return dsl.transactionResult(configuration -> {
            DSLContext tx = configuration.dsl();
            requireSystemAdministratorRead(tx, accountDomain, actor);
            return tx.select(IAM_ROLE.ID, IAM_ROLE.ROLE_NAME).from(IAM_ROLE)
                .where(IAM_ROLE.TENANT_ID.eq(actor.tenantId())
                    .and(IAM_ROLE.STATUS.eq(ACTIVE)).and(IAM_ROLE.ASSIGNABLE.isTrue())
                    .and(IAM_ROLE.SYSTEM_ROLE.isFalse()).and(IAM_ROLE.DELETED_AT.isNull()))
                .orderBy(IAM_ROLE.ROLE_NAME, IAM_ROLE.ID).limit(200)
                .fetch(row -> new InvitationRole(row.get(IAM_ROLE.ID), row.get(IAM_ROLE.ROLE_NAME)));
        });
    }

    private Invitation attachIdentity(DSLContext tx, Reservation reservation,
                                      FederatedIdentity identity) {
        var invitation = tx.selectFrom(IAM_IDENTITY_INVITATION)
            .where(IAM_IDENTITY_INVITATION.ID.eq(reservation.invitationId()))
            .forUpdate().fetchOne();
        if (invitation == null || invitation.getTenantId() != reservation.tenantId()
            || !invitation.getAccountDomain().equals(reservation.accountDomain().name())
            || !invitation.getIdempotencyKey().equals(reservation.idempotencyKey())
            || !invitation.getDisplayName().equals(reservation.displayName())) {
            throw new IllegalArgumentException("Invitation reservation does not match persisted state");
        }
        requireRealm(identity.issuer(), reservation.accountDomain());
        if (!RESERVED.equals(invitation.getStatus())) {
            return requireAttachedIdentity(tx, invitation, identity);
        }

        String domain = reservation.accountDomain().name();
        var existingUser = tx.selectFrom(IAM_USER)
            .where(IAM_USER.IDP_ISSUER.eq(identity.issuer())
                .and(IAM_USER.IDP_SUBJECT.eq(identity.subject())))
            .forUpdate().fetchOne();
        long userId;
        if (existingUser == null) {
            userId = nextId(tx);
            boolean existingIdentity = identity.mode() == FederatedIdentity.Mode.EXISTING_ACTIVE;
            tx.insertInto(IAM_USER)
                .set(IAM_USER.ID, userId)
                .set(IAM_USER.IDP_ISSUER, identity.issuer())
                .set(IAM_USER.IDP_SUBJECT, identity.subject())
                .set(IAM_USER.DISPLAY_NAME, invitation.getDisplayName())
                .set(IAM_USER.STATUS, existingIdentity ? ACTIVE : "PENDING_ACTIVATION")
                .set(IAM_USER.ACCOUNT_DOMAIN, domain)
                .set(IAM_USER.IDP_PROVISIONING_STATUS,
                    existingIdentity ? "PROVISIONED" : PROVISION_PENDING)
                .execute();
            tx.insertInto(IAM_AUTHENTICATION_CREDENTIAL)
                .set(IAM_AUTHENTICATION_CREDENTIAL.USER_ID, userId)
                .set(IAM_AUTHENTICATION_CREDENTIAL.USERNAME, localUsername(identity))
                .set(IAM_AUTHENTICATION_CREDENTIAL.STATUS,
                    existingIdentity ? ACTIVE : DISABLED)
                .set(IAM_AUTHENTICATION_CREDENTIAL.ACCOUNT_DOMAIN, domain)
                .execute();
        } else {
            userId = existingUser.getId();
            if (identity.mode() != FederatedIdentity.Mode.EXISTING_ACTIVE
                || !domain.equals(existingUser.getAccountDomain())
                || !ACTIVE.equals(existingUser.getStatus())
                || !"PROVISIONED".equals(existingUser.getIdpProvisioningStatus())
                || !activeCredentialExists(tx, domain, userId)) {
                throw new IllegalStateException("Existing application identity is not eligible for invitation");
            }
        }
        if (tx.fetchExists(tx.selectOne().from(IAM_MEMBERSHIP)
            .where(IAM_MEMBERSHIP.TENANT_ID.eq(invitation.getTenantId())
                .and(IAM_MEMBERSHIP.USER_ID.eq(userId))))) {
            throw new IllegalStateException("Identity already has a membership in the target tenant");
        }

        long membershipId = nextId(tx);
        tx.insertInto(IAM_MEMBERSHIP)
            .set(IAM_MEMBERSHIP.ID, membershipId)
            .set(IAM_MEMBERSHIP.TENANT_ID, invitation.getTenantId())
            .set(IAM_MEMBERSHIP.USER_ID, userId)
            .set(IAM_MEMBERSHIP.STATUS, "INVITED")
            .set(IAM_MEMBERSHIP.ACCOUNT_DOMAIN, domain)
            .execute();
        List<Long> roleIds = invitationRoleIds(tx, invitation.getId());
        if (roleIds.isEmpty()) {
            throw new IllegalStateException("Invitation has no frozen role assignment");
        }
        roleIds.forEach(roleId -> tx.insertInto(IAM_MEMBERSHIP_ROLE)
            .set(IAM_MEMBERSHIP_ROLE.TENANT_ID, invitation.getTenantId())
            .set(IAM_MEMBERSHIP_ROLE.MEMBERSHIP_ID, membershipId)
            .set(IAM_MEMBERSHIP_ROLE.ROLE_ID, roleId)
            .set(IAM_MEMBERSHIP_ROLE.ASSIGNED_BY,
                invitation.getRequestedByTenantId().equals(invitation.getTenantId())
                    ? invitation.getRequestedByMembershipId() : null)
            .execute());

        long eventId = nextId(tx);
        tx.insertInto(IAM_IDENTITY_LIFECYCLE_OUTBOX)
            .set(IAM_IDENTITY_LIFECYCLE_OUTBOX.ID, eventId)
            .set(IAM_IDENTITY_LIFECYCLE_OUTBOX.USER_ID, userId)
            .set(IAM_IDENTITY_LIFECYCLE_OUTBOX.TENANT_ID, invitation.getTenantId())
            .set(IAM_IDENTITY_LIFECYCLE_OUTBOX.REALM, domain)
            .set(IAM_IDENTITY_LIFECYCLE_OUTBOX.OPERATION_TYPE, "PROVISION")
            .set(IAM_IDENTITY_LIFECYCLE_OUTBOX.IDEMPOTENCY_KEY, invitation.getIdempotencyKey())
            .execute();
        OffsetDateTime now = now();
        tx.update(IAM_IDENTITY_INVITATION)
            .set(IAM_IDENTITY_INVITATION.USER_ID, userId)
            .set(IAM_IDENTITY_INVITATION.MEMBERSHIP_ID, membershipId)
            .set(IAM_IDENTITY_INVITATION.LIFECYCLE_EVENT_RECORD_ID, eventId)
            .set(IAM_IDENTITY_INVITATION.IDENTITY_MODE, identity.mode().name())
            .set(IAM_IDENTITY_INVITATION.STATUS, PROVISION_PENDING)
            .set(IAM_IDENTITY_INVITATION.KEYCLOAK_USER_CREATED_AT, now)
            .set(IAM_IDENTITY_INVITATION.AVAILABLE_AT, now)
            .set(IAM_IDENTITY_INVITATION.UPDATED_AT, now)
            .set(IAM_IDENTITY_INVITATION.ROW_VERSION,
                IAM_IDENTITY_INVITATION.ROW_VERSION.plus(1L))
            .where(IAM_IDENTITY_INVITATION.ID.eq(invitation.getId()))
            .execute();
        audit(tx, invitation.getTenantId(), sameTenantActor(invitation), "MEMBERSHIP",
            Long.toString(membershipId), "ATTACH_INVITATION_IDENTITY", "ISSUER_SUBJECT_BOUND");
        return new Invitation(invitation.getId(), membershipId, Status.PROVISION_PENDING);
    }

    @Override
    public Optional<IdentityInvitationTask> claimNext(AccountDomain accountDomain, Instant now,
                                                      Duration leaseDuration) {
        Objects.requireNonNull(accountDomain, "accountDomain");
        Objects.requireNonNull(now, "now");
        if (leaseDuration == null || leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("Invitation lease must be positive");
        }
        return dsl.transactionResult(configuration -> {
            DSLContext tx = configuration.dsl();
            OffsetDateTime current = at(now);
            var row = tx.select(IAM_IDENTITY_INVITATION.ID,
                    IAM_IDENTITY_INVITATION.LIFECYCLE_EVENT_RECORD_ID,
                    IAM_IDENTITY_INVITATION.USER_ID, IAM_IDENTITY_INVITATION.TENANT_ID,
                    IAM_IDENTITY_INVITATION.MEMBERSHIP_ID,
                    IAM_IDENTITY_INVITATION.INVITATION_KIND,
                    IAM_IDENTITY_INVITATION.IDENTITY_MODE,
                    IAM_IDENTITY_INVITATION.KEYCLOAK_USER_ENABLED_AT,
                    IAM_IDENTITY_INVITATION.ACTION_EMAIL_SENT_AT,
                    IAM_IDENTITY_INVITATION.ATTEMPTS,
                    IAM_USER.IDP_ISSUER, IAM_USER.IDP_SUBJECT,
                    IAM_AUTHENTICATION_CREDENTIAL.USERNAME)
                .from(IAM_IDENTITY_INVITATION)
                .join(IAM_USER).on(IAM_USER.ID.eq(IAM_IDENTITY_INVITATION.USER_ID)
                    .and(IAM_USER.ACCOUNT_DOMAIN.eq(IAM_IDENTITY_INVITATION.ACCOUNT_DOMAIN)))
                .join(IAM_AUTHENTICATION_CREDENTIAL)
                    .on(IAM_AUTHENTICATION_CREDENTIAL.USER_ID.eq(IAM_USER.ID)
                        .and(IAM_AUTHENTICATION_CREDENTIAL.ACCOUNT_DOMAIN.eq(
                            IAM_IDENTITY_INVITATION.ACCOUNT_DOMAIN)))
                .where(IAM_IDENTITY_INVITATION.ACCOUNT_DOMAIN.eq(accountDomain.name())
                    .and(IAM_IDENTITY_INVITATION.STATUS.eq(PROVISION_PENDING))
                    .and(IAM_IDENTITY_INVITATION.AVAILABLE_AT.le(current))
                    .and(IAM_IDENTITY_INVITATION.LEASE_UNTIL.isNull()
                        .or(IAM_IDENTITY_INVITATION.LEASE_UNTIL.le(current))))
                .orderBy(IAM_IDENTITY_INVITATION.ID)
                .limit(1).forUpdate().of(IAM_IDENTITY_INVITATION).skipLocked().fetchOne();
            if (row == null) {
                return Optional.empty();
            }
            long invitationId = row.get(IAM_IDENTITY_INVITATION.ID);
            tx.update(IAM_IDENTITY_INVITATION)
                .set(IAM_IDENTITY_INVITATION.LEASE_UNTIL, at(now.plus(leaseDuration)))
                .set(IAM_IDENTITY_INVITATION.ATTEMPTS,
                    IAM_IDENTITY_INVITATION.ATTEMPTS.plus(1))
                .set(IAM_IDENTITY_INVITATION.LAST_ERROR_CODE, (String) null)
                .set(IAM_IDENTITY_INVITATION.UPDATED_AT, current)
                .set(IAM_IDENTITY_INVITATION.ROW_VERSION,
                    IAM_IDENTITY_INVITATION.ROW_VERSION.plus(1L))
                .where(IAM_IDENTITY_INVITATION.ID.eq(invitationId)).execute();
            tx.update(IAM_IDENTITY_LIFECYCLE_RELAY_STATE)
                .set(IAM_IDENTITY_LIFECYCLE_RELAY_STATE.STATUS, "PUBLISHING")
                .set(IAM_IDENTITY_LIFECYCLE_RELAY_STATE.ATTEMPTS,
                    IAM_IDENTITY_LIFECYCLE_RELAY_STATE.ATTEMPTS.plus(1))
                .set(IAM_IDENTITY_LIFECYCLE_RELAY_STATE.LEASE_UNTIL,
                    at(now.plus(leaseDuration)))
                .set(IAM_IDENTITY_LIFECYCLE_RELAY_STATE.LAST_ERROR_CODE, (String) null)
                .set(IAM_IDENTITY_LIFECYCLE_RELAY_STATE.UPDATED_AT, current)
                .where(IAM_IDENTITY_LIFECYCLE_RELAY_STATE.EVENT_RECORD_ID.eq(
                    row.get(IAM_IDENTITY_INVITATION.LIFECYCLE_EVENT_RECORD_ID)))
                .execute();
            FederatedIdentity.Mode mode = FederatedIdentity.Mode.valueOf(
                row.get(IAM_IDENTITY_INVITATION.IDENTITY_MODE));
            return Optional.of(new IdentityInvitationTask(invitationId,
                row.get(IAM_IDENTITY_INVITATION.LIFECYCLE_EVENT_RECORD_ID),
                row.get(IAM_IDENTITY_INVITATION.USER_ID), row.get(IAM_IDENTITY_INVITATION.TENANT_ID),
                row.get(IAM_IDENTITY_INVITATION.MEMBERSHIP_ID), accountDomain,
                row.get(IAM_USER.IDP_ISSUER), row.get(IAM_USER.IDP_SUBJECT),
                row.get(IAM_AUTHENTICATION_CREDENTIAL.USERNAME), mode,
                row.get(IAM_IDENTITY_INVITATION.INVITATION_KIND),
                row.get(IAM_IDENTITY_INVITATION.ATTEMPTS) + 1, nextStep(row, mode)));
        });
    }

    @Override
    public void completeStep(long invitationId, IdentityInvitationStep step, Instant completedAt) {
        Objects.requireNonNull(step, "step");
        Objects.requireNonNull(completedAt, "completedAt");
        dsl.transaction(configuration -> completeStep(
            configuration.dsl(), invitationId, step, completedAt));
    }

    private void completeStep(DSLContext tx, long invitationId, IdentityInvitationStep step,
                              Instant completedAt) {
        var row = tx.selectFrom(IAM_IDENTITY_INVITATION)
            .where(IAM_IDENTITY_INVITATION.ID.eq(invitationId)).forUpdate().fetchOne();
        if (row == null || !PROVISION_PENDING.equals(row.getStatus())) {
            throw new IllegalStateException("Identity invitation is not pending");
        }
        FederatedIdentity.Mode mode = FederatedIdentity.Mode.valueOf(row.getIdentityMode());
        if (nextStep(row, mode) != step) {
            throw new IllegalStateException("Identity invitation step is out of order");
        }
        OffsetDateTime completed = at(completedAt);
        if (step == IdentityInvitationStep.KEYCLOAK_ENABLE) {
            tx.update(IAM_IDENTITY_INVITATION)
                .set(IAM_IDENTITY_INVITATION.KEYCLOAK_USER_ENABLED_AT, completed)
                .set(IAM_IDENTITY_INVITATION.LEASE_UNTIL, (OffsetDateTime) null)
                .set(IAM_IDENTITY_INVITATION.AVAILABLE_AT, completed)
                .set(IAM_IDENTITY_INVITATION.UPDATED_AT, completed)
                .set(IAM_IDENTITY_INVITATION.ROW_VERSION,
                    IAM_IDENTITY_INVITATION.ROW_VERSION.plus(1L))
                .where(IAM_IDENTITY_INVITATION.ID.eq(invitationId)).execute();
            return;
        }
        if (step == IdentityInvitationStep.ACTION_EMAIL) {
            tx.update(IAM_IDENTITY_INVITATION)
                .set(IAM_IDENTITY_INVITATION.ACTION_EMAIL_SENT_AT, completed)
                .set(IAM_IDENTITY_INVITATION.LEASE_UNTIL, (OffsetDateTime) null)
                .set(IAM_IDENTITY_INVITATION.AVAILABLE_AT, completed)
                .set(IAM_IDENTITY_INVITATION.UPDATED_AT, completed)
                .set(IAM_IDENTITY_INVITATION.ROW_VERSION,
                    IAM_IDENTITY_INVITATION.ROW_VERSION.plus(1L))
                .where(IAM_IDENTITY_INVITATION.ID.eq(invitationId)).execute();
            return;
        }

        activateApplicationIdentity(tx, row, mode, completed);
        tx.update(IAM_IDENTITY_INVITATION)
            .set(IAM_IDENTITY_INVITATION.STATUS, COMPLETED)
            .set(IAM_IDENTITY_INVITATION.COMPLETED_AT, completed)
            .set(IAM_IDENTITY_INVITATION.LEASE_UNTIL, (OffsetDateTime) null)
            .set(IAM_IDENTITY_INVITATION.LAST_ERROR_CODE, (String) null)
            .set(IAM_IDENTITY_INVITATION.UPDATED_AT, completed)
            .set(IAM_IDENTITY_INVITATION.ROW_VERSION,
                IAM_IDENTITY_INVITATION.ROW_VERSION.plus(1L))
            .where(IAM_IDENTITY_INVITATION.ID.eq(invitationId)).execute();
        tx.update(IAM_IDENTITY_LIFECYCLE_RELAY_STATE)
            .set(IAM_IDENTITY_LIFECYCLE_RELAY_STATE.STATUS, "PUBLISHED")
            .set(IAM_IDENTITY_LIFECYCLE_RELAY_STATE.PUBLISHED_AT, completed)
            .set(IAM_IDENTITY_LIFECYCLE_RELAY_STATE.LEASE_UNTIL, (OffsetDateTime) null)
            .set(IAM_IDENTITY_LIFECYCLE_RELAY_STATE.LAST_ERROR_CODE, (String) null)
            .set(IAM_IDENTITY_LIFECYCLE_RELAY_STATE.UPDATED_AT, completed)
            .where(IAM_IDENTITY_LIFECYCLE_RELAY_STATE.EVENT_RECORD_ID.eq(
                row.getLifecycleEventRecordId())).execute();
        audit(tx, row.getTenantId(), sameTenantActor(row), "MEMBERSHIP",
            Long.toString(row.getMembershipId()), "COMPLETE_IDENTITY_INVITATION",
            "IDENTITY_LIFECYCLE_PUBLISHED");
    }

    @Override
    public void reschedule(long invitationId, Instant availableAt, String errorCode) {
        Objects.requireNonNull(availableAt, "availableAt");
        String boundedCode = boundedErrorCode(errorCode);
        dsl.transaction(configuration -> {
            DSLContext tx = configuration.dsl();
            Long eventId = tx.select(IAM_IDENTITY_INVITATION.LIFECYCLE_EVENT_RECORD_ID)
                .from(IAM_IDENTITY_INVITATION)
                .where(IAM_IDENTITY_INVITATION.ID.eq(invitationId)
                    .and(IAM_IDENTITY_INVITATION.STATUS.eq(PROVISION_PENDING)))
                .forUpdate().fetchOne(IAM_IDENTITY_INVITATION.LIFECYCLE_EVENT_RECORD_ID);
            if (eventId == null) {
                return;
            }
            OffsetDateTime available = at(availableAt);
            tx.update(IAM_IDENTITY_INVITATION)
                .set(IAM_IDENTITY_INVITATION.AVAILABLE_AT, available)
                .set(IAM_IDENTITY_INVITATION.LEASE_UNTIL, (OffsetDateTime) null)
                .set(IAM_IDENTITY_INVITATION.LAST_ERROR_CODE, boundedCode)
                .set(IAM_IDENTITY_INVITATION.UPDATED_AT, now())
                .set(IAM_IDENTITY_INVITATION.ROW_VERSION,
                    IAM_IDENTITY_INVITATION.ROW_VERSION.plus(1L))
                .where(IAM_IDENTITY_INVITATION.ID.eq(invitationId)).execute();
            tx.update(IAM_IDENTITY_LIFECYCLE_RELAY_STATE)
                .set(IAM_IDENTITY_LIFECYCLE_RELAY_STATE.STATUS, "FAILED")
                .set(IAM_IDENTITY_LIFECYCLE_RELAY_STATE.AVAILABLE_AT, available)
                .set(IAM_IDENTITY_LIFECYCLE_RELAY_STATE.LEASE_UNTIL, (OffsetDateTime) null)
                .set(IAM_IDENTITY_LIFECYCLE_RELAY_STATE.LAST_ERROR_CODE, boundedCode)
                .set(IAM_IDENTITY_LIFECYCLE_RELAY_STATE.UPDATED_AT, now())
                .where(IAM_IDENTITY_LIFECYCLE_RELAY_STATE.EVENT_RECORD_ID.eq(eventId)).execute();
        });
    }

    private void activateApplicationIdentity(DSLContext tx,
                                             com.niv.payment.permission.persistence.jooq.generated.tables.records.IamIdentityInvitationRecord row,
                                             FederatedIdentity.Mode mode,
                                             OffsetDateTime completed) {
        if (mode == FederatedIdentity.Mode.NEW_DISABLED) {
            int userActivated = tx.update(IAM_USER)
                .set(IAM_USER.STATUS, ACTIVE)
                .set(IAM_USER.IDP_PROVISIONING_STATUS, "PROVISIONED")
                .set(IAM_USER.IDENTITY_VERSION, IAM_USER.IDENTITY_VERSION.plus(1L))
                .set(IAM_USER.ROW_VERSION, IAM_USER.ROW_VERSION.plus(1L))
                .set(IAM_USER.UPDATED_AT, completed)
                .where(IAM_USER.ID.eq(row.getUserId())
                    .and(IAM_USER.ACCOUNT_DOMAIN.eq(row.getAccountDomain()))
                    .and(IAM_USER.STATUS.eq("PENDING_ACTIVATION"))
                    .and(IAM_USER.IDP_PROVISIONING_STATUS.eq(PROVISION_PENDING)))
                .execute();
            int credentialActivated = tx.update(IAM_AUTHENTICATION_CREDENTIAL)
                .set(IAM_AUTHENTICATION_CREDENTIAL.STATUS, ACTIVE)
                .set(IAM_AUTHENTICATION_CREDENTIAL.ROW_VERSION,
                    IAM_AUTHENTICATION_CREDENTIAL.ROW_VERSION.plus(1L))
                .set(IAM_AUTHENTICATION_CREDENTIAL.UPDATED_AT, completed)
                .where(IAM_AUTHENTICATION_CREDENTIAL.USER_ID.eq(row.getUserId())
                    .and(IAM_AUTHENTICATION_CREDENTIAL.ACCOUNT_DOMAIN.eq(row.getAccountDomain()))
                    .and(IAM_AUTHENTICATION_CREDENTIAL.STATUS.eq(DISABLED)))
                .execute();
            if (userActivated != 1 || credentialActivated != 1) {
                throw new IllegalStateException("New identity is no longer eligible for activation");
            }
        } else if (!activeProvisionedIdentityExists(tx, row.getAccountDomain(), row.getUserId())) {
            throw new IllegalStateException("Existing identity is no longer active");
        }
        int membershipActivated = tx.update(IAM_MEMBERSHIP)
            .set(IAM_MEMBERSHIP.STATUS, ACTIVE)
            .set(IAM_MEMBERSHIP.PERMISSION_VERSION, IAM_MEMBERSHIP.PERMISSION_VERSION.plus(1L))
            .set(IAM_MEMBERSHIP.SESSION_VERSION, IAM_MEMBERSHIP.SESSION_VERSION.plus(1L))
            .set(IAM_MEMBERSHIP.ROW_VERSION, IAM_MEMBERSHIP.ROW_VERSION.plus(1L))
            .set(IAM_MEMBERSHIP.UPDATED_AT, completed)
            .where(IAM_MEMBERSHIP.ID.eq(row.getMembershipId())
                .and(IAM_MEMBERSHIP.TENANT_ID.eq(row.getTenantId()))
                .and(IAM_MEMBERSHIP.ACCOUNT_DOMAIN.eq(row.getAccountDomain()))
                .and(IAM_MEMBERSHIP.STATUS.eq("INVITED")))
            .execute();
        if (membershipActivated != 1) {
            throw new IllegalStateException("Invited membership is no longer eligible for activation");
        }
        if (TENANT_FIRST_ADMIN.equals(row.getInvitationKind())) {
            int tenantActivated = tx.update(IAM_TENANT)
                .set(IAM_TENANT.STATUS, ACTIVE)
                .set(IAM_TENANT.ROW_VERSION, IAM_TENANT.ROW_VERSION.plus(1L))
                .set(IAM_TENANT.UPDATED_AT, completed)
                .where(IAM_TENANT.ID.eq(row.getTenantId())
                    .and(IAM_TENANT.ACCOUNT_DOMAIN.eq(row.getAccountDomain()))
                    .and(IAM_TENANT.STATUS.eq(DISABLED)))
                .execute();
            int hostsActivated = tx.update(IAM_TENANT_ENTRY_HOST)
                .set(IAM_TENANT_ENTRY_HOST.STATUS, ACTIVE)
                .set(IAM_TENANT_ENTRY_HOST.ROW_VERSION,
                    IAM_TENANT_ENTRY_HOST.ROW_VERSION.plus(1L))
                .set(IAM_TENANT_ENTRY_HOST.UPDATED_AT, completed)
                .where(IAM_TENANT_ENTRY_HOST.TENANT_ID.eq(row.getTenantId())
                    .and(IAM_TENANT_ENTRY_HOST.ACCOUNT_DOMAIN.eq(row.getAccountDomain()))
                    .and(IAM_TENANT_ENTRY_HOST.STATUS.eq(DISABLED)))
                .execute();
            if (tenantActivated != 1 || hostsActivated != 1) {
                throw new IllegalStateException("Tenant bootstrap boundary is no longer activatable");
            }
        }
    }

    private static IdentityInvitationStep nextStep(Record row, FederatedIdentity.Mode mode) {
        if (mode == FederatedIdentity.Mode.EXISTING_ACTIVE) {
            return IdentityInvitationStep.APPLICATION_ACTIVATION;
        }
        if (row.get(IAM_IDENTITY_INVITATION.KEYCLOAK_USER_ENABLED_AT) == null) {
            return IdentityInvitationStep.KEYCLOAK_ENABLE;
        }
        if (row.get(IAM_IDENTITY_INVITATION.ACTION_EMAIL_SENT_AT) == null) {
            return IdentityInvitationStep.ACTION_EMAIL;
        }
        return IdentityInvitationStep.APPLICATION_ACTIVATION;
    }

    private static Reservation reservation(
        com.niv.payment.permission.persistence.jooq.generated.tables.records.IamIdentityInvitationRecord row) {
        return new Reservation(row.getId(), row.getTenantId(),
            AccountDomain.valueOf(row.getAccountDomain()), row.getIdempotencyKey(),
            row.getDisplayName(), Status.valueOf(row.getStatus()), row.getMembershipId());
    }

    private static void requireRealm(String issuer, AccountDomain accountDomain) {
        if (issuer == null || !issuer.endsWith("/realms/" + accountDomain.name())) {
            throw new IllegalArgumentException("Federated identity issuer does not match the fixed Realm");
        }
    }

    private static String localUsername(FederatedIdentity identity) {
        if (identity.mode() == FederatedIdentity.Mode.NEW_DISABLED) {
            if (!identity.username().matches("invite-[0-9a-f-]{36}")) {
                throw new IllegalArgumentException("New invitation username must be opaque");
            }
            return identity.username();
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] value = digest.digest((identity.issuer() + "\u0000" + identity.subject())
                .getBytes(StandardCharsets.UTF_8));
            return "oidc-" + HexFormat.of().formatHex(value, 0, 16);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private Invitation requireAttachedIdentity(DSLContext tx,
        com.niv.payment.permission.persistence.jooq.generated.tables.records.IamIdentityInvitationRecord invitation,
        FederatedIdentity identity) {
        var row = tx.select(IAM_USER.IDP_ISSUER, IAM_USER.IDP_SUBJECT)
            .from(IAM_USER)
            .where(IAM_USER.ID.eq(invitation.getUserId())
                .and(IAM_USER.ACCOUNT_DOMAIN.eq(invitation.getAccountDomain())))
            .fetchOne();
        if (row == null || !identity.issuer().equals(row.get(IAM_USER.IDP_ISSUER))
            || !identity.subject().equals(row.get(IAM_USER.IDP_SUBJECT))
            || !identity.mode().name().equals(invitation.getIdentityMode())) {
            throw new IllegalArgumentException("Idempotency key resolves to another identity");
        }
        return new Invitation(invitation.getId(), invitation.getMembershipId(),
            Status.valueOf(invitation.getStatus()));
    }

    private static void lockIdempotency(DSLContext tx, UUID idempotencyKey) {
        tx.execute("select pg_advisory_xact_lock(hashtextextended(?::text, 0))",
            idempotencyKey.toString());
    }

    private static com.niv.payment.permission.persistence.jooq.generated.tables.records.IamIdentityInvitationRecord
    invitationByIdempotency(DSLContext tx, UUID idempotencyKey) {
        return tx.selectFrom(IAM_IDENTITY_INVITATION)
            .where(IAM_IDENTITY_INVITATION.IDEMPOTENCY_KEY.eq(idempotencyKey))
            .forUpdate().fetchOne();
    }

    private static void requireExistingMemberRequest(DSLContext tx,
        com.niv.payment.permission.persistence.jooq.generated.tables.records.IamIdentityInvitationRecord row,
        AccountDomain accountDomain, AuthorizationSubject actor, MemberInvitationCommand command) {
        if (!MEMBER.equals(row.getInvitationKind())
            || row.getTenantId() != actor.tenantId()
            || row.getRequestedByTenantId() != actor.tenantId()
            || row.getRequestedByMembershipId() != actor.membershipId()
            || !accountDomain.name().equals(row.getAccountDomain())
            || !command.displayName().equals(row.getDisplayName())
            || !command.roleIds().equals(invitationRoleIds(tx, row.getId()))) {
            throw new IllegalArgumentException("Idempotency key belongs to another invitation request");
        }
    }

    private static void requireExistingBootstrapRequest(DSLContext tx,
        com.niv.payment.permission.persistence.jooq.generated.tables.records.IamIdentityInvitationRecord row,
        AuthorizationSubject actor, AccountDomain targetDomain, TenantBootstrapCommand command) {
        var tenant = tx.select(IAM_TENANT.TENANT_CODE, IAM_TENANT.TENANT_NAME, IAM_TENANT.TENANT_TYPE)
            .from(IAM_TENANT).where(IAM_TENANT.ID.eq(row.getTenantId())).fetchOne();
        String entryHost = tx.select(IAM_TENANT_ENTRY_HOST.ENTRY_HOST)
            .from(IAM_TENANT_ENTRY_HOST)
            .where(IAM_TENANT_ENTRY_HOST.TENANT_ID.eq(row.getTenantId()))
            .fetchOne(IAM_TENANT_ENTRY_HOST.ENTRY_HOST);
        if (!TENANT_FIRST_ADMIN.equals(row.getInvitationKind())
            || row.getRequestedByTenantId() != actor.tenantId()
            || row.getRequestedByMembershipId() != actor.membershipId()
            || !targetDomain.name().equals(row.getAccountDomain())
            || !command.firstAdministratorDisplayName().equals(row.getDisplayName())
            || tenant == null || !command.tenantCode().equals(tenant.get(IAM_TENANT.TENANT_CODE))
            || !command.tenantName().equals(tenant.get(IAM_TENANT.TENANT_NAME))
            || !command.tenantType().name().equals(tenant.get(IAM_TENANT.TENANT_TYPE))
            || !command.entryHost().equals(entryHost)) {
            throw new IllegalArgumentException("Idempotency key belongs to another tenant bootstrap");
        }
    }

    private static void requireSystemAdministrator(DSLContext tx, AccountDomain accountDomain,
                                                   AuthorizationSubject actor) {
        String domain = accountDomain.name();
        Long tenant = tx.select(IAM_TENANT.ID).from(IAM_TENANT)
            .where(IAM_TENANT.ID.eq(actor.tenantId())
                .and(IAM_TENANT.ACCOUNT_DOMAIN.eq(domain))
                .and(IAM_TENANT.STATUS.eq(ACTIVE)))
            .forUpdate().fetchOne(IAM_TENANT.ID);
        if (tenant == null) {
            throw new SecurityException("Active tenant in the fixed account domain is required");
        }
        var actorRow = tx.select(IAM_MEMBERSHIP.PERMISSION_VERSION,
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
            || actorRow.get(IAM_MEMBERSHIP.SESSION_VERSION) != actor.sessionVersion()
            || !tx.fetchExists(tx.selectOne().from(IAM_MEMBERSHIP_ROLE)
                .join(IAM_ROLE).on(IAM_ROLE.ID.eq(IAM_MEMBERSHIP_ROLE.ROLE_ID)
                    .and(IAM_ROLE.TENANT_ID.eq(IAM_MEMBERSHIP_ROLE.TENANT_ID)))
                .where(IAM_MEMBERSHIP_ROLE.TENANT_ID.eq(actor.tenantId())
                    .and(IAM_MEMBERSHIP_ROLE.MEMBERSHIP_ID.eq(actor.membershipId()))
                    .and(IAM_ROLE.SYSTEM_ROLE.isTrue())
                    .and(IAM_ROLE.STATUS.eq(ACTIVE))
                    .and(IAM_ROLE.DELETED_AT.isNull())))) {
            throw new SecurityException("Identity governance requires an active tenant system administrator");
        }
    }

    private static void requireSystemAdministratorRead(DSLContext tx, AccountDomain accountDomain,
                                                       AuthorizationSubject actor) {
        String domain = accountDomain.name();
        var actorRow = tx.select(IAM_MEMBERSHIP.PERMISSION_VERSION,
                IAM_MEMBERSHIP.SESSION_VERSION)
            .from(IAM_MEMBERSHIP)
            .join(IAM_TENANT).on(IAM_TENANT.ID.eq(IAM_MEMBERSHIP.TENANT_ID)
                .and(IAM_TENANT.ACCOUNT_DOMAIN.eq(domain)).and(IAM_TENANT.STATUS.eq(ACTIVE)))
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
            .fetchOne();
        if (actorRow == null
            || actorRow.get(IAM_MEMBERSHIP.PERMISSION_VERSION) != actor.permissionVersion()
            || actorRow.get(IAM_MEMBERSHIP.SESSION_VERSION) != actor.sessionVersion()
            || !tx.fetchExists(tx.selectOne().from(IAM_MEMBERSHIP_ROLE)
                .join(IAM_ROLE).on(IAM_ROLE.ID.eq(IAM_MEMBERSHIP_ROLE.ROLE_ID)
                    .and(IAM_ROLE.TENANT_ID.eq(IAM_MEMBERSHIP_ROLE.TENANT_ID)))
                .where(IAM_MEMBERSHIP_ROLE.TENANT_ID.eq(actor.tenantId())
                    .and(IAM_MEMBERSHIP_ROLE.MEMBERSHIP_ID.eq(actor.membershipId()))
                    .and(IAM_ROLE.SYSTEM_ROLE.isTrue()).and(IAM_ROLE.STATUS.eq(ACTIVE))
                    .and(IAM_ROLE.DELETED_AT.isNull())))) {
            throw new SecurityException("Identity governance requires an active tenant system administrator");
        }
    }

    private static void requireOrdinaryRoles(DSLContext tx, long tenantId, List<Long> roleIds) {
        List<Long> allowed = tx.select(IAM_ROLE.ID).from(IAM_ROLE)
            .where(IAM_ROLE.TENANT_ID.eq(tenantId).and(IAM_ROLE.ID.in(roleIds))
                .and(IAM_ROLE.STATUS.eq(ACTIVE)).and(IAM_ROLE.ASSIGNABLE.isTrue())
                .and(IAM_ROLE.SYSTEM_ROLE.isFalse()).and(IAM_ROLE.DELETED_AT.isNull()))
            .orderBy(IAM_ROLE.ID).fetch(IAM_ROLE.ID);
        if (!allowed.equals(roleIds.stream().sorted().toList())) {
            throw new SecurityException("Invitation roles must be ordinary assignable tenant roles");
        }
    }

    private static List<Long> invitationRoleIds(DSLContext tx, long invitationId) {
        return tx.select(IAM_IDENTITY_INVITATION_ROLE.ROLE_ID)
            .from(IAM_IDENTITY_INVITATION_ROLE)
            .where(IAM_IDENTITY_INVITATION_ROLE.INVITATION_ID.eq(invitationId))
            .orderBy(IAM_IDENTITY_INVITATION_ROLE.ROLE_ID)
            .fetch(IAM_IDENTITY_INVITATION_ROLE.ROLE_ID);
    }

    private static void insertBootstrapRole(DSLContext tx, long tenantId, long roleId,
                                            String code, String name, TenantType tenantType,
                                            boolean assignable, boolean systemRole) {
        tx.insertInto(IAM_ROLE)
            .set(IAM_ROLE.ID, roleId)
            .set(IAM_ROLE.TENANT_ID, tenantId)
            .set(IAM_ROLE.ROLE_CODE, code)
            .set(IAM_ROLE.ROLE_NAME, name)
            .set(IAM_ROLE.APPLICABLE_TENANT_TYPE, tenantType.name())
            .set(IAM_ROLE.ASSIGNABLE, assignable)
            .set(IAM_ROLE.SYSTEM_ROLE, systemRole)
            .set(IAM_ROLE.STATUS, ACTIVE)
            .execute();
    }

    private static void addPortalGrant(DSLContext tx, AccountDomain accountDomain,
                                       long tenantId, long roleId) {
        Long permissionId = tx.select(IAM_PERMISSION.ID).from(IAM_PERMISSION)
            .where(IAM_PERMISSION.PERMISSION_CODE.eq(accountDomain.accessPermissionCode())
                .and(IAM_PERMISSION.STATUS.eq(ACTIVE)))
            .fetchOne(IAM_PERMISSION.ID);
        if (permissionId == null) {
            throw new IllegalStateException("Canonical backoffice access permission is missing");
        }
        long grantId = nextId(tx);
        tx.insertInto(IAM_ROLE_GRANT)
            .set(IAM_ROLE_GRANT.ID, grantId)
            .set(IAM_ROLE_GRANT.TENANT_ID, tenantId)
            .set(IAM_ROLE_GRANT.ROLE_ID, roleId)
            .set(IAM_ROLE_GRANT.PERMISSION_ID, permissionId)
            .set(IAM_ROLE_GRANT.GRANT_KEY, "system-backoffice-access")
            .set(IAM_ROLE_GRANT.STATUS, ACTIVE)
            .execute();
        tx.insertInto(IAM_GRANT_DIMENSION)
            .set(IAM_GRANT_DIMENSION.ID, nextId(tx))
            .set(IAM_GRANT_DIMENSION.GRANT_ID, grantId)
            .set(IAM_GRANT_DIMENSION.DIMENSION_CODE, "TENANT")
            .set(IAM_GRANT_DIMENSION.SCOPE_MODE, "TENANT_ALL")
            .execute();
    }

    private static boolean activeCredentialExists(DSLContext tx, String domain, long userId) {
        return tx.fetchExists(tx.selectOne().from(IAM_AUTHENTICATION_CREDENTIAL)
            .where(IAM_AUTHENTICATION_CREDENTIAL.USER_ID.eq(userId)
                .and(IAM_AUTHENTICATION_CREDENTIAL.ACCOUNT_DOMAIN.eq(domain))
                .and(IAM_AUTHENTICATION_CREDENTIAL.STATUS.eq(ACTIVE))));
    }

    private static boolean activeProvisionedIdentityExists(DSLContext tx, String domain, long userId) {
        return tx.fetchExists(tx.selectOne().from(IAM_USER)
            .join(IAM_AUTHENTICATION_CREDENTIAL)
                .on(IAM_AUTHENTICATION_CREDENTIAL.USER_ID.eq(IAM_USER.ID)
                    .and(IAM_AUTHENTICATION_CREDENTIAL.ACCOUNT_DOMAIN.eq(domain))
                    .and(IAM_AUTHENTICATION_CREDENTIAL.STATUS.eq(ACTIVE)))
            .where(IAM_USER.ID.eq(userId).and(IAM_USER.ACCOUNT_DOMAIN.eq(domain))
                .and(IAM_USER.STATUS.eq(ACTIVE))
                .and(IAM_USER.IDP_PROVISIONING_STATUS.eq("PROVISIONED"))));
    }

    private void audit(DSLContext tx, long tenantId, Long actorMembershipId, String targetType,
                       String targetRef, String action, String reason) {
        tx.insertInto(IAM_AUDIT_EVENT)
            .set(IAM_AUDIT_EVENT.ID, nextId(tx))
            .set(IAM_AUDIT_EVENT.TENANT_ID, tenantId)
            .set(IAM_AUDIT_EVENT.OPERATOR_MEMBERSHIP_ID, actorMembershipId)
            .set(IAM_AUDIT_EVENT.TARGET_TYPE, targetType)
            .set(IAM_AUDIT_EVENT.TARGET_REF, targetRef)
            .set(IAM_AUDIT_EVENT.ACTION_CODE, action)
            .set(IAM_AUDIT_EVENT.DECISION, "ALLOW")
            .set(IAM_AUDIT_EVENT.REASON_CODE, reason)
            .set(IAM_AUDIT_EVENT.AFTER_VALUE, EMPTY_JSON)
            .set(IAM_AUDIT_EVENT.TRACE_ID, traceIdSupplier.get())
            .execute();
    }

    private static Long sameTenantActor(
        com.niv.payment.permission.persistence.jooq.generated.tables.records.IamIdentityInvitationRecord row) {
        return row.getRequestedByTenantId().equals(row.getTenantId())
            ? row.getRequestedByMembershipId() : null;
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
        return value != null && value.matches("[A-Z][A-Z0-9_]{0,63}")
            ? value : "UNCLASSIFIED_FAILURE";
    }
}
