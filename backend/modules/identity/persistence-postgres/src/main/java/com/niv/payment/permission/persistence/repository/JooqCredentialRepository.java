package com.niv.payment.permission.persistence.repository;

import com.niv.payment.permission.service.AuthenticationService;
import org.jooq.Condition;
import org.jooq.DSLContext;
import org.jooq.impl.DSL;

import java.util.Objects;
import java.util.Optional;

import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_AUTHENTICATION_CREDENTIAL;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_MEMBERSHIP;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_TENANT;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_USER;

/** PostgreSQL credential adapter. Tenant selection is fail-closed when more than one workspace is active. */
public final class JooqCredentialRepository implements AuthenticationService.CredentialLookup {
    private static final String ACTIVE = "ACTIVE";

    private final DSLContext dsl;

    public JooqCredentialRepository(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
    }

    @Override
    public Optional<AuthenticationService.CredentialAccount> findActiveByUsername(String username, Long tenantId) {
        Condition membershipScope = IAM_MEMBERSHIP.STATUS.eq(ACTIVE);
        if (tenantId != null) {
            membershipScope = membershipScope.and(IAM_MEMBERSHIP.TENANT_ID.eq(tenantId));
        }

        var candidates = dsl.select(
                IAM_USER.ID,
                IAM_MEMBERSHIP.ID,
                IAM_MEMBERSHIP.TENANT_ID,
                IAM_MEMBERSHIP.DEPARTMENT_ID,
                IAM_MEMBERSHIP.PERMISSION_VERSION,
                IAM_MEMBERSHIP.SESSION_VERSION,
                IAM_AUTHENTICATION_CREDENTIAL.PASSWORD_HASH)
            .from(IAM_AUTHENTICATION_CREDENTIAL)
            .join(IAM_USER)
                .on(IAM_USER.ID.eq(IAM_AUTHENTICATION_CREDENTIAL.USER_ID)
                    .and(IAM_USER.STATUS.eq(ACTIVE)))
            .join(IAM_MEMBERSHIP)
                .on(IAM_MEMBERSHIP.USER_ID.eq(IAM_USER.ID))
            .join(IAM_TENANT)
                .on(IAM_TENANT.ID.eq(IAM_MEMBERSHIP.TENANT_ID)
                    .and(IAM_TENANT.STATUS.eq(ACTIVE)))
            .where(IAM_AUTHENTICATION_CREDENTIAL.USERNAME.eq(username)
                .and(IAM_AUTHENTICATION_CREDENTIAL.STATUS.eq(ACTIVE))
                .and(IAM_AUTHENTICATION_CREDENTIAL.PASSWORD_HASH.isNotNull())
                .and(membershipScope))
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
