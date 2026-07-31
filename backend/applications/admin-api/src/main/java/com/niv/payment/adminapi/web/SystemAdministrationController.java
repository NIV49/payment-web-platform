package com.niv.payment.adminapi.web;

import com.niv.payment.adminapi.config.AdminAuthorizationEnforcer;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
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

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

@RestController
@RequestMapping("/api/system")
public class SystemAdministrationController {
    private final IdentityAdministrationService identities;
    private final ObjectMapper json;
    private final AdminAuthorizationEnforcer authorization;
    private final VbenMenuContract menuContract;
    private final ZoneId queryZone;

    public SystemAdministrationController(IdentityAdministrationService identities, ObjectMapper json,
                                          VbenMenuContract menuContract,
                                          AdminAuthorizationEnforcer authorization,
                                          @Value("${payment.time-zone}") String timeZone) {
        this.identities = identities;
        this.json = json;
        this.menuContract = menuContract;
        this.authorization = authorization;
        this.queryZone = ZoneId.of(timeZone);
    }

    @GetMapping("/user/list")
    ApiResponse<PageResponse<UserResponse>> users(@RequestParam Map<String, String> query, HttpServletRequest request) {
        AuthorizationSubject subject = AuthUserMenuController.subject(request);
        int page = integer(query.get("page"), 1); int size = integer(query.get("pageSize"), 20);
        IdentityModels.UserQuery criteria = new IdentityModels.UserQuery(query.get("username"), query.get("name"),
            nullableLong(query.get("id")), nullableInteger(query.get("status")), nullableLong(query.get("deptId")),
            parseTime(query.get("startTime"), false), parseTime(query.get("endTime"), true), page, size);
        IdentityModels.Page<IdentityModels.User> result = identities.users(subject.tenantId(), criteria);
        return ApiResponse.success(new PageResponse<>(result.items().stream().map(this::user).toList(), result.total()));
    }

    @PostMapping("/user")
    ApiResponse<IdResponse> createUser(@Valid @RequestBody UserCreateRequest body, HttpServletRequest request) {
        AuthorizationSubject subject = AuthUserMenuController.subject(request);
        if (!body.roleIds().isEmpty()) requirePermission(subject, "user:assign-role");
        long id = identities.createUser(subject.tenantId(), actor(subject), body.command());
        return ApiResponse.success(new IdResponse(Long.toString(id)));
    }

    @PutMapping("/user/{id}")
    ApiResponse<Void> updateUser(@PathVariable("id") long id, @Valid @RequestBody MembershipUpdateRequest body,
                                 HttpServletRequest request) {
        AuthorizationSubject subject = AuthUserMenuController.subject(request);
        identities.updateUser(subject.tenantId(), actor(subject), id, body.command());
        return ApiResponse.success(null);
    }

    @PatchMapping("/user/{id}/status")
    ApiResponse<UserStatusResponse> updateUserStatus(@PathVariable("id") long id,
        @Valid @RequestBody UserStatusRequest body, HttpServletRequest request) {
        AuthorizationSubject subject = AuthUserMenuController.subject(request);
        long version = identities.updateUserStatus(subject.tenantId(), actor(subject), id,
            body.status(), body.userVersion());
        return ApiResponse.success(new UserStatusResponse(version));
    }

    @DeleteMapping("/user/{id}")
    ApiResponse<Void> deleteUser(@PathVariable("id") long id,
                                 @RequestParam("expectedVersion") @Min(0) long expectedVersion,
                                 HttpServletRequest request) {
        AuthorizationSubject subject = AuthUserMenuController.subject(request);
        identities.deleteUser(subject.tenantId(), actor(subject), id, expectedVersion);
        return ApiResponse.success(null);
    }

    @GetMapping("/role/list")
    ApiResponse<PageResponse<RoleResponse>> roles(@RequestParam Map<String, String> query, HttpServletRequest request) {
        AuthorizationSubject subject = AuthUserMenuController.subject(request);
        IdentityModels.RoleQuery criteria = new IdentityModels.RoleQuery(query.get("name"), nullableLong(query.get("id")),
            nullableInteger(query.get("status")), query.get("remark"), parseTime(query.get("startTime"), false),
            parseTime(query.get("endTime"), true), integer(query.get("page"), 1), integer(query.get("pageSize"), 20));
        IdentityModels.Page<IdentityModels.Role> result = identities.roles(subject.tenantId(), criteria);
        return ApiResponse.success(new PageResponse<>(result.items().stream().map(this::role).toList(), result.total()));
    }

