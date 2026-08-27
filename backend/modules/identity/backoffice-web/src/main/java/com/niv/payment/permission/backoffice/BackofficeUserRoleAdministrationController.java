package com.niv.payment.permission.backoffice;

import com.niv.payment.permission.domain.AdministrationActor;
import com.niv.payment.permission.domain.AuthorizationSubject;
import com.niv.payment.permission.service.IdentityAdministrationService;
import com.niv.payment.permission.service.IdentityModels;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/** Same-tenant User and Role administration shared by MERCHANT and AGENT roots. */
@RestController
@RequestMapping("/api/system")
final class BackofficeUserRoleAdministrationController {
    private final IdentityAdministrationService identities;
    private final ObjectMapper json;
    private final ZoneId queryZone;

    BackofficeUserRoleAdministrationController(
        IdentityAdministrationService identities,
        ObjectMapper json,
        @Value("${payment.time-zone}") String timeZone) {
        this.identities = identities;
        this.json = json;
        this.queryZone = ZoneId.of(timeZone);
    }

    @GetMapping("/user/list")
    BackofficeApiResponse<PageResponse<UserResponse>> users(
        @RequestParam Map<String, String> query, HttpServletRequest request) {
        AuthorizationSubject subject = BackofficeSubjects.current(request);
        IdentityModels.UserQuery criteria = new IdentityModels.UserQuery(
            query.get("username"), query.get("name"), nullableLong(query.get("id")),
            nullableInteger(query.get("status")), nullableLong(query.get("deptId")),
            parseTime(query.get("startTime"), false), parseTime(query.get("endTime"), true),
            integer(query.get("page"), 1), integer(query.get("pageSize"), 20));
        IdentityModels.Page<IdentityModels.User> result =
            identities.users(subject.tenantId(), criteria);
        return BackofficeApiResponse.success(new PageResponse<>(
            result.items().stream().map(BackofficeUserRoleAdministrationController::user).toList(),
            result.total()));
    }

    @PostMapping("/user")
    BackofficeApiResponse<IdResponse> createUser(
        @Valid @RequestBody UserCreateRequest body, HttpServletRequest request) {
        AuthorizationSubject subject = BackofficeSubjects.current(request);
        long id = identities.createUser(subject.tenantId(), actor(subject), body.command());
        return BackofficeApiResponse.success(new IdResponse(Long.toString(id)));
    }

    @PutMapping("/user/{id}")
    BackofficeApiResponse<Void> updateUser(
        @PathVariable long id,
        @Valid @RequestBody MembershipUpdateRequest body,
        HttpServletRequest request) {
        AuthorizationSubject subject = BackofficeSubjects.current(request);
        identities.updateUser(subject.tenantId(), actor(subject), id, body.command());
        return BackofficeApiResponse.success(null);
    }

    @PutMapping("/user/{id}/roles")
    BackofficeApiResponse<UserStatusResponse> replaceUserRoles(
        @PathVariable long id,
        @Valid @RequestBody UserRoleAssignmentRequest body,
        HttpServletRequest request) {
        AuthorizationSubject subject = BackofficeSubjects.current(request);
        long version = identities.replaceUserRoles(
            subject.tenantId(), actor(subject), id, body.roles(), body.userVersion());
        return BackofficeApiResponse.success(new UserStatusResponse(version));
    }

    @PatchMapping("/user/{id}/status")
    BackofficeApiResponse<UserStatusResponse> updateUserStatus(
        @PathVariable long id,
        @Valid @RequestBody UserStatusRequest body,
        HttpServletRequest request) {
        AuthorizationSubject subject = BackofficeSubjects.current(request);
        long version = identities.updateUserStatus(
            subject.tenantId(), actor(subject), id, body.status(), body.userVersion());
        return BackofficeApiResponse.success(new UserStatusResponse(version));
    }

