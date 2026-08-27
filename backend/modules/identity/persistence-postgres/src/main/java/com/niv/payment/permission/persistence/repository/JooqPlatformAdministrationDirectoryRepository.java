package com.niv.payment.permission.persistence.repository;

import com.niv.payment.permission.domain.AccountDomain;
import com.niv.payment.permission.domain.AdministrationActor;
import com.niv.payment.permission.port.PlatformAdministrationDirectoryPort;
import com.niv.payment.permission.service.IdentityModels;
import org.jooq.DSLContext;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Objects;

import static com.niv.payment.permission.persistence.jooq.generated.Tables.IAM_TENANT;

/** Read-only PLATFORM control-plane adapter for role and menu directories. */
public class JooqPlatformAdministrationDirectoryRepository
        implements PlatformAdministrationDirectoryPort {
    private static final String ACTIVE = "ACTIVE";

    private final DSLContext dsl;
    private final JooqAdministrationSupport source;
    private final JooqPlatformSystemAdministratorGuard systemAdministrator;
    private final JooqIdentityQueryRepository queries;

    public JooqPlatformAdministrationDirectoryRepository(
            DSLContext dsl,
            JooqIdentityQueryRepository queries) {
        this.dsl = Objects.requireNonNull(dsl, "dsl");
        this.queries = Objects.requireNonNull(queries, "queries");
        this.source = new JooqAdministrationSupport(dsl, AccountDomain.PLATFORM, () -> "platform-directory");
        this.systemAdministrator = new JooqPlatformSystemAdministratorGuard(dsl);
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public IdentityModels.Page<DirectoryRole> findRoles(
            long sourceTenantId,
            AdministrationActor actor,
            RoleDirectoryQuery query) {
        source.validateActor(sourceTenantId, actor);
        systemAdministrator.require(sourceTenantId, actor.membershipId());
        DirectoryScope scope = scope(sourceTenantId, query.accountDomain(), query.tenantId());
        var page = queries.findRoles(scope.tenantId(), query.roleQuery());
        return new IdentityModels.Page<>(
            page.items().stream().map(role -> new DirectoryRole(scope, role)).toList(),
            page.total());
    }

    @Override
    @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
    public List<DirectoryMenu> findMenus(
            long sourceTenantId,
            AdministrationActor actor,
            MenuDirectoryQuery query) {
        source.validateActor(sourceTenantId, actor);
        systemAdministrator.require(sourceTenantId, actor.membershipId());
        DirectoryScope scope = scope(sourceTenantId, query.accountDomain(), query.tenantId());
        return queries.findMenus(scope.tenantId(), query.selectableOnly()).stream()
            .map(menu -> new DirectoryMenu(scope, menu))
            .toList();
    }

    private DirectoryScope scope(long sourceTenantId, AccountDomain accountDomain, Long targetTenantId) {
        Objects.requireNonNull(accountDomain, "accountDomain");
        if (targetTenantId == null || targetTenantId <= 0) {
            throw new IllegalArgumentException("tenantId must be positive");
        }
        if (accountDomain == AccountDomain.PLATFORM && targetTenantId != sourceTenantId) {
            throw new SecurityException("PLATFORM directory is limited to the current tenant");
        }
        var target = dsl.select(
                IAM_TENANT.ID, IAM_TENANT.TENANT_NAME, IAM_TENANT.ACCOUNT_DOMAIN)
            .from(IAM_TENANT)
            .where(IAM_TENANT.ID.eq(targetTenantId)
                .and(IAM_TENANT.ACCOUNT_DOMAIN.eq(accountDomain.name()))
                .and(IAM_TENANT.STATUS.eq(ACTIVE)))
            .fetchOne();
        if (target == null) {
            throw JooqAdministrationSupport.notFound("Target tenant");
        }
        ManagementMode mode = accountDomain == AccountDomain.PLATFORM
            ? ManagementMode.SAME_TENANT
            : ManagementMode.READ_ONLY;
        return new DirectoryScope(
            AccountDomain.valueOf(target.get(IAM_TENANT.ACCOUNT_DOMAIN)),
            target.get(IAM_TENANT.ID), target.get(IAM_TENANT.TENANT_NAME), mode);
    }
}
