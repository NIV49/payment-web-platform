package com.niv.payment.permission.service;

import com.niv.payment.permission.port.DepartmentAdministrationPort;
import com.niv.payment.permission.port.IdentityQueryPort;
import com.niv.payment.permission.port.MenuAdministrationPort;
import com.niv.payment.permission.port.RoleAdministrationPort;
import com.niv.payment.permission.port.UserAdministrationPort;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/** Tenant and operator identifiers come only from the trusted server-side session. */
public final class IdentityAdministrationService {
    private final IdentityQueryPort queries;
    private final UserAdministrationPort users;
    private final RoleAdministrationPort roles;
    private final DepartmentAdministrationPort departments;
    private final MenuAdministrationPort menus;

    public IdentityAdministrationService(IdentityQueryPort queries, UserAdministrationPort users,
                                         RoleAdministrationPort roles, DepartmentAdministrationPort departments,
                                         MenuAdministrationPort menus) {
        this.queries = Objects.requireNonNull(queries, "queries");
        this.users = Objects.requireNonNull(users, "users");
        this.roles = Objects.requireNonNull(roles, "roles");
        this.departments = Objects.requireNonNull(departments, "departments");
        this.menus = Objects.requireNonNull(menus, "menus");
    }

    public IdentityModels.CurrentUser currentUser(long tenantId, long membershipId) {
        return queries.findCurrentUser(tenantId, membershipId)
            .orElseThrow(() -> new ResourceNotFoundException("User membership was not found"));
    }
    public List<String> permissionCodes(long tenantId, long membershipId) {
        return queries.findPermissionCodes(tenantId, membershipId);
    }
    public List<IdentityModels.Menu> accessibleMenus(long tenantId, long membershipId) {
        return queries.findAccessibleMenus(tenantId, membershipId);
    }
    public IdentityModels.Page<IdentityModels.User> users(long tenantId, IdentityModels.UserQuery query) {
        Objects.requireNonNull(query, "query"); if (query.status()!=null) validStatus(query.status());
        validRange(query.startTime(), query.endTime());
        return users.findUsers(tenantId, new IdentityModels.UserQuery(query.username(), query.name(), query.id(),
            query.status(), query.departmentId(), query.startTime(), query.endTime(), validPage(query.page()),
            validPageSize(query.pageSize())));
    }
    public boolean userRolesChanged(long tenantId, long userId, List<Long> requestedRoleIds) {
        return !Set.copyOf(users.findUserRoleIds(tenantId, positiveId(userId))).equals(Set.copyOf(requestedRoleIds));
    }
    public IdentityModels.Page<IdentityModels.Role> roles(long tenantId, IdentityModels.RoleQuery query) {
        Objects.requireNonNull(query, "query"); if (query.status()!=null) validStatus(query.status());
        validRange(query.startTime(), query.endTime());
        return roles.findRoles(tenantId, new IdentityModels.RoleQuery(query.name(), query.id(), query.status(),
            query.remark(), query.startTime(), query.endTime(), validPage(query.page()), validPageSize(query.pageSize())));
    }
    public List<IdentityModels.Department> departments(long tenantId) { return departments.findDepartments(tenantId); }
    public List<IdentityModels.Menu> menus(long tenantId) { return menus.findMenus(tenantId); }

