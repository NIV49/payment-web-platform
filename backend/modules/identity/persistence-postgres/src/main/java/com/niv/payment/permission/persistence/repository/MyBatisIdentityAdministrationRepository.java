package com.niv.payment.permission.persistence.repository;

import com.niv.payment.permission.persistence.mapper.IdentityAdminMapper;
import com.niv.payment.permission.persistence.mapper.RoleAssignmentRow;
import com.niv.payment.permission.port.DepartmentAdministrationPort;
import com.niv.payment.permission.port.IdentityQueryPort;
import com.niv.payment.permission.port.MenuAdministrationPort;
import com.niv.payment.permission.port.RoleAdministrationPort;
import com.niv.payment.permission.port.UserAdministrationPort;
import com.niv.payment.permission.service.AuthenticationService;
import com.niv.payment.permission.service.IdentityAdministrationService;
import com.niv.payment.permission.service.IdentityModels;
import com.niv.payment.permission.service.RoleAssignmentPolicy;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.Set;

public class MyBatisIdentityAdministrationRepository implements AuthenticationService.CredentialLookup,
    IdentityQueryPort, UserAdministrationPort, RoleAdministrationPort,
    DepartmentAdministrationPort, MenuAdministrationPort {

    private final IdentityAdminMapper mapper;
    private final RoleAssignmentPolicy roleAssignmentPolicy;
    private final Supplier<String> traceIdSupplier;

    public MyBatisIdentityAdministrationRepository(IdentityAdminMapper mapper, Supplier<String> traceIdSupplier) {
        this.mapper = mapper;
        this.roleAssignmentPolicy = new RoleAssignmentPolicy();
        this.traceIdSupplier = traceIdSupplier;
    }

    @Override
    public Optional<AuthenticationService.CredentialAccount> findActiveByUsername(String username, Long tenantId) {
        Map<String, Object> row = mapper.findActiveCredential(username, tenantId == null ? 0L : tenantId,
            tenantId != null);
        if (row == null) return Optional.empty();
        return Optional.of(new AuthenticationService.CredentialAccount(number(row, "userId"),
            number(row, "membershipId"), number(row, "tenantId"), nullableNumber(row, "departmentId"),
            number(row, "permissionVersion"), number(row, "sessionVersion"), text(row, "passwordHash")));
    }

    @Override
    public void markLoginSucceeded(long userId) {
        mapper.markLoginSucceeded(userId);
    }

    @Override
    public Optional<IdentityModels.CurrentUser> findCurrentUser(long tenantId, long membershipId) {
        Map<String, Object> row = mapper.findCurrentUser(tenantId, membershipId);
        if (row == null) return Optional.empty();
        return Optional.of(new IdentityModels.CurrentUser(number(row, "id"), text(row, "username"),
            text(row, "realName"), text(row, "avatar"), strings(row.get("roles")), text(row, "homePath")));
    }

    @Override
    public List<String> findPermissionCodes(long tenantId, long membershipId) {
        return List.copyOf(mapper.findPermissionCodes(tenantId, membershipId));
    }

    @Override
    public List<IdentityModels.Menu> findAccessibleMenus(long tenantId, long membershipId) {
        return mapper.findAccessibleMenus(tenantId, membershipId).stream().map(this::menu).toList();
    }

    @Override
    public IdentityModels.Page<IdentityModels.User> findUsers(long tenantId, IdentityModels.UserQuery query) {
        List<IdentityModels.User> items = mapper.findUsers(tenantId, query, query.pageSize(), (query.page() - 1) * query.pageSize())
            .stream().map(this::user).toList();
        return new IdentityModels.Page<>(items, mapper.countUsers(tenantId, query));
    }

    @Override
    public List<Long> findUserRoleIds(long tenantId, long userId) {
        return List.copyOf(mapper.findUserRoleIds(tenantId, userId));
    }

    @Override
    public IdentityModels.Page<IdentityModels.Role> findRoles(long tenantId, IdentityModels.RoleQuery query) {
        List<IdentityModels.Role> items = mapper.findRoles(tenantId, query, query.pageSize(), (query.page() - 1) * query.pageSize())
            .stream().map(this::role).toList();
        return new IdentityModels.Page<>(items, mapper.countRoles(tenantId, query));
    }

    @Override
    public List<IdentityModels.Department> findDepartments(long tenantId) {
        return mapper.findDepartments(tenantId).stream().map(this::department).toList();
    }

    @Override
    public List<IdentityModels.Menu> findMenus(long tenantId) {
        return mapper.findMenus(tenantId).stream().map(this::menu).toList();
    }

    @Override
    @Transactional
    public long createUser(long tenantId, long operatorId, IdentityModels.UserCommand command) {
        requirePlatformTenant(tenantId);
        long userId = mapper.nextId();
        long membershipId = mapper.nextId();
        authorizeRoleReplacement(tenantId, operatorId, membershipId, Set.of(), command.roleIds());
        String username = normalize(command.username());
        String status = status(command.status());
        mapper.insertUser(userId, username, command.name().trim(), status, command.remark());
        if (mapper.insertMembership(membershipId, tenantId, userId, command.departmentId(), status) != 1) {
            throw new IdentityAdministrationService.ResourceNotFoundException("Department was not found");
        }
        mapper.insertCredential(userId, username, status);
        replaceRoles(tenantId, membershipId, command.roleIds(), operatorId);
        audit(tenantId, operatorId, "USER", userId, "CREATE", "user:create");
        return userId;
    }

    @Override
    @Transactional
    public void updateUser(long tenantId, long operatorId, long userId, IdentityModels.UserCommand command) {
        requirePlatformTenant(tenantId);
        Long membershipId = mapper.findMembershipId(tenantId, userId);
        if (membershipId == null) throw notFound("User");
        authorizeRoleReplacement(tenantId, operatorId, membershipId,
            Set.copyOf(mapper.findMembershipRoleIds(tenantId, membershipId)), command.roleIds());
        String state = status(command.status());
        if (command.status() == 0) protectAdministratorDeactivation(tenantId, userId);
        if (mapper.updateMembership(tenantId, userId, command.departmentId(), state, command.userVersion()) != 1) {
            throw new IdentityAdministrationService.OptimisticLockException();
        }
        mapper.updateUser(userId, command.name().trim(), state, command.remark());
        mapper.updateCredential(userId, normalize(command.username()), state);
        replaceRoles(tenantId, membershipId, command.roleIds(), operatorId);
        audit(tenantId, operatorId, "USER", userId, "UPDATE", "user:update");
    }

    @Override
    @Transactional
    public long updateUserStatus(long tenantId, long operatorId, long userId, int status, long version) {
        requirePlatformTenant(tenantId);
        if (status == 0) protectAdministratorDeactivation(tenantId, userId);
        String state = status(status);
        if (mapper.updateMembershipStatus(tenantId, userId, state, version) != 1) {
            throw new IdentityAdministrationService.OptimisticLockException();
        }
        audit(tenantId, operatorId, "USER", userId, "STATUS", "user:disable");
        return Optional.ofNullable(mapper.findUserVersion(tenantId, userId)).orElseThrow(() -> notFound("User"));
    }

    @Override
    @Transactional
    public void deleteUser(long tenantId, long operatorId, long userId) {
        requirePlatformTenant(tenantId);
        protectAdministratorDeactivation(tenantId, userId);
        if (mapper.terminateMembership(tenantId, userId) != 1) throw notFound("User");
        audit(tenantId, operatorId, "USER", userId, "DELETE", "user:delete");
    }

    @Override
    @Transactional
    public long createRole(long tenantId, long operatorId, IdentityModels.RoleCommand command) {
        requirePlatformTenant(tenantId);
        lockTenant(tenantId);
        long id = mapper.nextId();
        mapper.insertRole(id, tenantId, slug(command.name()) + '-' + id, command.name().trim(),
            status(command.status()), command.remark());
        replaceMenus(tenantId, id, command.menuIds());
        audit(tenantId, operatorId, "ROLE", id, "CREATE", "role:create");
        return id;
    }

    @Override
    @Transactional
    public void updateRole(long tenantId, long operatorId, long roleId, IdentityModels.RoleCommand command) {
        requirePlatformTenant(tenantId);
        lockTenant(tenantId);
        if (mapper.updateRole(tenantId, roleId, command.name().trim(), status(command.status()), command.remark()) != 1) {
            throw notFound("Role");
        }
        replaceMenus(tenantId, roleId, command.menuIds());
        mapper.bumpRoleMembers(tenantId, roleId);
        audit(tenantId, operatorId, "ROLE", roleId, "UPDATE", "role:update");
    }

    @Override
    @Transactional
    public void updateRoleStatus(long tenantId, long operatorId, long roleId, int status) {
        requirePlatformTenant(tenantId);
        lockTenant(tenantId);
        if (mapper.updateRoleStatus(tenantId, roleId, status(status)) != 1) throw notFound("Role");
        mapper.bumpRoleMembers(tenantId, roleId);
        audit(tenantId, operatorId, "ROLE", roleId, "STATUS", "role:update");
    }

    @Override
    @Transactional
    public void deleteRole(long tenantId, long operatorId, long roleId) {
        requirePlatformTenant(tenantId);
        lockTenant(tenantId);
        if (mapper.disableRole(tenantId, roleId) != 1) throw notFound("Role");
        mapper.bumpRoleMembers(tenantId, roleId);
        audit(tenantId, operatorId, "ROLE", roleId, "DELETE", "role:delete");
    }

    @Override
    @Transactional
    public long createDepartment(long tenantId, long operatorId, IdentityModels.DepartmentCommand command) {
        requirePlatformTenant(tenantId);
        lockTenant(tenantId);
        long id = mapper.nextId();
        if (!mapper.departmentParentAllowed(tenantId, id, command.parentId())) throw new IllegalArgumentException("Invalid department parent");
        mapper.insertDepartment(id, tenantId, command.parentId(), "dept-" + id, command.name().trim(),
            status(command.status()), command.remark());
        audit(tenantId, operatorId, "DEPARTMENT", id, "CREATE", "department:manage");
        return id;
    }

    @Override
    @Transactional
    public void updateDepartment(long tenantId, long operatorId, long id, IdentityModels.DepartmentCommand command) {
        requirePlatformTenant(tenantId);
        lockTenant(tenantId);
        if (!mapper.departmentParentAllowed(tenantId, id, command.parentId())) throw new IllegalArgumentException("Invalid department parent");
        if (mapper.updateDepartment(tenantId, id, command.parentId(), command.name().trim(),
            status(command.status()), command.remark()) != 1) throw notFound("Department");
        audit(tenantId, operatorId, "DEPARTMENT", id, "UPDATE", "department:manage");
    }

    @Override
    @Transactional
    public void deleteDepartment(long tenantId, long operatorId, long id) {
        requirePlatformTenant(tenantId);
        lockTenant(tenantId);
        if (mapper.departmentHasDependents(tenantId, id)) throw new IdentityAdministrationService.OptimisticLockException();
        if (mapper.disableDepartment(tenantId, id) != 1) throw notFound("Department");
        audit(tenantId, operatorId, "DEPARTMENT", id, "DELETE", "department:manage");
    }

    @Override
    @Transactional
    public long createMenu(long tenantId, long operatorId, IdentityModels.MenuCommand command) {
        requirePlatformTenant(tenantId);
        lockTenant(tenantId);
        long id = mapper.nextId();
        if (!mapper.menuParentAllowed(tenantId, id, command.parentId())) throw new IllegalArgumentException("Invalid menu parent");
        mapper.insertMenu(id, tenantId, command.parentId(), menuType(command.type()), command.name().trim(),
            command.path(), command.component(), command.redirect(), command.authCode(), json(command.metaJson()),
            status(command.status()));
        audit(tenantId, operatorId, "MENU", id, "CREATE", "menu:manage");
        return id;
    }

    @Override
    @Transactional
    public void updateMenu(long tenantId, long operatorId, long id, IdentityModels.MenuCommand command) {
        requirePlatformTenant(tenantId);
        lockTenant(tenantId);
        if (!mapper.menuParentAllowed(tenantId, id, command.parentId())) throw new IllegalArgumentException("Invalid menu parent");
        if (mapper.updateMenu(tenantId, id, command.parentId(), menuType(command.type()), command.name().trim(),
            command.path(), command.component(), command.redirect(), command.authCode(), json(command.metaJson()),
            status(command.status())) != 1) throw notFound("Menu");
        audit(tenantId, operatorId, "MENU", id, "UPDATE", "menu:manage");
    }

    @Override
    @Transactional
    public void deleteMenu(long tenantId, long operatorId, long id) {
        requirePlatformTenant(tenantId);
        lockTenant(tenantId);
        if (mapper.menuHasActiveChildren(tenantId, id)) throw new IdentityAdministrationService.OptimisticLockException();
        if (mapper.disableMenu(tenantId, id) != 1) throw notFound("Menu");
        audit(tenantId, operatorId, "MENU", id, "DELETE", "menu:manage");
    }

    @Override public boolean menuNameExists(long tenantId, String name, Long id) {
        return mapper.menuNameExists(tenantId, name, id);
    }
    @Override public boolean menuPathExists(long tenantId, String path, Long id) {
        return mapper.menuPathExists(tenantId, path, id);
    }

    private void authorizeRoleReplacement(long tenantId, long operatorMembershipId,
                                          long targetMembershipId, Set<Long> currentRoleIds,
                                          List<Long> requestedRoleIds) {
        lockTenant(tenantId);
        validateRoles(tenantId, requestedRoleIds);
        Map<Long, RoleAssignmentPolicy.RoleFacts> roleFacts = new HashMap<>();
        for (RoleAssignmentRow row : mapper.findRoleAssignmentFacts(tenantId)) {
            roleFacts.put(row.id(), new RoleAssignmentPolicy.RoleFacts(
                row.id(), row.assignable(), row.systemRole()));
        }
        roleAssignmentPolicy.validateReplacement(operatorMembershipId, targetMembershipId,
            currentRoleIds, Set.copyOf(requestedRoleIds),
            Set.copyOf(mapper.findMembershipRoleIds(tenantId, operatorMembershipId)), roleFacts);
    }

    private void protectAdministratorDeactivation(long tenantId, long userId) {
        lockTenant(tenantId);
        Long membershipId = mapper.findMembershipId(tenantId, userId);
        if (membershipId == null) throw notFound("User");
        roleAssignmentPolicy.validateDeactivation(
            mapper.membershipHasActiveSystemRole(tenantId, membershipId),
            mapper.hasOtherActiveSystemAdministrator(tenantId, membershipId));
    }

    private void lockTenant(long tenantId) {
        if (mapper.lockTenantForAdministration(tenantId) == null) throw notFound("Tenant");
    }

    private void replaceRoles(long tenantId, long membershipId, List<Long> ids, long operatorId) {
        mapper.deleteMembershipRoles(tenantId, membershipId);
        if (!ids.isEmpty()) mapper.insertMembershipRoles(tenantId, membershipId, ids, operatorId);
    }
    private void requirePlatformTenant(long tenantId) {
        if (!mapper.isActivePlatformTenant(tenantId)) throw new SecurityException("Platform tenant is required");
    }
    private void replaceMenus(long tenantId, long roleId, List<Long> ids) {
        mapper.deleteRoleMenus(tenantId, roleId);
        if (!ids.isEmpty()) mapper.insertRoleMenus(tenantId, roleId, ids);
    }
    private void validateRoles(long tenantId, List<Long> ids) {
        if (!ids.isEmpty() && mapper.countActiveRoles(tenantId, ids) != ids.stream().distinct().count()) {
            throw new IdentityAdministrationService.ResourceNotFoundException("One or more roles were not found");
        }
    }
    private void audit(long tenantId, long operatorId, String type, long target, String action, String permission) {
        mapper.insertAudit(tenantId, operatorId, type, Long.toString(target), action, permission, traceIdSupplier.get());
    }

    private IdentityModels.User user(Map<String, Object> r) {
        return new IdentityModels.User(number(r,"id"), number(r,"membershipId"), text(r,"username"), text(r,"name"),
            nullableNumber(r,"departmentId"), nullableText(r,"departmentName"), longs(r.get("roleIds")),
            strings(r.get("roleNames")), integer(r,"status"), number(r,"userVersion"), nullableText(r,"remark"),
            instant(r.get("createdAt")));
    }
    private IdentityModels.Role role(Map<String, Object> r) {
        return new IdentityModels.Role(number(r,"id"), text(r,"name"), longs(r.get("menuIds")), integer(r,"status"),
            nullableText(r,"remark"), instant(r.get("createdAt")));
    }
    private IdentityModels.Department department(Map<String, Object> r) {
        return new IdentityModels.Department(number(r,"id"), nullableNumber(r,"parentId"), text(r,"name"),
            integer(r,"status"), nullableText(r,"remark"), instant(r.get("createdAt")));
    }
    private IdentityModels.Menu menu(Map<String, Object> r) {
        return new IdentityModels.Menu(number(r,"id"), nullableNumber(r,"parentId"), menuTypeForApi(text(r,"type")),
            text(r,"name"), nullableText(r,"path"), nullableText(r,"component"), nullableText(r,"redirect"),
            nullableText(r,"authCode"), text(r,"metaJson"), integer(r,"status"));
    }

    private static RuntimeException notFound(String label) {
        return new IdentityAdministrationService.ResourceNotFoundException(label + " was not found");
    }
    private static String normalize(String s) { return s.trim().toLowerCase(Locale.ROOT); }
    private static String blankToNull(String s) { return s == null || s.isBlank() ? null : s.trim(); }
    private static String status(int status) { return status == 1 ? "ACTIVE" : "DISABLED"; }
    private static String json(String value) { return value == null || value.isBlank() ? "{}" : value; }
    private static String slug(String value) {
        String slug = value.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return slug.isBlank() ? "role" : slug;
    }
    private static String menuType(String value) {
        return switch (value.toLowerCase(Locale.ROOT)) {
            case "catalog" -> "DIRECTORY"; case "menu" -> "PAGE"; case "embedded" -> "EMBEDDED";
            case "link" -> "LINK"; case "button" -> "BUTTON"; default -> throw new IllegalArgumentException("Invalid menu type");
        };
    }
    private static String menuTypeForApi(String value) {
        return switch (value) { case "DIRECTORY" -> "catalog"; case "PAGE" -> "menu";
            case "EMBEDDED" -> "embedded"; case "LINK" -> "link"; case "BUTTON" -> "button";
            default -> throw new IllegalStateException("Unknown menu type"); };
    }
    private static long number(Map<String,Object> r,String k) { return ((Number) r.get(k)).longValue(); }
    private static int integer(Map<String,Object> r,String k) { return ((Number) r.get(k)).intValue(); }
    private static Long nullableNumber(Map<String,Object> r,String k) { Object v=r.get(k); return v==null?null:((Number)v).longValue(); }
    private static String text(Map<String,Object> r,String k) { Object v=r.get(k); return v==null?"":v.toString(); }
    private static String nullableText(Map<String,Object> r,String k) { Object v=r.get(k); return v==null?null:v.toString(); }
    private static List<String> strings(Object v) { if(v==null||v.toString().isBlank())return List.of(); return Arrays.stream(v.toString().split(",")).toList(); }
    private static List<Long> longs(Object v) { return strings(v).stream().map(Long::valueOf).toList(); }
    private static Instant instant(Object v) {
        if(v instanceof Instant i)return i; if(v instanceof OffsetDateTime o)return o.toInstant();
        if(v instanceof Timestamp t)return t.toInstant(); throw new IllegalStateException("Unsupported timestamp value");
    }
}
