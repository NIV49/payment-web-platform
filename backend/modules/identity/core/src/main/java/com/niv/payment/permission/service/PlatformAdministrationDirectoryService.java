package com.niv.payment.permission.service;

import com.niv.payment.permission.domain.AccountDomain;
import com.niv.payment.permission.domain.AdministrationActor;
import com.niv.payment.permission.port.PlatformAdministrationDirectoryPort;
import com.niv.payment.permission.port.PlatformAdministrationDirectoryPort.DirectoryMenu;
import com.niv.payment.permission.port.PlatformAdministrationDirectoryPort.MenuDirectoryQuery;
import com.niv.payment.permission.port.PlatformAdministrationDirectoryPort.RoleDirectoryQuery;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class PlatformAdministrationDirectoryService {

    private static final int MAX_PAGE_SIZE = 200;
    private static final int MAX_MENU_COUNT = 2_000;
    private static final int MAX_MENU_DEPTH = 32;

    private final PlatformAdministrationDirectoryPort directory;

    public PlatformAdministrationDirectoryService(PlatformAdministrationDirectoryPort directory) {
        this.directory = Objects.requireNonNull(directory, "directory");
    }

    public IdentityModels.Page<PlatformAdministrationDirectoryPort.DirectoryRole> findRoles(
            long sourceTenantId,
            AdministrationActor actor,
            RoleDirectoryQuery query) {
        requireSource(sourceTenantId, actor);
        Objects.requireNonNull(query, "query");
        var roleQuery = normalizeRoleQuery(query.roleQuery());
        var normalized = new RoleDirectoryQuery(
                query.accountDomain(),
                targetTenantId(sourceTenantId, query.accountDomain(), query.tenantId()),
                roleQuery);
        return directory.findRoles(sourceTenantId, actor, normalized);
    }

    public List<DirectoryMenu> findMenus(
            long sourceTenantId,
            AdministrationActor actor,
            MenuDirectoryQuery query) {
        requireSource(sourceTenantId, actor);
        Objects.requireNonNull(query, "query");
        var normalized = new MenuDirectoryQuery(
                query.accountDomain(),
                targetTenantId(sourceTenantId, query.accountDomain(), query.tenantId()),
                query.selectableOnly());
        var menus = List.copyOf(directory.findMenus(sourceTenantId, actor, normalized));
        validateMenuTree(menus);
        return menus;
    }

    private static IdentityModels.RoleQuery normalizeRoleQuery(IdentityModels.RoleQuery query) {
        Objects.requireNonNull(query, "roleQuery");
        if (query.id() != null && query.id() <= 0) {
            throw invalid("Identifier must be positive");
        }
        if (query.status() != null && query.status() != 0 && query.status() != 1) {
            throw invalid("Status must be 0 or 1");
        }
        if (query.startTime() != null
                && query.endTime() != null
                && query.startTime().isAfter(query.endTime())) {
            throw invalid("Invalid time range");
        }
        int page = Math.max(query.page(), 1);
        if (query.pageSize() < 1 || query.pageSize() > MAX_PAGE_SIZE) {
            throw invalid("pageSize must be between 1 and " + MAX_PAGE_SIZE);
        }
        long offset = (long) (page - 1) * query.pageSize();
        if (offset > Integer.MAX_VALUE) {
            throw invalid("Page offset exceeds the supported range");
        }
        return new IdentityModels.RoleQuery(
                query.name(),
                query.id(),
                query.status(),
                query.remark(),
                query.startTime(),
                query.endTime(),
                page,
                query.pageSize());
    }

    private static Long targetTenantId(long sourceTenantId, AccountDomain domain, Long requestedTenantId) {
        Objects.requireNonNull(domain, "accountDomain");
        if (domain == AccountDomain.PLATFORM) {
            if (requestedTenantId != null && requestedTenantId != sourceTenantId) {
                throw invalid("PLATFORM directory is fixed to the source tenant");
            }
            return sourceTenantId;
        }
        if (requestedTenantId == null || requestedTenantId <= 0) {
            throw invalid("tenantId is required for " + domain);
        }
        return requestedTenantId;
    }

    private static void validateMenuTree(List<DirectoryMenu> menus) {
        if (menus.size() > MAX_MENU_COUNT) {
            throw treeInvalid("Tree node limit exceeded");
        }
        Map<Long, DirectoryMenu> byId = new HashMap<>();
        for (DirectoryMenu directoryMenu : menus) {
            if (byId.put(directoryMenu.menu().id(), directoryMenu) != null) {
                throw treeInvalid("Tree contains duplicate identifiers");
            }
        }
        for (DirectoryMenu directoryMenu : menus) {
            Set<Long> path = new HashSet<>();
            Long currentId = directoryMenu.menu().id();
            int depth = 0;
            while (currentId != null && currentId != 0) {
                if (!path.add(currentId)) {
                    throw treeInvalid("Tree contains a cycle");
                }
                if (++depth > MAX_MENU_DEPTH) {
                    throw treeInvalid("Tree depth limit exceeded");
                }
                DirectoryMenu current = byId.get(currentId);
                if (current == null) {
                    break;
                }
                currentId = current.menu().parentId();
            }
        }
    }

    private static void requireSource(long sourceTenantId, AdministrationActor actor) {
        if (sourceTenantId <= 0) {
            throw invalid("sourceTenantId must be positive");
        }
        Objects.requireNonNull(actor, "actor");
    }

    private static IdentityAdministrationService.InvalidCommandException invalid(String message) {
        return new IdentityAdministrationService.InvalidCommandException(message);
    }

    private static IdentityAdministrationService.TreeLimitExceededException treeInvalid(String message) {
        return new IdentityAdministrationService.TreeLimitExceededException(message);
    }
}
