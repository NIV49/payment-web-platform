package com.niv.payment.permission.persistence.repository;

import com.niv.payment.permission.domain.AccountDomain;
import com.niv.payment.permission.service.AuthenticationService;
import com.niv.payment.permission.service.RoleGrantAdministrationService;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_AUTHENTICATION_CREDENTIAL;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_GRANT_DIMENSION;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_GRANT_TARGET;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_MEMBERSHIP;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_MEMBERSHIP_ROLE;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_PERMISSION;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_ROLE;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_ROLE_GRANT;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_TENANT;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_USER;

/** PostgreSQL credential adapter. Tenant selection is fail-closed when more than one workspace is active. */
public final class JooqCredentialRepository implements AuthenticationService.CredentialLookup {
    private static final String ACTIVE = "ACTIVE";
    private static final Set<String> PORTAL_ACCESS_PERMISSION_CODES = Set.of(
        AccountDomain.PLATFORM.accessPermissionCode(),
        AccountDomain.MERCHANT.accessPermissionCode(),
        AccountDomain.AGENT.accessPermissionCode());

    private final DSLContext dsl;

    public JooqCredentialRepository(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
    }

    @Override
    public Optional<AuthenticationService.CredentialAccount> findActiveByUsername(
        String username, AccountDomain accountDomain) {
        String domain = accountDomain.name();
        Condition membershipScope = IAM_MEMBERSHIP.STATUS.eq(ACTIVE)
            .and(IAM_MEMBERSHIP.ACCOUNT_DOMAIN.eq(domain));
        var membershipRole = IAM_MEMBERSHIP_ROLE.as("portal_membership_role");
        var role = IAM_ROLE.as("portal_role");
        var grant = IAM_ROLE_GRANT.as("portal_grant");
        var permission = IAM_PERMISSION.as("portal_permission");
        var dimension = IAM_GRANT_DIMENSION.as("portal_dimension");
        var extraDimension = IAM_GRANT_DIMENSION.as("portal_extra_dimension");
        var extraPortalGrant = IAM_ROLE_GRANT.as("extra_portal_grant");
        var extraPortalPermission = IAM_PERMISSION.as("extra_portal_permission");
        var target = IAM_GRANT_TARGET.as("portal_target");
        Condition explicitPortalAccess = DSL.exists(DSL.selectOne()
            .from(membershipRole)
            .join(role).on(role.TENANT_ID.eq(membershipRole.TENANT_ID)
                .and(role.ID.eq(membershipRole.ROLE_ID))
                .and(role.STATUS.eq(ACTIVE))
                .and(role.DELETED_AT.isNull()))
            .join(grant).on(grant.TENANT_ID.eq(role.TENANT_ID)
                .and(grant.ROLE_ID.eq(role.ID))
                .and(grant.STATUS.eq(ACTIVE))
                .and(grant.GRANT_KEY.eq(
                    RoleGrantAdministrationService.PROTECTED_PORTAL_GRANT_KEY))
                .and(grant.VALID_FROM.isNull())
                .and(grant.VALID_UNTIL.isNull()))
            .join(permission).on(permission.ID.eq(grant.PERMISSION_ID)
                .and(permission.STATUS.eq(ACTIVE))
                .and(permission.PERMISSION_CODE.eq(accountDomain.accessPermissionCode()))
                .and(permission.RISK_LEVEL.eq("NORMAL"))
                .and(permission.CROSS_TENANT_MODE.eq("SAME_TENANT_ONLY"))
                .and(permission.REQUIRED_DIMENSIONS.eq(new String[]{"TENANT"}))
                .and(permission.REQUIRES_STEP_UP.isFalse())
                .and(permission.REQUIRES_APPROVAL.isFalse()))
            .join(dimension).on(dimension.GRANT_ID.eq(grant.ID)
                .and(dimension.DIMENSION_CODE.eq("TENANT"))
                .and(dimension.SCOPE_MODE.eq("TENANT_ALL")))
            .where(membershipRole.TENANT_ID.eq(IAM_MEMBERSHIP.TENANT_ID)
                .and(membershipRole.MEMBERSHIP_ID.eq(IAM_MEMBERSHIP.ID))
                .andNotExists(DSL.selectOne().from(extraDimension)
                    .where(extraDimension.GRANT_ID.eq(grant.ID)
                        .and(extraDimension.ID.ne(dimension.ID))))
                .andNotExists(DSL.selectOne().from(target)
                    .where(target.DIMENSION_ID.eq(dimension.ID)))
                .andNotExists(DSL.selectOne()
                    .from(extraPortalGrant)
                    .join(extraPortalPermission).on(extraPortalPermission.ID.eq(extraPortalGrant.PERMISSION_ID))
                    .where(extraPortalGrant.TENANT_ID.eq(grant.TENANT_ID)
                        .and(extraPortalGrant.ROLE_ID.eq(grant.ROLE_ID))
                        .and(extraPortalGrant.STATUS.eq(ACTIVE))
                        .and(extraPortalGrant.ID.ne(grant.ID))
                        .and(extraPortalPermission.PERMISSION_CODE.in(PORTAL_ACCESS_PERMISSION_CODES))))));

        var candidates = dsl.select(
                IAM_USER.ID,
                IAM_MEMBERSHIP.ID,
                IAM_MEMBERSHIP.TENANT_ID,
                IAM_MEMBERSHIP.DEPARTMENT_ID,
                IAM_MEMBERSHIP.PERMISSION_VERSION,
                IAM_MEMBERSHIP.SESSION_VERSION,
                IAM_USER.IDENTITY_VERSION,
                IAM_MEMBERSHIP.ACCOUNT_DOMAIN,
                IAM_AUTHENTICATION_CREDENTIAL.PASSWORD_HASH)
            .from(IAM_AUTHENTICATION_CREDENTIAL)
            .join(IAM_USER)
                .on(IAM_USER.ID.eq(IAM_AUTHENTICATION_CREDENTIAL.USER_ID)
                    .and(IAM_USER.STATUS.eq(ACTIVE))
                    .and(IAM_USER.ACCOUNT_DOMAIN.eq(domain))
                    .and(IAM_AUTHENTICATION_CREDENTIAL.ACCOUNT_DOMAIN.eq(domain)))
            .join(IAM_MEMBERSHIP)
                .on(IAM_MEMBERSHIP.USER_ID.eq(IAM_USER.ID))
            .join(IAM_TENANT)
                .on(IAM_TENANT.ID.eq(IAM_MEMBERSHIP.TENANT_ID)
                    .and(IAM_TENANT.STATUS.eq(ACTIVE))
                    .and(IAM_TENANT.ACCOUNT_DOMAIN.eq(domain)))
            .where(IAM_AUTHENTICATION_CREDENTIAL.USERNAME.eq(username)
                .and(IAM_AUTHENTICATION_CREDENTIAL.STATUS.eq(ACTIVE))
                .and(IAM_AUTHENTICATION_CREDENTIAL.PASSWORD_HASH.isNotNull())
                .and(membershipScope)
                .and(explicitPortalAccess))
            .orderBy(IAM_MEMBERSHIP.ID)
            .limit(2)
            .fetch();

        if (candidates.size() != 1) {
            return Optional.empty();
        }
        var record = candidates.getFirst();
        return Optional.of(new AuthenticationService.CredentialAccount(
            record.get(IAM_USER.ID),
            record.get(IAM_MEMBERSHIP.ID),
            record.get(IAM_MEMBERSHIP.TENANT_ID),
            record.get(IAM_MEMBERSHIP.DEPARTMENT_ID),
            record.get(IAM_MEMBERSHIP.PERMISSION_VERSION),
            record.get(IAM_MEMBERSHIP.SESSION_VERSION),
            record.get(IAM_USER.IDENTITY_VERSION),
            AccountDomain.valueOf(record.get(IAM_MEMBERSHIP.ACCOUNT_DOMAIN)),
            record.get(IAM_AUTHENTICATION_CREDENTIAL.PASSWORD_HASH)));
    }

    @Override
    public void markLoginSucceeded(long userId) {
        dsl.update(IAM_AUTHENTICATION_CREDENTIAL)
            .set(IAM_AUTHENTICATION_CREDENTIAL.LAST_LOGIN_AT, DSL.currentOffsetDateTime())
            .set(IAM_AUTHENTICATION_CREDENTIAL.UPDATED_AT, DSL.currentOffsetDateTime())
            .set(IAM_AUTHENTICATION_CREDENTIAL.ROW_VERSION,
                IAM_AUTHENTICATION_CREDENTIAL.ROW_VERSION.plus(1L))
            .where(IAM_AUTHENTICATION_CREDENTIAL.USER_ID.eq(userId))
            .execute();
    }
}
