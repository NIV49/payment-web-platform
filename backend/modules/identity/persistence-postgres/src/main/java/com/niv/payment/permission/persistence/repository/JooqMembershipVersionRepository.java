package com.niv.payment.permission.persistence.repository;

import com.niv.payment.permission.port.InvalidAuthorizationSubjectException;
import com.niv.payment.permission.port.MembershipVersionRepository;
import org.jooq.DSLContext;

import java.util.Objects;

import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_AUTHENTICATION_CREDENTIAL;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_MEMBERSHIP;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_TENANT;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_USER;

public final class JooqMembershipVersionRepository implements MembershipVersionRepository {
    private static final String ACTIVE = "ACTIVE";

    private final DSLContext dsl;

    public JooqMembershipVersionRepository(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
    }

    @Override
    public long findPermissionVersion(long tenantId, long membershipId) {
        return dsl.select(IAM_MEMBERSHIP.PERMISSION_VERSION)
            .from(IAM_MEMBERSHIP)
            .join(IAM_TENANT)
                .on(IAM_TENANT.ID.eq(IAM_MEMBERSHIP.TENANT_ID)
                    .and(IAM_TENANT.STATUS.eq(ACTIVE)))
            .join(IAM_USER)
                .on(IAM_USER.ID.eq(IAM_MEMBERSHIP.USER_ID)
                    .and(IAM_USER.STATUS.eq(ACTIVE)))
            .join(IAM_AUTHENTICATION_CREDENTIAL)
                .on(IAM_AUTHENTICATION_CREDENTIAL.USER_ID.eq(IAM_USER.ID)
                    .and(IAM_AUTHENTICATION_CREDENTIAL.STATUS.eq(ACTIVE))
                    .and(IAM_AUTHENTICATION_CREDENTIAL.PASSWORD_HASH.isNotNull()))
            .where(IAM_MEMBERSHIP.TENANT_ID.eq(tenantId)
                .and(IAM_MEMBERSHIP.ID.eq(membershipId))
                .and(IAM_MEMBERSHIP.STATUS.eq(ACTIVE)))
            .fetchOptional(IAM_MEMBERSHIP.PERMISSION_VERSION)
            .orElseThrow(InvalidAuthorizationSubjectException::new);
    }
}
