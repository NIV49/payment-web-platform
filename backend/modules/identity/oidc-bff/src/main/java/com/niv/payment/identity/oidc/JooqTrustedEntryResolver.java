package com.niv.payment.identity.oidc;

import org.jooq.DSLContext;

import java.util.Objects;
import java.util.Optional;

import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_TENANT;
import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_TENANT_ENTRY_HOST;

public final class JooqTrustedEntryResolver implements OidcFlowService.TrustedEntryResolver {
    private static final String ACTIVE = "ACTIVE";

    private final DSLContext dsl;

    public JooqTrustedEntryResolver(DSLContext dsl) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
    }

    @Override
    public Optional<OidcFlowService.TrustedEntry> findActive(String canonicalHost) {
        return dsl.select(IAM_TENANT_ENTRY_HOST.ENTRY_HOST, IAM_TENANT_ENTRY_HOST.ACCOUNT_DOMAIN,
                IAM_TENANT_ENTRY_HOST.TENANT_ID)
            .from(IAM_TENANT_ENTRY_HOST)
            .join(IAM_TENANT).on(IAM_TENANT.ID.eq(IAM_TENANT_ENTRY_HOST.TENANT_ID)
                .and(IAM_TENANT.ACCOUNT_DOMAIN.eq(IAM_TENANT_ENTRY_HOST.ACCOUNT_DOMAIN))
                .and(IAM_TENANT.STATUS.eq(ACTIVE)))
            .where(IAM_TENANT_ENTRY_HOST.ENTRY_HOST.eq(canonicalHost)
                .and(IAM_TENANT_ENTRY_HOST.STATUS.eq(ACTIVE)))
            .fetchOptional(record -> new OidcFlowService.TrustedEntry(
                record.get(IAM_TENANT_ENTRY_HOST.ENTRY_HOST),
                com.niv.payment.permission.domain.AccountDomain.valueOf(
                    record.get(IAM_TENANT_ENTRY_HOST.ACCOUNT_DOMAIN)),
                record.get(IAM_TENANT_ENTRY_HOST.TENANT_ID)));
    }
}