    @PostMapping("/user/{id}/password/reset")
    BackofficeApiResponse<PasswordResetResponse> resetUserPassword(
        @PathVariable long id,
        @Valid @RequestBody PasswordResetRequest body,
        HttpServletRequest request) {
        AuthorizationSubject subject = BackofficeSubjects.current(request);
        IdentityModels.PasswordResetResult result = identities.resetUserPassword(
            subject.tenantId(), actor(subject), id, body.credentialVersion(), body.password());
        return BackofficeApiResponse.success(new PasswordResetResponse(
            result.credentialVersion(), result.identityVersion(), result.userVersion()));
    }

    @DeleteMapping("/user/{id}")
    BackofficeApiResponse<Void> deleteUser(
        @PathVariable long id,
        @RequestParam("expectedVersion") @Min(0) long expectedVersion,
        HttpServletRequest request) {
        AuthorizationSubject subject = BackofficeSubjects.current(request);
        identities.deleteUser(subject.tenantId(), actor(subject), id, expectedVersion);
        return BackofficeApiResponse.success(null);
    }

    @GetMapping("/role/list")
    BackofficeApiResponse<PageResponse<RoleResponse>> roles(
        @RequestParam Map<String, String> query, HttpServletRequest request) {
        AuthorizationSubject subject = BackofficeSubjects.current(request);
        IdentityModels.RoleQuery criteria = new IdentityModels.RoleQuery(
            query.get("name"), nullableLong(query.get("id")),
            nullableInteger(query.get("status")), query.get("remark"),
            parseTime(query.get("startTime"), false), parseTime(query.get("endTime"), true),
            integer(query.get("page"), 1), integer(query.get("pageSize"), 20));
        IdentityModels.Page<IdentityModels.Role> result =
            identities.roles(subject.tenantId(), criteria);
        return BackofficeApiResponse.success(new PageResponse<>(
            result.items().stream().map(BackofficeUserRoleAdministrationController::role).toList(),
            result.total()));
    }

    @GetMapping("/role/{id}/members")
    BackofficeApiResponse<PageResponse<UserResponse>> roleMembers(
        @PathVariable long id,
        @RequestParam boolean assigned,
        @RequestParam Map<String, String> query,
        HttpServletRequest request) {
        AuthorizationSubject subject = BackofficeSubjects.current(request);
        IdentityModels.UserQuery criteria = new IdentityModels.UserQuery(
            query.get("username"), query.get("name"), nullableLong(query.get("userId")),
            nullableInteger(query.get("status")), null,
            parseTime(query.get("startTime"), false), parseTime(query.get("endTime"), true),
            integer(query.get("page"), 1), integer(query.get("pageSize"), 20));
        IdentityModels.Page<IdentityModels.User> result = identities.roleMembers(
            subject.tenantId(), id, assigned, criteria);
        return BackofficeApiResponse.success(new PageResponse<>(
            result.items().stream().map(BackofficeUserRoleAdministrationController::user).toList(),
            result.total()));
    }

    @PatchMapping("/role/{id}/members")
    BackofficeApiResponse<Void> updateRoleMembers(
        @PathVariable long id,
        @Valid @RequestBody RoleMembersRequest body,
        HttpServletRequest request) {
        AuthorizationSubject subject = BackofficeSubjects.current(request);
        identities.updateRoleMembers(subject.tenantId(), actor(subject), id, body.changes());
        return BackofficeApiResponse.success(null);
    }

    @PostMapping("/role")
    BackofficeApiResponse<IdResponse> createRole(
        @Valid @RequestBody RoleRequest body, HttpServletRequest request) {
        AuthorizationSubject subject = BackofficeSubjects.current(request);
        long id = identities.createRole(subject.tenantId(), actor(subject), body.command());
        return BackofficeApiResponse.success(new IdResponse(Long.toString(id)));
    }

