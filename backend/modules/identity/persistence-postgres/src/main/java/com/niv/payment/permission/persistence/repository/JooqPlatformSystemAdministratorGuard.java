package com.niv.payment.permission.persistence.repository;

import com.niv.payment.permission.domain.AccountDomain;
import com.niv.payment.permission.service.IdentityAdministrationService;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

import java.util.List;
import java.util.Objects;

import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_GRANT_DIMENSION;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_MEMBERSHIP_ROLE;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_PERMISSION;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_ROLE;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_ROLE_GRANT;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_TENANT;

final class JooqPlatformSystemAdministratorGuard {
    private static final String ACTIVE = "ACTIVE";

    private final DSLContext dsl;

    JooqPlatformSystemAdministratorGuard(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
    }

    void require(long tenantId, long membershipId) {
        long protectedRoleId = protectedSystemRole(tenantId);
        boolean allowed = dsl.fetchExists(dsl.selectOne()
            .from(IAM_MEMBERSHIP_ROLE)
            .join(IAM_ROLE).on(IAM_ROLE.TENANT_ID.eq(IAM_MEMBERSHIP_ROLE.TENANT_ID)
                .and(IAM_ROLE.ID.eq(IAM_MEMBERSHIP_ROLE.ROLE_ID)))
            .where(IAM_MEMBERSHIP_ROLE.TENANT_ID.eq(tenantId)
                .and(IAM_MEMBERSHIP_ROLE.MEMBERSHIP_ID.eq(membershipId))
                .and(IAM_ROLE.ID.eq(protectedRoleId))
                .and(IAM_ROLE.STATUS.eq(ACTIVE))
                .and(IAM_ROLE.DELETED_AT.isNull())));
        if (!allowed) {
            throw new SecurityException("Platform system administrator is required");
        }
    }

    long protectedSystemRole(long tenantId) {
        String domainValue = dsl.select(IAM_TENANT.ACCOUNT_DOMAIN).from(IAM_TENANT)
            .where(IAM_TENANT.ID.eq(tenantId)
                .and(IAM_TENANT.STATUS.eq(ACTIVE)))
            .fetchOne(IAM_TENANT.ACCOUNT_DOMAIN);
        if (domainValue == null) {
            throw JooqAdministrationSupport.notFound("Tenant");
        }
        String entryPermission = switch (AccountDomain.valueOf(domainValue)) {
            case PLATFORM -> "backoffice:platform-access";
            case MERCHANT -> "backoffice:merchant-access";
            case AGENT -> "backoffice:agent-access";
        };
        List<Long> ids = dsl.select(IAM_ROLE.ID).from(IAM_ROLE)
            .where(IAM_ROLE.TENANT_ID.eq(tenantId)
                .and(IAM_ROLE.SYSTEM_ROLE.isTrue())
                .and(IAM_ROLE.ASSIGNABLE.isFalse())
                .and(IAM_ROLE.STATUS.eq(ACTIVE))
                .and(IAM_ROLE.DELETED_AT.isNull())
                .andExists(DSL.selectOne()
                    .from(IAM_ROLE_GRANT)
                    .join(IAM_PERMISSION)
                        .on(IAM_PERMISSION.ID.eq(IAM_ROLE_GRANT.PERMISSION_ID))
                    .join(IAM_GRANT_DIMENSION)
                        .on(IAM_GRANT_DIMENSION.GRANT_ID.eq(IAM_ROLE_GRANT.ID))
                    .where(IAM_ROLE_GRANT.TENANT_ID.eq(tenantId)
                        .and(IAM_ROLE_GRANT.ROLE_ID.eq(IAM_ROLE.ID))
                        .and(IAM_ROLE_GRANT.GRANT_KEY.eq("system-backoffice-access"))
                        .and(IAM_ROLE_GRANT.STATUS.eq(ACTIVE))
                        .and(IAM_ROLE_GRANT.VALID_FROM.isNull()
                            .or(IAM_ROLE_GRANT.VALID_FROM.le(DSL.currentOffsetDateTime())))
                        .and(IAM_ROLE_GRANT.VALID_UNTIL.isNull()
                            .or(IAM_ROLE_GRANT.VALID_UNTIL.gt(DSL.currentOffsetDateTime())))
                        .and(IAM_PERMISSION.PERMISSION_CODE.eq(entryPermission))
                        .and(IAM_PERMISSION.STATUS.eq(ACTIVE))
                        .and(IAM_GRANT_DIMENSION.DIMENSION_CODE.eq("TENANT"))
                        .and(IAM_GRANT_DIMENSION.SCOPE_MODE.eq("TENANT_ALL")))))
            .orderBy(IAM_ROLE.ID)
            .limit(2)
            .fetch(IAM_ROLE.ID);
        if (ids.size() != 1) {
            throw new IdentityAdministrationService.DataConflictException(
                "Tenant must have exactly one protected system role");
        }
        return ids.getFirst();
    }
}
