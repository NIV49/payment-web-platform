package com.niv.payment.adminapi.web;

import com.niv.payment.permission.domain.AccountDomain;
import com.niv.payment.permission.domain.AdministrationActor;
import com.niv.payment.permission.domain.AuthorizationSubject;
import com.niv.payment.permission.port.PlatformUserGovernancePort;
import com.niv.payment.permission.service.PlatformUserGovernanceService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/** PLATFORM-only cross-domain directory and protected tenant administrator control plane. */
@RestController
@RequestMapping("/api/platform")
public final class PlatformUserGovernanceController {
    private final PlatformUserGovernanceService governance;

    public PlatformUserGovernanceController(PlatformUserGovernanceService governance) {
        this.governance = governance;
    }

    @GetMapping("/user-directory")
    ApiResponse<PageResponse<UserResponse>> users(
        @RequestParam Map<String, String> query, HttpServletRequest request) {
        AuthorizationSubject subject = AuthUserMenuController.subject(request);
        AccountDomain accountDomain = accountDomain(query.getOrDefault("accountDomain", "PLATFORM"));
        var result = governance.findUsers(subject.tenantId(), actor(subject),
            new PlatformUserGovernancePort.DirectoryQuery(
                accountDomain, nullableLong(query.get("tenantId")), nullableLong(query.get("deptId")),
                query.get("username"), query.get("name"), nullableInteger(query.get("status")),
                integer(query.get("page"), 1), integer(query.get("pageSize"), 20)));
        return ApiResponse.success(new PageResponse<>(
            result.items().stream().map(PlatformUserGovernanceController::user).toList(),
            result.total()));
    }

    @GetMapping("/tenant-options")
    ApiResponse<List<TenantResponse>> tenants(
        @RequestParam("accountDomain") String accountDomain, HttpServletRequest request) {
        AuthorizationSubject subject = AuthUserMenuController.subject(request);
        return ApiResponse.success(governance.findTargetTenants(
            subject.tenantId(), actor(subject), accountDomain(accountDomain)).stream()
            .map(option -> new TenantResponse(
                Long.toString(option.id()), option.accountDomain(), option.code(), option.name()))
            .toList());
    }

    @PostMapping("/tenant-administrators")
    ApiResponse<IdResponse> create(
        @Valid @RequestBody AdministratorCreateRequest body, HttpServletRequest request) {
        AuthorizationSubject subject = AuthUserMenuController.subject(request);
        long id = governance.createTenantAdministrator(subject.tenantId(), actor(subject),
            body.command());
        return ApiResponse.success(new IdResponse(Long.toString(id)));
    }

    @PutMapping("/tenant-administrators/{userId}")
    ApiResponse<Void> update(
        @PathVariable long userId, @Valid @RequestBody AdministratorUpdateRequest body,
        HttpServletRequest request) {
        AuthorizationSubject subject = AuthUserMenuController.subject(request);
        governance.updateTenantAdministrator(subject.tenantId(), actor(subject), userId,
            body.command());
        return ApiResponse.success(null);
    }

    @PostMapping("/users/{userId}/password/reset")
    ApiResponse<PasswordResetResponse> resetPassword(
        @PathVariable long userId, @Valid @RequestBody PasswordResetRequest body,
        HttpServletRequest request) {
        AuthorizationSubject subject = AuthUserMenuController.subject(request);
        var result = governance.resetUserPassword(subject.tenantId(), actor(subject), userId,
            body.command());
        return ApiResponse.success(new PasswordResetResponse(
            result.credentialVersion(), result.identityVersion(), result.userVersion()));
    }

    private static UserResponse user(PlatformUserGovernancePort.DirectoryUser user) {
        return new UserResponse(
            Long.toString(user.id()), Long.toString(user.membershipId()), user.accountDomain(),
            Long.toString(user.tenantId()), user.tenantName(), user.email(), user.name(),
            user.remark(),
            user.departmentId() == null ? "0" : Long.toString(user.departmentId()),
            user.departmentName(), user.roleIds().stream().map(String::valueOf).toList(),
            user.roleNames(), user.status(), user.identityStatus(), user.systemAdministrator(),
            user.membershipVersion(), user.identityVersion(), user.credentialVersion(),
            user.createdAt().toString());
    }

    private static AdministrationActor actor(AuthorizationSubject subject) {
        return AdministrationActor.from(subject);
    }

    private static AccountDomain accountDomain(String value) {
        try {
            return AccountDomain.valueOf(value);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid accountDomain", invalid);
        }
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

    record PageResponse<T>(List<T> items, long total) { }
    record IdResponse(String id) { }
    record PasswordResetResponse(
        long credentialVersion, long identityVersion, long userVersion) { }
    record TenantResponse(String id, AccountDomain accountDomain, String code, String name) { }
    record UserResponse(String id, String membershipId, AccountDomain accountDomain,
                        String tenantId, String tenantName, String username, String name,
                        String remark,
                        String deptId, String deptName, List<String> roleIds,
                        List<String> roleNames, int status, String identityStatus,
                        boolean systemAdministrator, long userVersion, long identityVersion,
                        long credentialVersion, String createTime) { }

    record AdministratorCreateRequest(
        @NotNull AccountDomain accountDomain,
        @NotBlank String tenantId,
        @NotBlank @Size(max = 100) String username,
        @NotBlank @Size(max = 128) String name,
        @NotNull @Min(0) @Max(1) Integer status) {
        PlatformUserGovernancePort.CreateAdministratorCommand command() {
            return new PlatformUserGovernancePort.CreateAdministratorCommand(
                accountDomain, Long.parseLong(tenantId), username, name, status);
        }
    }

    record AdministratorUpdateRequest(
        @NotNull AccountDomain accountDomain,
        @NotBlank String tenantId,
        @NotBlank @Size(max = 100) String username,
        @NotBlank @Size(max = 128) String name,
        @NotNull @Min(0) @Max(1) Integer status,
        @NotNull @Min(0) Long userVersion,
        @NotNull @Min(0) Long identityVersion,
        @NotNull @Min(0) Long credentialVersion) {
        PlatformUserGovernancePort.UpdateAdministratorCommand command() {
            return new PlatformUserGovernancePort.UpdateAdministratorCommand(
                accountDomain, Long.parseLong(tenantId), username, name, status,
                userVersion, identityVersion, credentialVersion);
        }
    }

    record PasswordResetRequest(
        @NotNull AccountDomain accountDomain,
        @NotBlank String tenantId,
        @NotNull @Min(0) Long credentialVersion,
        String password) {
        PlatformUserGovernancePort.PasswordResetCommand command() {
            return new PlatformUserGovernancePort.PasswordResetCommand(
                accountDomain, Long.parseLong(tenantId), credentialVersion, password);
        }

        @Override
        public String toString() {
            return "PasswordResetRequest[accountDomain=" + accountDomain
                + ", tenantId=" + tenantId + ", credentialVersion=" + credentialVersion
                + ", password=<redacted>]";
        }
    }
}