    @PutMapping("/role/{id}")
    BackofficeApiResponse<Void> updateRole(
        @PathVariable long id,
        @Valid @RequestBody RoleUpdateRequest body,
        HttpServletRequest request) {
        AuthorizationSubject subject = BackofficeSubjects.current(request);
        identities.updateRole(
            subject.tenantId(), actor(subject), id, body.command(), body.expectedVersion());
        return BackofficeApiResponse.success(null);
    }

    @PatchMapping("/role/{id}/status")
    BackofficeApiResponse<Void> updateRoleStatus(
        @PathVariable long id,
        @Valid @RequestBody RoleStatusRequest body,
        HttpServletRequest request) {
        AuthorizationSubject subject = BackofficeSubjects.current(request);
        identities.updateRoleStatus(
            subject.tenantId(), actor(subject), id, body.status(), body.expectedVersion());
        return BackofficeApiResponse.success(null);
    }

    @DeleteMapping("/role/{id}")
    BackofficeApiResponse<Void> deleteRole(
        @PathVariable long id,
        @RequestParam("expectedVersion") @Min(0) long expectedVersion,
        HttpServletRequest request) {
        AuthorizationSubject subject = BackofficeSubjects.current(request);
        identities.deleteRole(subject.tenantId(), actor(subject), id, expectedVersion);
        return BackofficeApiResponse.success(null);
    }

    @GetMapping("/dept/list")
    BackofficeApiResponse<List<DepartmentResponse>> departments(
        @RequestParam(value = "selectableOnly", defaultValue = "false") boolean selectableOnly,
        HttpServletRequest request) {
        AuthorizationSubject subject = BackofficeSubjects.current(request);
        return BackofficeApiResponse.success(departmentTree(
            identities.departments(subject.tenantId(), selectableOnly)));
    }

    @GetMapping("/menu/list")
    BackofficeApiResponse<List<MenuResponse>> menus(
        @RequestParam(value = "selectableOnly", defaultValue = "false") boolean selectableOnly,
        HttpServletRequest request) {
        AuthorizationSubject subject = BackofficeSubjects.current(request);
        return BackofficeApiResponse.success(menuTree(
            identities.menus(subject.tenantId(), selectableOnly)));
    }

    private static UserResponse user(IdentityModels.User user) {
        return new UserResponse(
            Long.toString(user.id()), user.username(), user.name(), id(user.departmentId()),
            user.departmentName(), user.roleIds().stream().map(String::valueOf).toList(),
            user.roleNames(), user.status(), user.identityStatus(), user.userVersion(),
            user.identityVersion(), user.credentialVersion(), user.remark(),
            user.createdAt().toString());
    }

    private static RoleResponse role(IdentityModels.Role role) {
        return new RoleResponse(
            Long.toString(role.id()), role.name(),
            role.menuIds().stream().map(String::valueOf).toList(), role.status(),
            role.remark(), role.rowVersion(), role.systemRole(), role.assignable(),
            role.createdAt().toString());
    }

    private List<DepartmentResponse> departmentTree(List<IdentityModels.Department> rows) {
        return tree(rows, IdentityModels.Department::id, IdentityModels.Department::parentId,
            (item, children) -> new DepartmentResponse(
                Long.toString(item.id()), id(item.parentId()), item.name(), item.status(),
                item.remark(), item.rowVersion(), item.systemManaged(),
                item.createdAt().toString(), children));
    }

    private List<MenuResponse> menuTree(List<IdentityModels.Menu> rows) {
        return tree(rows, IdentityModels.Menu::id, IdentityModels.Menu::parentId,
            (item, children) -> new MenuResponse(
                Long.toString(item.id()), id(item.parentId()), item.type(), item.name(),
                item.path(), item.component(), item.redirect(), item.authCode(),
                readMeta(item.metaJson()), item.status(), item.rowVersion(),
                item.systemManaged(), children));
    }

    private Map<String, Object> readMeta(String value) {
        try {
            return json.readValue(value, new TypeReference<>() { });
        } catch (JacksonException exception) {
            throw new IllegalStateException("Stored menu metadata is invalid", exception);
        }
    }

