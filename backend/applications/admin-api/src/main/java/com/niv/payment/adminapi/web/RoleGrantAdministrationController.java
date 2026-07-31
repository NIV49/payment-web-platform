package com.niv.payment.adminapi.web;

import com.niv.payment.permission.domain.AdministrationActor;
import com.niv.payment.permission.domain.AuthorizationSubject;
import com.niv.payment.permission.domain.PermissionCode;
import com.niv.payment.permission.domain.ScopeDimension;
import com.niv.payment.permission.domain.ScopeMode;
import com.niv.payment.permission.service.RoleGrantAdministrationService;
import com.niv.payment.permission.service.RoleGrantChangeCommand;
import com.niv.payment.permission.service.RoleGrantModels;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/iam")
public final class RoleGrantAdministrationController {
    private final RoleGrantAdministrationService grants;

    public RoleGrantAdministrationController(RoleGrantAdministrationService grants) {
        this.grants = grants;
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

    record ReplaceRoleGrantsRequest(@NotNull @Min(0) Long expectedVersion,
                                    @NotBlank @Size(max = 500) String reason,
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