    public long createUser(long tenantId, long operatorId, IdentityModels.UserCreateCommand command) {
        validateUserCreate(command); return users.createUser(tenantId, operatorId, command);
    }
    public void updateUser(long tenantId, long operatorId, long id, IdentityModels.MembershipUpdateCommand command) {
        validateMembershipUpdate(command); users.updateUser(tenantId, operatorId, positiveId(id), command);
    }
    public long updateUserStatus(long tenantId, long operatorId, long id, int status, long version) {
        validStatus(status); return users.updateUserStatus(tenantId, operatorId, positiveId(id), status, version);
    }
    public void deleteUser(long tenantId, long operatorId, long id) { users.deleteUser(tenantId, operatorId, positiveId(id)); }
    public long createRole(long tenantId, long operatorId, IdentityModels.RoleCommand command) {
        validateRole(command); return roles.createRole(tenantId, operatorId, command);
    }
    public void updateRole(long tenantId, long operatorId, long id, IdentityModels.RoleCommand command) {
        validateRole(command); roles.updateRole(tenantId, operatorId, positiveId(id), command);
    }
    public void updateRoleStatus(long tenantId, long operatorId, long id, int status) {
        validStatus(status); roles.updateRoleStatus(tenantId, operatorId, positiveId(id), status);
    }
    public void deleteRole(long tenantId, long operatorId, long id) { roles.deleteRole(tenantId, operatorId, positiveId(id)); }
    public long createDepartment(long tenantId, long operatorId, IdentityModels.DepartmentCommand command) {
        validateDepartment(command); return departments.createDepartment(tenantId, operatorId, command);
    }
    public void updateDepartment(long tenantId, long operatorId, long id, IdentityModels.DepartmentCommand command) {
        validateDepartment(command); departments.updateDepartment(tenantId, operatorId, positiveId(id), command);
    }
    public void deleteDepartment(long tenantId, long operatorId, long id) { departments.deleteDepartment(tenantId, operatorId, positiveId(id)); }
    public long createMenu(long tenantId, long operatorId, IdentityModels.MenuCommand command) {
        validateMenu(command); return menus.createMenu(tenantId, operatorId, command);
    }
    public void updateMenu(long tenantId, long operatorId, long id, IdentityModels.MenuCommand command) {
        validateMenu(command); menus.updateMenu(tenantId, operatorId, positiveId(id), command);
    }
    public void deleteMenu(long tenantId, long operatorId, long id) { menus.deleteMenu(tenantId, operatorId, positiveId(id)); }
    public boolean menuNameExists(long tenantId, String name, Long id) {
        return menus.menuNameExists(tenantId, requiredText(name, "Menu name"), id);
    }
    public boolean menuPathExists(long tenantId, String path, Long id) {
        return menus.menuPathExists(tenantId, requiredText(path, "Menu path"), id);
    }

    private static void validateUserCreate(IdentityModels.UserCreateCommand command) {
        Objects.requireNonNull(command, "command"); requiredText(command.username(), "Username");
        requiredText(command.name(), "Name"); positiveId(command.departmentId());
        Objects.requireNonNull(command.roleIds(), "roleIds is required");
        command.roleIds().forEach(IdentityAdministrationService::positiveId); validStatus(command.status());
    }
    private static void validateMembershipUpdate(IdentityModels.MembershipUpdateCommand command) {
        Objects.requireNonNull(command, "command"); positiveId(command.departmentId());
        Objects.requireNonNull(command.roleIds(), "roleIds is required");
        command.roleIds().forEach(IdentityAdministrationService::positiveId); validStatus(command.status());
        if (command.userVersion() < 0) throw new InvalidCommandException("userVersion is invalid");
    }
    private static void validateRole(IdentityModels.RoleCommand command) {
        Objects.requireNonNull(command, "command"); requiredText(command.name(), "Role name");
        Objects.requireNonNull(command.menuIds(), "menuIds is required");
        command.menuIds().forEach(IdentityAdministrationService::positiveId); validStatus(command.status());
    }
    private static void validateDepartment(IdentityModels.DepartmentCommand command) {
        Objects.requireNonNull(command, "command"); requiredText(command.name(), "Department name");
        if (command.parentId() != null) positiveId(command.parentId()); validStatus(command.status());
    }
    private static void validateMenu(IdentityModels.MenuCommand command) {
        Objects.requireNonNull(command, "command"); requiredText(command.name(), "Menu name");
        requiredText(command.type(), "Menu type"); if (command.parentId() != null) positiveId(command.parentId());
        validStatus(command.status());
    }
    private static int validPage(int page) { return Math.max(page, 1); }
    private static void validRange(java.time.Instant start, java.time.Instant end) {
        if (start != null && end != null && start.isAfter(end)) throw new InvalidCommandException("Invalid time range");
    }
    private static int validPageSize(int size) {
        if (size < 1 || size > 200) throw new InvalidCommandException("pageSize must be between 1 and 200"); return size;
    }
    private static long positiveId(long id) {
        if (id <= 0) throw new InvalidCommandException("Identifier must be positive"); return id;
    }
    private static void validStatus(int status) {
        if (status != 0 && status != 1) throw new InvalidCommandException("Status must be 0 or 1");
    }
    private static String requiredText(String value, String label) {
        if (value == null || value.isBlank()) throw new InvalidCommandException(label + " is required"); return value.trim();
    }

    public static final class InvalidCommandException extends RuntimeException {
        public InvalidCommandException(String message) { super(message); }
    }
    public static final class ResourceNotFoundException extends RuntimeException {
        public ResourceNotFoundException(String message) { super(message); }
    }
    public static final class OptimisticLockException extends RuntimeException {
        public OptimisticLockException() { super("The record has changed; reload and retry"); }
    }
}
