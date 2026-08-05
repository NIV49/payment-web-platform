package com.niv.payment.adminapi.web;

import com.niv.payment.permission.domain.AdministrationActor;
import com.niv.payment.permission.domain.AuthorizationSubject;
import com.niv.payment.permission.domain.PermissionCode;
import com.niv.payment.permission.domain.ScopeDimension;
import com.niv.payment.permission.domain.ScopeMode;
import com.niv.payment.permission.service.RoleGrantAdministrationService;
import com.niv.payment.permission.service.RoleGrantChangeCommand;
import com.niv.payment.permission.service.RoleGrantModels;
import com.niv.payment.permission.service.RoleConfigurationAdministrationService;
import com.niv.payment.permission.service.RoleConfigurationCommand;
import com.niv.payment.permission.service.RoleConfigurationCreateCommand;
import com.niv.payment.permission.service.RoleConfigurationModels;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/iam")
public final class RoleGrantAdministrationController {
    private final RoleGrantAdministrationService grants;
    private final RoleConfigurationAdministrationService configurations;

    public RoleGrantAdministrationController(
        RoleGrantAdministrationService grants,
        RoleConfigurationAdministrationService configurations) {
        this.grants = grants;
        this.configurations = configurations;
    }

    @GetMapping("/permissions/grantable")
    ApiResponse<List<GrantablePermissionResponse>> grantablePermissions(HttpServletRequest request) {
        AuthorizationSubject subject = AuthUserMenuController.subject(request);
        return ApiResponse.success(grants.grantablePermissions(subject.tenantId(), actor(subject)).stream()
            .map(GrantablePermissionResponse::from).toList());
    }

    @GetMapping("/roles/{roleId}/grants")
    ApiResponse<RoleGrantsResponse> roleGrants(
        @PathVariable @Min(1) Long roleId,
        HttpServletRequest request) {
        AuthorizationSubject subject = AuthUserMenuController.subject(request);
        return ApiResponse.success(RoleGrantsResponse.from(
            grants.find(subject.tenantId(), actor(subject), roleId)));
    }

    @PutMapping("/roles/{roleId}/grants")
    ApiResponse<RoleGrantsResponse> replaceRoleGrants(
        @PathVariable @Min(1) Long roleId,
        @Valid @RequestBody ReplaceRoleGrantsRequest body,
        HttpServletRequest request) {
        AuthorizationSubject subject = AuthUserMenuController.subject(request);
        RoleGrantChangeCommand command = new RoleGrantChangeCommand(
            subject.tenantId(), roleId, body.expectedVersion(), actor(subject),
            body.reason(), body.grants().stream().map(GrantRequest::selection).toList());
        return ApiResponse.success(RoleGrantsResponse.from(grants.replace(command)));
    }

    @PutMapping("/roles/{roleId}/configuration")
    ApiResponse<RoleConfigurationResponse> replaceRoleConfiguration(
        @PathVariable @Min(1) Long roleId,
        @Valid @RequestBody ReplaceRoleConfigurationRequest body,
        HttpServletRequest request) {
        AuthorizationSubject subject = AuthUserMenuController.subject(request);
        RoleConfigurationCommand command = new RoleConfigurationCommand(
            subject.tenantId(), roleId, body.expectedVersion(), actor(subject), body.name(),
            body.status(), body.remark(), body.menuIds().stream().map(Long::parseLong).toList(),
            body.reason(), body.grants().stream().map(GrantRequest::selection).toList());
        return ApiResponse.success(RoleConfigurationResponse.from(configurations.replace(command)));
    }

    @PostMapping("/roles/configuration")
    ApiResponse<RoleConfigurationResponse> createRoleConfiguration(
        @Valid @RequestBody CreateRoleConfigurationRequest body,
        HttpServletRequest request) {
        AuthorizationSubject subject = AuthUserMenuController.subject(request);
        RoleConfigurationCreateCommand command = new RoleConfigurationCreateCommand(
            subject.tenantId(), actor(subject), body.name(), body.status(), body.remark(),
            body.menuIds().stream().map(Long::parseLong).toList(),
            body.grants().stream().map(GrantRequest::selection).toList());
        return ApiResponse.success(RoleConfigurationResponse.from(configurations.create(command)));
    }

