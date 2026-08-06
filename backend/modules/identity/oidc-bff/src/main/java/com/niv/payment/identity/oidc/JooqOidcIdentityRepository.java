package com.niv.payment.identity.oidc;

import com.niv.payment.permission.domain.AccountDomain;
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

public final class JooqOidcIdentityRepository implements OidcSessionAuthenticator.IdentityRepository {
    private static final String ACTIVE = "ACTIVE";
    private static final Set<String> PORTAL_ACCESS_PERMISSION_CODES = Set.of(
        AccountDomain.PLATFORM.accessPermissionCode(), AccountDomain.MERCHANT.accessPermissionCode(),
        AccountDomain.AGENT.accessPermissionCode());

    private final DSLContext dsl;

    public JooqOidcIdentityRepository(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
    }

    @Override
    public Optional<OidcSessionAuthenticator.IdentityAccount> findActive(
        AccountDomain accountDomain, long tenantId, String issuer, String subject) {
        String domain = accountDomain.name();
        Condition portalAccess = portalAccess(accountDomain);
        var candidates = dsl.select(IAM_USER.ID, IAM_USER.IDENTITY_VERSION,
                IAM_MEMBERSHIP.ID, IAM_MEMBERSHIP.DEPARTMENT_ID,
                IAM_MEMBERSHIP.PERMISSION_VERSION, IAM_MEMBERSHIP.SESSION_VERSION)
            .from(IAM_USER)
            .join(IAM_AUTHENTICATION_CREDENTIAL).on(IAM_AUTHENTICATION_CREDENTIAL.USER_ID.eq(IAM_USER.ID)
                .and(IAM_AUTHENTICATION_CREDENTIAL.ACCOUNT_DOMAIN.eq(domain))
                .and(IAM_AUTHENTICATION_CREDENTIAL.STATUS.eq(ACTIVE)))
            .join(IAM_MEMBERSHIP).on(IAM_MEMBERSHIP.USER_ID.eq(IAM_USER.ID)
                .and(IAM_MEMBERSHIP.TENANT_ID.eq(tenantId))
                .and(IAM_MEMBERSHIP.ACCOUNT_DOMAIN.eq(domain))
                .and(IAM_MEMBERSHIP.STATUS.eq(ACTIVE)))
            .join(IAM_TENANT).on(IAM_TENANT.ID.eq(IAM_MEMBERSHIP.TENANT_ID)
                .and(IAM_TENANT.ACCOUNT_DOMAIN.eq(domain))
                .and(IAM_TENANT.STATUS.eq(ACTIVE)))
            .where(IAM_USER.IDP_ISSUER.eq(issuer)
                .and(IAM_USER.IDP_SUBJECT.eq(subject))
                .and(IAM_USER.ACCOUNT_DOMAIN.eq(domain))
                .and(IAM_USER.IDP_PROVISIONING_STATUS.eq("PROVISIONED"))
                .and(IAM_USER.STATUS.eq(ACTIVE))
                .and(portalAccess))
            .limit(2)
            .fetch();
        if (candidates.size() != 1) {
            return Optional.empty();
        }
        var record = candidates.getFirst();
        return Optional.of(new OidcSessionAuthenticator.IdentityAccount(
            record.get(IAM_USER.ID), record.get(IAM_MEMBERSHIP.ID), tenantId,
            record.get(IAM_MEMBERSHIP.DEPARTMENT_ID), record.get(IAM_MEMBERSHIP.PERMISSION_VERSION),
            record.get(IAM_MEMBERSHIP.SESSION_VERSION), record.get(IAM_USER.IDENTITY_VERSION),
            accountDomain));
    }

    private static Condition portalAccess(AccountDomain accountDomain) {
        var membershipRole = IAM_MEMBERSHIP_ROLE.as("oidc_membership_role");
        var role = IAM_ROLE.as("oidc_role");
        var grant = IAM_ROLE_GRANT.as("oidc_grant");
        var permission = IAM_PERMISSION.as("oidc_permission");
        var dimension = IAM_GRANT_DIMENSION.as("oidc_dimension");
        var extraDimension = IAM_GRANT_DIMENSION.as("oidc_extra_dimension");
        var target = IAM_GRANT_TARGET.as("oidc_target");
        var extraGrant = IAM_ROLE_GRANT.as("oidc_extra_portal_grant");
        var extraPermission = IAM_PERMISSION.as("oidc_extra_portal_permission");
        return DSL.exists(DSL.selectOne()
            .from(membershipRole)
            .join(role).on(role.TENANT_ID.eq(membershipRole.TENANT_ID)
                .and(role.ID.eq(membershipRole.ROLE_ID)).and(role.STATUS.eq(ACTIVE))
                .and(role.DELETED_AT.isNull()))
            .join(grant).on(grant.TENANT_ID.eq(role.TENANT_ID).and(grant.ROLE_ID.eq(role.ID))
                .and(grant.STATUS.eq(ACTIVE))
                .and(grant.GRANT_KEY.eq(RoleGrantAdministrationService.PROTECTED_PORTAL_GRANT_KEY))
                .and(grant.VALID_FROM.isNull()).and(grant.VALID_UNTIL.isNull()))
            .join(permission).on(permission.ID.eq(grant.PERMISSION_ID)
                .and(permission.STATUS.eq(ACTIVE))
                .and(permission.PERMISSION_CODE.eq(accountDomain.accessPermissionCode()))
                .and(permission.RISK_LEVEL.eq("NORMAL"))
                .and(permission.CROSS_TENANT_MODE.eq("SAME_TENANT_ONLY"))
                .and(permission.REQUIRED_DIMENSIONS.eq(new String[]{"TENANT"}))
                .and(permission.REQUIRES_STEP_UP.isFalse()).and(permission.REQUIRES_APPROVAL.isFalse()))
            .join(dimension).on(dimension.GRANT_ID.eq(grant.ID)
                .and(dimension.DIMENSION_CODE.eq("TENANT"))
                .and(dimension.SCOPE_MODE.eq("TENANT_ALL")))
            .where(membershipRole.TENANT_ID.eq(IAM_MEMBERSHIP.TENANT_ID)
                .and(membershipRole.MEMBERSHIP_ID.eq(IAM_MEMBERSHIP.ID))
                .andNotExists(DSL.selectOne().from(extraDimension)
                    .where(extraDimension.GRANT_ID.eq(grant.ID).and(extraDimension.ID.ne(dimension.ID))))
                .andNotExists(DSL.selectOne().from(target).where(target.DIMENSION_ID.eq(dimension.ID)))
                .andNotExists(DSL.selectOne().from(extraGrant)
                    .join(extraPermission).on(extraPermission.ID.eq(extraGrant.PERMISSION_ID))
                    .where(extraGrant.TENANT_ID.eq(grant.TENANT_ID)
                        .and(extraGrant.ROLE_ID.eq(grant.ROLE_ID)).and(extraGrant.STATUS.eq(ACTIVE))
                        .and(extraGrant.ID.ne(grant.ID))
                        .and(extraPermission.PERMISSION_CODE.in(PORTAL_ACCESS_PERMISSION_CODES))))));
    }
}
