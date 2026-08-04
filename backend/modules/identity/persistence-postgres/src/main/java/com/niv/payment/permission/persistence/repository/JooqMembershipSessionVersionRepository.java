package com.niv.payment.permission.persistence.repository;

import com.niv.payment.permission.domain.AccountDomain;
import com.niv.payment.permission.port.MembershipSessionVersionRepository;
import org.jooq.DSLContext;

import java.util.Objects;
import java.util.Optional;

import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_AUTHENTICATION_CREDENTIAL;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_MEMBERSHIP;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_TENANT;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_USER;

public final class JooqMembershipSessionVersionRepository implements MembershipSessionVersionRepository {
    private static final String ACTIVE = "ACTIVE";

    private final DSLContext dsl;

    public JooqMembershipSessionVersionRepository(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
    }

    @Override
    public Optional<MembershipVersions> findActiveVersions(AccountDomain accountDomain, long tenantId,
                                                           long membershipId, long userId) {
        String domain = accountDomain.name();
        return dsl.select(IAM_MEMBERSHIP.PERMISSION_VERSION, IAM_MEMBERSHIP.SESSION_VERSION)
            .from(IAM_MEMBERSHIP)
            .join(IAM_TENANT)
                .on(IAM_TENANT.ID.eq(IAM_MEMBERSHIP.TENANT_ID)
                    .and(IAM_TENANT.STATUS.eq(ACTIVE)))
                    .and(IAM_TENANT.ACCOUNT_DOMAIN.eq(domain))
            .join(IAM_USER)
                .on(IAM_USER.ID.eq(IAM_MEMBERSHIP.USER_ID)
                    .and(IAM_USER.STATUS.eq(ACTIVE))
                    .and(IAM_USER.ACCOUNT_DOMAIN.eq(domain)))
            .join(IAM_AUTHENTICATION_CREDENTIAL)
                .on(IAM_AUTHENTICATION_CREDENTIAL.USER_ID.eq(IAM_USER.ID)
                    .and(IAM_AUTHENTICATION_CREDENTIAL.STATUS.eq(ACTIVE))
                    .and(IAM_AUTHENTICATION_CREDENTIAL.ACCOUNT_DOMAIN.eq(domain))
                    .and(IAM_AUTHENTICATION_CREDENTIAL.PASSWORD_HASH.isNotNull()))
            .where(IAM_MEMBERSHIP.TENANT_ID.eq(tenantId)
                .and(IAM_MEMBERSHIP.ID.eq(membershipId))
                .and(IAM_MEMBERSHIP.USER_ID.eq(userId))
                .and(IAM_MEMBERSHIP.ACCOUNT_DOMAIN.eq(domain))
                .and(IAM_MEMBERSHIP.STATUS.eq(ACTIVE)))
            .fetchOptional(row -> new MembershipVersions(
                row.get(IAM_MEMBERSHIP.PERMISSION_VERSION),
                row.get(IAM_MEMBERSHIP.SESSION_VERSION)));
    }
}