    @PostMapping("/role")
    ApiResponse<IdResponse> createRole(@Valid @RequestBody RoleRequest body, HttpServletRequest request) {
        AuthorizationSubject subject = AuthUserMenuController.subject(request);
        return ApiResponse.success(new IdResponse(Long.toString(identities.createRole(subject.tenantId(),
            actor(subject), body.command()))));
    }

    @PutMapping("/role/{id}")
    ApiResponse<Void> updateRole(@PathVariable("id") long id, @Valid @RequestBody RoleUpdateRequest body,
                                 HttpServletRequest request) {
        AuthorizationSubject subject = AuthUserMenuController.subject(request);
        identities.updateRole(subject.tenantId(), actor(subject), id, body.command(), body.expectedVersion());
        return ApiResponse.success(null);
    }

    @DeleteMapping("/role/{id}")
    ApiResponse<Void> deleteRole(@PathVariable("id") long id,
                                 @RequestParam("expectedVersion") @Min(0) long expectedVersion,
                                 HttpServletRequest request) {
        AuthorizationSubject subject = AuthUserMenuController.subject(request);
        identities.deleteRole(subject.tenantId(), actor(subject), id, expectedVersion);
        return ApiResponse.success(null);
    }

    @PatchMapping("/role/{id}/status")
    ApiResponse<Void> updateRoleStatus(@PathVariable("id") long id, @Valid @RequestBody RoleStatusRequest body,
                                       HttpServletRequest request) {
        AuthorizationSubject subject = AuthUserMenuController.subject(request);
        identities.updateRoleStatus(subject.tenantId(), actor(subject), id, body.status(), body.expectedVersion());
        return ApiResponse.success(null);
    }

    @GetMapping("/dept/list")
    ApiResponse<List<DepartmentResponse>> departments(HttpServletRequest request) {
        AuthorizationSubject subject = AuthUserMenuController.subject(request);
        return ApiResponse.success(departmentTree(identities.departments(subject.tenantId())));
    }

    @PostMapping("/dept")
    ApiResponse<IdResponse> createDepartment(@Valid @RequestBody DepartmentRequest body, HttpServletRequest request) {
        AuthorizationSubject subject = AuthUserMenuController.subject(request);
        return ApiResponse.success(new IdResponse(Long.toString(identities.createDepartment(subject.tenantId(),
            actor(subject), body.command()))));
    }

    @PutMapping("/dept/{id}")
    ApiResponse<Void> updateDepartment(@PathVariable("id") long id,
                                       @Valid @RequestBody DepartmentUpdateRequest body,
                                       HttpServletRequest request) {
        AuthorizationSubject subject = AuthUserMenuController.subject(request);
        identities.updateDepartment(subject.tenantId(), actor(subject), id, body.command(), body.expectedVersion());
        return ApiResponse.success(null);
    }

    @DeleteMapping("/dept/{id}")
    ApiResponse<Void> deleteDepartment(@PathVariable("id") long id,
                                       @RequestParam("expectedVersion") @Min(0) long expectedVersion,
                                       HttpServletRequest request) {
        AuthorizationSubject subject = AuthUserMenuController.subject(request);
        identities.deleteDepartment(subject.tenantId(), actor(subject), id, expectedVersion);
        return ApiResponse.success(null);
    }

    @GetMapping("/menu/list")
    ApiResponse<List<MenuResponse>> menus(HttpServletRequest request) {
        AuthorizationSubject subject = AuthUserMenuController.subject(request);
        return ApiResponse.success(menuTree(identities.menus(subject.tenantId())));
    }

    @GetMapping("/menu/name-exists")
    ApiResponse<Boolean> menuNameExists(@RequestParam("name") String name, @RequestParam(value = "id", required = false) Long id,
                                        HttpServletRequest request) {
        AuthorizationSubject subject = AuthUserMenuController.subject(request);
        return ApiResponse.success(identities.menuNameExists(subject.tenantId(), name, id));
    }