    private static AdministrationActor actor(AuthorizationSubject subject) {
        return new AdministrationActor(subject.membershipId(), subject.userId(),
            subject.permissionVersion(), subject.sessionVersion());
    }

    record GrantablePermissionResponse(String permissionCode, String resourceCode, String actionCode,
                                       String riskLevel, List<RequiredDimensionResponse> requiredDimensions) {
        static GrantablePermissionResponse from(RoleGrantModels.GrantablePermission permission) {
            return new GrantablePermissionResponse(permission.code().value(), permission.resource(),
                permission.action(), "NORMAL",
                List.of(new RequiredDimensionResponse("TENANT", List.of("TENANT_ALL"))));
        }
    }

    record RequiredDimensionResponse(String code, List<String> allowedModes) {
    }

    record RoleGrantsResponse(String roleId, long roleVersion, boolean editable,
                              List<GrantResponse> grants) {
        static RoleGrantsResponse from(RoleGrantModels.RoleGrants source) {
            return new RoleGrantsResponse(Long.toString(source.roleId()), source.roleVersion(),
                source.editable(), source.grants().stream().map(GrantResponse::from).toList());
        }
    }

    record GrantResponse(String grantKey, String permissionCode, List<DimensionResponse> dimensions) {
        static GrantResponse from(RoleGrantModels.Selection source) {
            return new GrantResponse(source.grantKey(), source.permission().value(), List.of(
                new DimensionResponse(source.dimension().name(), source.mode().name(), List.of())));
        }
    }

    record DimensionResponse(String code, String mode, List<String> targets) {
    }

    record RoleConfigurationResponse(String roleId, long roleVersion, List<String> menuIds,
                                     List<GrantResponse> grants, boolean editable) {
        static RoleConfigurationResponse from(RoleConfigurationModels.RoleConfiguration source) {
            return new RoleConfigurationResponse(
                Long.toString(source.roleId()), source.roleVersion(),
                source.menuIds().stream().map(String::valueOf).toList(),
                source.grants().stream().map(GrantResponse::from).toList(), source.editable());
        }
    }

    record ReplaceRoleGrantsRequest(@NotNull @Min(0) Long expectedVersion,
                                    @NotBlank @Size(max = 500) String reason,
                                    @NotNull @Size(max = 18) List<@NotNull @Valid GrantRequest> grants) {
    }

    record ReplaceRoleConfigurationRequest(
        @NotNull @Min(0) Long expectedVersion,
        @NotBlank @Size(max = 128) String name,
        @NotNull @Min(0) @Max(1) Integer status,
        @Size(max = 500) String remark,
        @NotNull @Size(max = 2048)
        List<@Pattern(regexp = "[1-9][0-9]{0,18}") String> menuIds,
        @NotBlank @Size(max = 500) String reason,
        @NotNull @Size(max = 18) List<@NotNull @Valid GrantRequest> grants) {
    }

    record CreateRoleConfigurationRequest(
        @NotBlank @Size(max = 128) String name,
        @NotNull @Min(0) @Max(1) Integer status,
        @Size(max = 500) String remark,
        @NotNull @Size(max = 2048)
        List<@Pattern(regexp = "[1-9][0-9]{0,18}") String> menuIds,
        @NotNull @Size(max = 18) List<@NotNull @Valid GrantRequest> grants) {
    }

    record GrantRequest(
        @NotBlank @Pattern(regexp = "[a-z][a-z0-9_-]{0,63}") String grantKey,
        @NotBlank @Pattern(regexp = "[a-z][a-z0-9_-]*:[a-z][a-z0-9_-]*") String permissionCode,
        @NotNull @Size(min = 1, max = 1) List<@NotNull @Valid DimensionRequest> dimensions) {
        RoleGrantModels.Selection selection() {
            DimensionRequest dimension = dimensions.getFirst();
            return new RoleGrantModels.Selection(grantKey, PermissionCode.of(permissionCode),
                dimension.code(), dimension.mode());
        }
    }

    record DimensionRequest(@NotNull ScopeDimension code, @NotNull ScopeMode mode,
                            @NotNull @Size(max = 0) List<@NotBlank String> targets) {
    }
}
