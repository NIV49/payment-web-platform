package com.niv.payment.adminapi.web;

import com.niv.payment.permission.domain.AuthorizationSubject;
import com.niv.payment.permission.backoffice.VbenMenuTreeMapper;
import com.niv.payment.permission.service.AuthenticationService;
import com.niv.payment.permission.service.IdentityAdministrationService;
import com.niv.payment.permission.service.IdentityModels;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
@RequestMapping("/api")
public class AuthUserMenuController {
    private final AuthenticationService authentication;
    private final IdentityAdministrationService identities;
    private final VbenMenuTreeMapper menuMapper;

    public AuthUserMenuController(AuthenticationService authentication,
                                  IdentityAdministrationService identities,
                                  VbenMenuTreeMapper menuMapper) {
        this.authentication = authentication;
        this.identities = identities;
        this.menuMapper = menuMapper;
    }

    @PostMapping("/auth/login")
    ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        authentication.login(new AuthenticationService.LoginCommand(
            request.username(), request.password(), http.getRemoteAddr()));
        return ApiResponse.success(new LoginResponse("cookie-session"));
    }

    @PostMapping("/auth/logout")
    ApiResponse<Void> logout() {
        authentication.logout();
        return ApiResponse.success(null);
    }

    @GetMapping("/health")
    ApiResponse<HealthResponse> health() {
        return ApiResponse.success(new HealthResponse("UP"));
    }

    @GetMapping("/user/info")
    ApiResponse<UserInfoResponse> currentUser(HttpServletRequest request) {
        AuthorizationSubject subject = subject(request);
        IdentityModels.CurrentUser user = identities.currentUser(subject.tenantId(), subject.membershipId());
        List<VbenMenuTreeMapper.MenuRoute> menus = menuMapper.map(
            identities.accessibleMenus(subject.tenantId(), subject.membershipId()));
        String homePath = menuMapper.resolveHomePath(user.homePath(), menus);
        return ApiResponse.success(new UserInfoResponse(Long.toString(user.id()), user.username(), user.realName(),
            user.avatar(), user.roles(), homePath, "", "cookie-session", user.systemAdministrator()));
    }

    @GetMapping("/auth/codes")
    ApiResponse<List<String>> permissionCodes(HttpServletRequest request) {
        AuthorizationSubject subject = subject(request);
        return ApiResponse.success(identities.permissionCodes(subject.tenantId(), subject.membershipId()));
    }

    @GetMapping("/menu/all")
    ApiResponse<List<VbenMenuTreeMapper.MenuRoute>> allMenus(HttpServletRequest request) {
        AuthorizationSubject subject = subject(request);
        return ApiResponse.success(menuMapper.map(
            identities.accessibleMenus(subject.tenantId(), subject.membershipId())));
    }

    static AuthorizationSubject subject(HttpServletRequest request) {
        Object value = request.getAttribute(AuthorizationSubject.class.getName());
        if (!(value instanceof AuthorizationSubject subject)) throw new IllegalStateException("Trusted session is missing");
        return subject;
    }

    record LoginRequest(@NotBlank @Size(max = 100) String username,
                        @NotBlank @Size(max = 256) String password) { }
    record LoginResponse(String accessToken) { }
    record HealthResponse(String status) { }
    record UserInfoResponse(String userId, String username, String realName, String avatar,
                            List<String> roles, String homePath, String desc, String token,
                            boolean systemAdministrator) { }
}