    @GetMapping("/menu/path-exists")
    ApiResponse<Boolean> menuPathExists(@RequestParam("path") String path, @RequestParam(value = "id", required = false) Long id,
                                        HttpServletRequest request) {
        AuthorizationSubject subject = AuthUserMenuController.subject(request);
        return ApiResponse.success(identities.menuPathExists(subject.tenantId(), path, id));
    }

    @PostMapping("/menu")
    ApiResponse<IdResponse> createMenu(@Valid @RequestBody MenuRequest body, HttpServletRequest request) {
        AuthorizationSubject subject = AuthUserMenuController.subject(request);
        body.validate(menuContract);
        return ApiResponse.success(new IdResponse(Long.toString(identities.createMenu(subject.tenantId(),
            actor(subject), body.command(json)))));
    }

    @PutMapping("/menu/{id}")
    ApiResponse<Void> updateMenu(@PathVariable("id") long id, @Valid @RequestBody MenuUpdateRequest body,
                                 HttpServletRequest request) {
        AuthorizationSubject subject = AuthUserMenuController.subject(request);
        body.validate(menuContract);
        identities.updateMenu(subject.tenantId(), actor(subject), id, body.command(json), body.expectedVersion());
        return ApiResponse.success(null);
    }

    @DeleteMapping("/menu/{id}")
    ApiResponse<Void> deleteMenu(@PathVariable("id") long id,
                                 @RequestParam("expectedVersion") @Min(0) long expectedVersion,
                                 HttpServletRequest request) {
        AuthorizationSubject subject = AuthUserMenuController.subject(request);
        identities.deleteMenu(subject.tenantId(), actor(subject), id, expectedVersion);
        return ApiResponse.success(null);
    }

    private UserResponse user(IdentityModels.User u) {
        return new UserResponse(Long.toString(u.id()), u.username(), u.name(), id(u.departmentId()), u.departmentName(),
            u.roleIds().stream().map(String::valueOf).toList(), u.roleNames(), u.status(), u.identityStatus(),
            u.userVersion(), u.remark(), u.createdAt().toString());
    }
    private RoleResponse role(IdentityModels.Role r) {
        return new RoleResponse(Long.toString(r.id()), r.name(), r.menuIds().stream().map(String::valueOf).toList(),
            r.status(), r.remark(), r.rowVersion(), r.systemRole(), r.assignable(), r.createdAt().toString());
    }
    private List<DepartmentResponse> departmentTree(List<IdentityModels.Department> rows) {
        return tree(rows, IdentityModels.Department::id, IdentityModels.Department::parentId,
            (item, children) -> new DepartmentResponse(Long.toString(item.id()), id(item.parentId()), item.name(),
                item.status(), item.remark(), item.rowVersion(), item.createdAt().toString(), children));
    }
    private List<MenuResponse> menuTree(List<IdentityModels.Menu> rows) {
        return tree(rows, IdentityModels.Menu::id, IdentityModels.Menu::parentId, (item, children) -> {
            Map<String,Object> meta;
            try { meta=json.readValue(item.metaJson(), new TypeReference<>(){}); }
            catch (JacksonException e) { throw new IllegalStateException("Stored menu metadata is invalid", e); }
            return new MenuResponse(Long.toString(item.id()), id(item.parentId()), item.type(), item.name(), item.path(),
                item.component(), item.redirect(), item.authCode(), meta, item.status(), item.rowVersion(), children);
        });
    }

    private static <T,R> List<R> tree(List<T> rows, Function<T,Long> id, Function<T,Long> parent,
                                      TreeFactory<T,R> factory) {
        Map<Long,List<T>> grouped=new LinkedHashMap<>(); Set<Long> ids=rows.stream().map(id).collect(java.util.stream.Collectors.toSet());
        rows.forEach(item -> grouped.computeIfAbsent(parent.apply(item), ignored -> new ArrayList<>()).add(item));
        return rows.stream().filter(item -> parent.apply(item)==null || !ids.contains(parent.apply(item)))
            .map(item -> node(item,id,grouped,factory)).toList();
    }
    private static <T,R> R node(T item, Function<T,Long> id, Map<Long,List<T>> grouped, TreeFactory<T,R> factory) {
        List<R> children=grouped.getOrDefault(id.apply(item),List.of()).stream().map(c->node(c,id,grouped,factory)).toList();
        return factory.create(item,children);
    }

