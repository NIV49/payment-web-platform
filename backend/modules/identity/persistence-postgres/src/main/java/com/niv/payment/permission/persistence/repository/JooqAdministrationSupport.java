package com.niv.payment.permission.persistence.repository;

import com.niv.payment.permission.domain.AdministrationActor;
import com.niv.payment.permission.port.InvalidAuthorizationSubjectException;
import com.niv.payment.permission.port.StalePermissionVersionException;
import com.niv.payment.permission.service.IdentityAdministrationService;
import org.jooq.DSLContext;
import org.jooq.JSONB;

import java.util.Objects;
import java.util.function.Supplier;

import static com.niv.payment.permission.persistence.jooq.generated.Sequences.IAM_ID_SEQ;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_AUTHENTICATION_CREDENTIAL;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_AUDIT_EVENT;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_MEMBERSHIP;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_TENANT;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_USER;

final class JooqAdministrationSupport {
    static final String ACTIVE = "ACTIVE";
    static final String DISABLED = "DISABLED";
    static final String TERMINATED = "TERMINATED";

    private static final JSONB EMPTY_JSON = JSONB.valueOf("{}");

    private final DSLContext dsl;
    private final Supplier<String> traceIdSupplier;

    JooqAdministrationSupport(DSLContext dsl, Supplier<String> traceIdSupplier) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
        this.traceIdSupplier = Objects.requireNonNull(traceIdSupplier, "traceIdSupplier");
    }

    void requirePlatformTenant(long tenantId) {
        boolean activePlatform = dsl.fetchExists(dsl.selectOne()
            .from(IAM_TENANT)
            .where(IAM_TENANT.ID.eq(tenantId)
                .and(IAM_TENANT.TENANT_TYPE.eq("PLATFORM"))
                .and(IAM_TENANT.STATUS.eq(ACTIVE))));
        if (!activePlatform) {
            throw new SecurityException("Platform tenant is required");
        }
    }

    void lockTenant(long tenantId, AdministrationActor actor) {
        Objects.requireNonNull(actor, "actor");
        Long locked = dsl.select(IAM_TENANT.ID)
            .from(IAM_TENANT)
            .where(IAM_TENANT.ID.eq(tenantId)
                .and(IAM_TENANT.TENANT_TYPE.eq("PLATFORM"))
                .and(IAM_TENANT.STATUS.eq(ACTIVE)))
            .forUpdate()
            .fetchOne(IAM_TENANT.ID);
        if (locked == null) {
            throw new SecurityException("Active platform tenant is required");
        }
        var currentVersions = dsl.select(
                IAM_MEMBERSHIP.PERMISSION_VERSION,
                IAM_MEMBERSHIP.SESSION_VERSION)
            .from(IAM_MEMBERSHIP)
            .join(IAM_USER)
                .on(IAM_USER.ID.eq(IAM_MEMBERSHIP.USER_ID)
                    .and(IAM_USER.STATUS.eq(ACTIVE)))
            .join(IAM_AUTHENTICATION_CREDENTIAL)
                .on(IAM_AUTHENTICATION_CREDENTIAL.USER_ID.eq(IAM_USER.ID)
                    .and(IAM_AUTHENTICATION_CREDENTIAL.STATUS.eq(ACTIVE))
                    .and(IAM_AUTHENTICATION_CREDENTIAL.PASSWORD_HASH.isNotNull()))
            .where(IAM_MEMBERSHIP.TENANT_ID.eq(tenantId)
                .and(IAM_MEMBERSHIP.ID.eq(actor.membershipId()))
                .and(IAM_MEMBERSHIP.USER_ID.eq(actor.expectedUserId()))
                .and(IAM_MEMBERSHIP.STATUS.eq(ACTIVE)))
            .forUpdate()
            .of(IAM_MEMBERSHIP, IAM_USER, IAM_AUTHENTICATION_CREDENTIAL)
            .fetchOne();
        if (currentVersions == null) {
            throw new InvalidAuthorizationSubjectException();
        }
        if (currentVersions.value1() != actor.expectedPermissionVersion()) {
            throw new StalePermissionVersionException();
        }
        if (currentVersions.value2() != actor.expectedSessionVersion()) {
            throw new InvalidAuthorizationSubjectException();
        }
    }

    void validateActor(long tenantId, AdministrationActor actor) {
        Objects.requireNonNull(actor, "actor");
        requirePlatformTenant(tenantId);
        var currentVersions = dsl.select(
                IAM_MEMBERSHIP.PERMISSION_VERSION,
                IAM_MEMBERSHIP.SESSION_VERSION)
            .from(IAM_MEMBERSHIP)
            .join(IAM_USER)
                .on(IAM_USER.ID.eq(IAM_MEMBERSHIP.USER_ID)
                    .and(IAM_USER.STATUS.eq(ACTIVE)))
            .join(IAM_AUTHENTICATION_CREDENTIAL)
                .on(IAM_AUTHENTICATION_CREDENTIAL.USER_ID.eq(IAM_USER.ID)
                    .and(IAM_AUTHENTICATION_CREDENTIAL.STATUS.eq(ACTIVE))
                    .and(IAM_AUTHENTICATION_CREDENTIAL.PASSWORD_HASH.isNotNull()))
            .where(IAM_MEMBERSHIP.TENANT_ID.eq(tenantId)
                .and(IAM_MEMBERSHIP.ID.eq(actor.membershipId()))
                .and(IAM_MEMBERSHIP.USER_ID.eq(actor.expectedUserId()))
                .and(IAM_MEMBERSHIP.STATUS.eq(ACTIVE)))
            .fetchOne();
        if (currentVersions == null) {
            throw new InvalidAuthorizationSubjectException();
        }
        if (currentVersions.value1() != actor.expectedPermissionVersion()) {
            throw new StalePermissionVersionException();
        }
        if (currentVersions.value2() != actor.expectedSessionVersion()) {
            throw new InvalidAuthorizationSubjectException();
        }
    }

    long nextId() {
        var nextId = IAM_ID_SEQ.nextval();
        Long id = dsl.select(nextId).fetchOne(nextId);
        if (id == null) {
            throw new IllegalStateException("IAM sequence did not return an identifier");
        }
        return id;
    }

    void audit(long tenantId, long operatorMembershipId, String targetType,
               long targetId, String action, String permissionCode) {
        dsl.insertInto(IAM_AUDIT_EVENT)
            .set(IAM_AUDIT_EVENT.ID, nextId())
            .set(IAM_AUDIT_EVENT.TENANT_ID, tenantId)
            .set(IAM_AUDIT_EVENT.OPERATOR_MEMBERSHIP_ID, operatorMembershipId)
            .set(IAM_AUDIT_EVENT.TARGET_TYPE, targetType)
            .set(IAM_AUDIT_EVENT.TARGET_REF, Long.toString(targetId))
            .set(IAM_AUDIT_EVENT.ACTION_CODE, action)
            .set(IAM_AUDIT_EVENT.DECISION, "ALLOW")
            .set(IAM_AUDIT_EVENT.REASON_CODE, "AUTHORIZED")
            .set(IAM_AUDIT_EVENT.PERMISSION_CODE, permissionCode)
            .set(IAM_AUDIT_EVENT.AFTER_VALUE, EMPTY_JSON)
            .set(IAM_AUDIT_EVENT.TRACE_ID, traceIdSupplier.get())
            .execute();
    }

    static RuntimeException notFound(String label) {
        return new IdentityAdministrationService.ResourceNotFoundException(label + " was not found");
    }

    static String status(int value) {
        return value == 1 ? ACTIVE : DISABLED;
    }

    static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