    private static <T, R> List<R> tree(
        List<T> rows, Function<T, Long> id, Function<T, Long> parent,
        TreeFactory<T, R> factory) {
        Map<Long, List<T>> grouped = new LinkedHashMap<>();
        Set<Long> ids = rows.stream().map(id).collect(java.util.stream.Collectors.toSet());
        rows.forEach(item -> grouped.computeIfAbsent(parent.apply(item), ignored ->
            new ArrayList<>()).add(item));
        return rows.stream()
            .filter(item -> parent.apply(item) == null || !ids.contains(parent.apply(item)))
            .map(item -> node(item, id, grouped, factory))
            .toList();
    }

    private static <T, R> R node(
        T item, Function<T, Long> id, Map<Long, List<T>> grouped,
        TreeFactory<T, R> factory) {
        List<R> children = grouped.getOrDefault(id.apply(item), List.of()).stream()
            .map(child -> node(child, id, grouped, factory))
            .toList();
        return factory.create(item, children);
    }

    private Instant parseTime(String value, boolean endOfDay) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            // Continue with the supported local formats.
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
            // Continue with the supported local formats.
        }
        try {
            return LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                .atZone(queryZone).toInstant();
        } catch (DateTimeParseException ignored) {
            // Continue with the date-only format.
        }
        try {
            LocalDate date = LocalDate.parse(value);
            return endOfDay
                ? date.plusDays(1).atStartOfDay(queryZone).minusNanos(1).toInstant()
                : date.atStartOfDay(queryZone).toInstant();
        } catch (DateTimeParseException invalid) {
            throw new IllegalArgumentException("Invalid time query", invalid);
        }
    }

    private static AdministrationActor actor(AuthorizationSubject subject) {
        return AdministrationActor.from(subject);
    }

    private static String id(Long value) {
        return value == null ? "0" : value.toString();
    }

    private static int integer(String value, int fallback) {
        return value == null ? fallback : Integer.parseInt(value);
    }

    private static Integer nullableInteger(String value) {
        return value == null || value.isBlank() ? null : Integer.valueOf(value);
    }

    private static Long nullableLong(String value) {
        return value == null || value.isBlank() ? null : Long.valueOf(value);
    }

    private static long longId(String value) {
        return Long.parseLong(value);
    }

    @FunctionalInterface
    interface TreeFactory<T, R> {
        R create(T item, List<R> children);
    }

    record PageResponse<T>(List<T> items, long total) { }
    record IdResponse(String id) { }
    record UserStatusResponse(long userVersion) { }
    record PasswordResetResponse(
        long credentialVersion, long identityVersion, long userVersion) { }
    record UserResponse(
        String id, String username, String name, String deptId, String deptName,
        List<String> roleIds, List<String> roleNames, int status, String identityStatus,
        long userVersion, long identityVersion, long credentialVersion, String remark,
        String createTime) { }
    record RoleResponse(
        String id, String name, List<String> menuIds, int status, String remark,
        long rowVersion, boolean systemRole, boolean assignable, String createTime) { }
    record DepartmentResponse(
        String id, String pid, String name, int status, String remark, long rowVersion,
        boolean systemManaged, String createTime, List<DepartmentResponse> children) { }
    record MenuResponse(
        String id, String pid, String type, String name, String path, String component,
        String redirect, String authCode, Map<String, Object> meta, int status,
        long rowVersion, boolean systemManaged, List<MenuResponse> children) { }

    record UserCreateRequest(
        @NotBlank @Size(max = 100) String username,
        @NotBlank @Size(max = 128) String name,
        @NotBlank @Pattern(regexp = "[1-9][0-9]*") String deptId,
        @NotNull @Size(max = 256)
        List<@Pattern(regexp = "[1-9][0-9]{0,18}") String> roleIds,
        @NotNull @Min(0) @Max(1) Integer status,
        @Size(max = 500) String remark) {
        IdentityModels.UserCreateCommand command() {
            return new IdentityModels.UserCreateCommand(
                username, name, longId(deptId), roleIds.stream().map(
                    BackofficeUserRoleAdministrationController::longId).toList(),
                status, remark);
        }
    }

    record MembershipUpdateRequest(
        @Size(max = 100) String username,
        @Size(max = 128) String name,
        @NotBlank @Pattern(regexp = "[1-9][0-9]*") String deptId,
        @NotNull @Size(max = 256)
        List<@Pattern(regexp = "[1-9][0-9]{0,18}") String> roleIds,
        @NotNull @Min(0) @Max(1) Integer status,
        @NotNull @Min(0) Long userVersion,
        @Min(0) Long identityVersion,
        @Min(0) Long credentialVersion,
        @Size(max = 500) String remark) {
        IdentityModels.MembershipUpdateCommand command() {
            List<Long> roles = roleIds.stream()
                .map(BackofficeUserRoleAdministrationController::longId).toList();
            if (identityVersion == null && credentialVersion == null) {
                return new IdentityModels.MembershipUpdateCommand(
                    longId(deptId), roles, status, userVersion);
            }
            return new IdentityModels.MembershipUpdateCommand(
                username, name, longId(deptId), roles, status, userVersion,
                identityVersion, credentialVersion, remark);
        }
    }

    record UserStatusRequest(
        @NotNull @Min(0) @Max(1) Integer status,
        @NotNull @Min(0) Long userVersion) { }
    record UserRoleAssignmentRequest(
        @NotNull @Size(max = 256)
        List<@Pattern(regexp = "[1-9][0-9]{0,18}") String> roleIds,
        @NotNull @Min(0) Long userVersion) {
        List<Long> roles() {
            return roleIds.stream()
                .map(BackofficeUserRoleAdministrationController::longId).toList();
        }
    }
    record PasswordResetRequest(
        @NotNull @Min(0) Long credentialVersion,
        String password) {
        @Override
        public String toString() {
            return "PasswordResetRequest[credentialVersion=" + credentialVersion
                + ", password=<redacted>]";
        }
    }
    record RoleRequest(
        @NotBlank @Size(max = 128) String name,
        @NotNull @Size(max = 2048)
        List<@Pattern(regexp = "[1-9][0-9]{0,18}") String> menuIds,
        @NotNull @Min(0) @Max(1) Integer status,
        @Size(max = 500) String remark) {
        IdentityModels.RoleCommand command() {
            return new IdentityModels.RoleCommand(
                name, menuIds.stream().map(
                    BackofficeUserRoleAdministrationController::longId).toList(),
                status, remark);
        }
    }
    record RoleUpdateRequest(
        @NotBlank @Size(max = 128) String name,
        @NotNull @Size(max = 2048)
        List<@Pattern(regexp = "[1-9][0-9]{0,18}") String> menuIds,
        @NotNull @Min(0) @Max(1) Integer status,
        @Size(max = 500) String remark,
        @NotNull @Min(0) Long expectedVersion) {
        IdentityModels.RoleCommand command() {
            return new IdentityModels.RoleCommand(
                name, menuIds.stream().map(
                    BackofficeUserRoleAdministrationController::longId).toList(),
                status, remark);
        }
    }
    record RoleStatusRequest(
        @NotNull @Min(0) @Max(1) Integer status,
        @NotNull @Min(0) Long expectedVersion) { }
    record RoleMembersRequest(
        @NotNull @Size(max = 200) List<@Valid RoleMemberRequest> members) {
        List<IdentityModels.RoleMemberChange> changes() {
            return members.stream().map(RoleMemberRequest::change).toList();
        }
    }
    record RoleMemberRequest(
        @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String userId,
        @NotNull @Min(0) Long userVersion,
        @NotNull Boolean assigned) {
        IdentityModels.RoleMemberChange change() {
            return new IdentityModels.RoleMemberChange(longId(userId), userVersion, assigned);
        }
    }
}