    private static String id(Long value) { return value == null ? "0" : value.toString(); }
    private static int integer(String value,int fallback) { return value==null?fallback:Integer.parseInt(value); }
    private static Integer nullableInteger(String value) { return value==null||value.isBlank()?null:Integer.valueOf(value); }
    private static Long nullableLong(String value) { return value==null||value.isBlank()?null:Long.valueOf(value); }
    private Instant parseTime(String value, boolean endOfDay) {
        if(value==null||value.isBlank()) return null;
        try { return Instant.parse(value); } catch(DateTimeParseException ignored) { }
        try { return OffsetDateTime.parse(value).toInstant(); } catch(DateTimeParseException ignored) { }
        try { return LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")).atZone(queryZone).toInstant(); }
        catch(DateTimeParseException ignored) { }
        try {
            LocalDate date=LocalDate.parse(value);
            return (endOfDay?date.plusDays(1).atStartOfDay(queryZone).minusNanos(1):date.atStartOfDay(queryZone)).toInstant();
        } catch(DateTimeParseException invalid) { throw new IllegalArgumentException("Invalid time query", invalid); }
    }
    private static String first(String... values) { for(String v:values)if(v!=null&&!v.isBlank())return v; return null; }
    private static long longId(String value) { return Long.parseLong(value); }
    private static Long parentId(Object value) {
        if(value==null)return null; long id=Long.parseLong(value.toString()); return id==0?null:id;
    }
    private void requirePermission(AuthorizationSubject subject, String permission) {
        authorization.requireTenantPermission(subject, permission);
    }
    private static AdministrationActor actor(AuthorizationSubject subject) {
        return new AdministrationActor(
            subject.membershipId(), subject.userId(),
            subject.permissionVersion(), subject.sessionVersion());
    }

    @FunctionalInterface interface TreeFactory<T,R>{ R create(T item,List<R> children); }
    record PageResponse<T>(List<T> items,long total) { }
    record IdResponse(String id) { }
    record UserResponse(String id,String username,String name,String deptId,String deptName,List<String> roleIds,
                        List<String> roleNames,int status,String identityStatus,long userVersion,String remark,
                        String createTime) { }
    record RoleResponse(String id,String name,List<String> menuIds,int status,String remark,long rowVersion,
                        boolean systemRole,boolean assignable,String createTime) { }
    record DepartmentResponse(String id,String pid,String name,int status,String remark,long rowVersion,String createTime,
                              List<DepartmentResponse> children) { }
    record MenuResponse(String id,String pid,String type,String name,String path,String component,String redirect,
                        String authCode,Map<String,Object> meta,int status,long rowVersion,
                        List<MenuResponse> children) { }
    record UserStatusResponse(long userVersion) { }

    record UserCreateRequest(@NotBlank @Size(max=100) String username,@NotBlank @Size(max=128) String name,
        @NotBlank @Pattern(regexp="[1-9][0-9]*") String deptId,
        @NotNull @Size(max=256) List<@Pattern(regexp="[1-9][0-9]{0,18}") String> roleIds,
        @NotNull @Min(0) @Max(1) Integer status,@Size(max=500) String remark) {
        IdentityModels.UserCreateCommand command(){ return new IdentityModels.UserCreateCommand(username,name,
            longId(deptId),roleIds.stream().map(SystemAdministrationController::longId).toList(),status,remark); }
    }
    record MembershipUpdateRequest(@NotBlank @Pattern(regexp="[1-9][0-9]*") String deptId,
        @NotNull @Size(max=256) List<@Pattern(regexp="[1-9][0-9]{0,18}") String> roleIds,
        @NotNull @Min(0) @Max(1) Integer status,@NotNull @Min(0) Long userVersion) {
        IdentityModels.MembershipUpdateCommand command(){ return new IdentityModels.MembershipUpdateCommand(
            longId(deptId),roleIds.stream().map(SystemAdministrationController::longId).toList(),status,userVersion); }
    }
    record UserStatusRequest(@NotNull @Min(0) @Max(1) Integer status,@NotNull @Min(0) Long userVersion) { }
    record RoleRequest(@NotBlank @Size(max=128) String name,@NotNull @Size(max=2048) List<@Pattern(regexp="[1-9][0-9]{0,18}") String> menuIds,
                       @NotNull @Min(0) @Max(1) Integer status,@Size(max=500) String remark) {
        IdentityModels.RoleCommand command(){ return new IdentityModels.RoleCommand(name,
            menuIds.stream().map(SystemAdministrationController::longId).toList(),status,remark); }
    }
    record RoleUpdateRequest(@NotBlank @Size(max=128) String name,
                             @NotNull @Size(max=2048) List<@Pattern(regexp="[1-9][0-9]{0,18}") String> menuIds,
                             @NotNull @Min(0) @Max(1) Integer status,@Size(max=500) String remark,
                             @NotNull @Min(0) Long expectedVersion) {
        IdentityModels.RoleCommand command(){ return new IdentityModels.RoleCommand(name,
            menuIds.stream().map(SystemAdministrationController::longId).toList(),status,remark); }
    }
    record RoleStatusRequest(@NotNull @Min(0) @Max(1) Integer status,
                             @NotNull @Min(0) Long expectedVersion) { }
    record DepartmentRequest(@Pattern(regexp="0|[1-9][0-9]*") String pid,@NotBlank @Size(max=128) String name,
                             @NotNull @Min(0) @Max(1) Integer status,@Size(max=500) String remark) {
        IdentityModels.DepartmentCommand command(){ return new IdentityModels.DepartmentCommand(parentId(pid),name,status,remark); }
    }
    record DepartmentUpdateRequest(@Pattern(regexp="0|[1-9][0-9]*") String pid,
                                   @NotBlank @Size(max=128) String name,
                                   @NotNull @Min(0) @Max(1) Integer status,@Size(max=500) String remark,
                                   @NotNull @Min(0) Long expectedVersion) {
        IdentityModels.DepartmentCommand command(){
            return new IdentityModels.DepartmentCommand(parentId(pid),name,status,remark);
        }
    }
    record MenuRequest(@Pattern(regexp="0|[1-9][0-9]*") String pid,@NotBlank @Size(max=16) String type,
                       @NotBlank @Size(max=128) String name,@Size(max=255) String path,
                       @Size(max=255) String component,@Size(max=255) String redirect,@Size(max=128) String authCode,
                       @NotNull @Size(max=32) Map<String,Object> meta,
                       @NotNull @Min(0) @Max(1) Integer status) {
        void validate(VbenMenuContract contract) {
            contract.validate(type,name,path,component,redirect,authCode,meta);
        }
        IdentityModels.MenuCommand command(ObjectMapper json){
            try{return new IdentityModels.MenuCommand(parentId(pid),type,name,path,component,redirect,authCode,
                json.writeValueAsString(meta),status);}
            catch(JacksonException e){throw new IllegalArgumentException("Invalid menu metadata",e);}
        }
    }
    record MenuUpdateRequest(@Pattern(regexp="0|[1-9][0-9]*") String pid,
                             @NotBlank @Size(max=16) String type,
                             @NotBlank @Size(max=128) String name,@Size(max=255) String path,
                             @Size(max=255) String component,@Size(max=255) String redirect,
                             @Size(max=128) String authCode,@NotNull @Size(max=32) Map<String,Object> meta,
                             @NotNull @Min(0) @Max(1) Integer status,
                             @NotNull @Min(0) Long expectedVersion) {
        void validate(VbenMenuContract contract) {
            contract.validate(type,name,path,component,redirect,authCode,meta);
        }
        IdentityModels.MenuCommand command(ObjectMapper json){
            try{return new IdentityModels.MenuCommand(parentId(pid),type,name,path,component,redirect,authCode,
                json.writeValueAsString(meta),status);}
            catch(JacksonException e){throw new IllegalArgumentException("Invalid menu metadata",e);}
        }
    }
}
