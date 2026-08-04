package com.niv.payment.permission.backoffice;

import com.niv.payment.permission.domain.AuthorizationSubject;
import com.niv.payment.permission.security.SaTokenSessionBridge;
import com.niv.payment.permission.service.AuthenticationService;
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
final class BackofficeAuthController {
    private final AuthenticationService authentication;
    private final SaTokenSessionBridge sessions;
    private final BackofficeAccessService access;
    private final VbenMenuTreeMapper menuMapper;

    BackofficeAuthController(AuthenticationService authentication, SaTokenSessionBridge sessions,
                             BackofficeAccessService access, VbenMenuTreeMapper menuMapper) {
        this.authentication = authentication;
        this.sessions = sessions;
        this.access = access;
        this.menuMapper = menuMapper;
    }

    @PostMapping("/auth/login")
    BackofficeApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                               HttpServletRequest http) {
        authentication.login(new AuthenticationService.LoginCommand(
            request.username(), request.password(), http.getRemoteAddr()));
        return BackofficeApiResponse.success(new LoginResponse("cookie-session"));
    }

    @PostMapping("/auth/logout")
    BackofficeApiResponse<Void> logout() {
        authentication.logout();
        return BackofficeApiResponse.success(null);
    }

    @GetMapping("/user/info")
    BackofficeApiResponse<UserInfoResponse> currentUser() {
        AuthorizationSubject subject = sessions.currentSubject();
        IdentityModels.CurrentUser user = access.currentUser(subject.tenantId(), subject.membershipId());
        List<VbenMenuTreeMapper.MenuRoute> menus = menuMapper.map(
            access.menus(subject.tenantId(), subject.membershipId()));
        return BackofficeApiResponse.success(new UserInfoResponse(
            Long.toString(user.id()), user.username(), user.realName(), user.avatar(), user.roles(),
            menuMapper.resolveHomePath(user.homePath(), menus), "", "cookie-session",
            user.systemAdministrator()));
    }

    @GetMapping("/auth/codes")
    BackofficeApiResponse<List<String>> permissionCodes() {
        AuthorizationSubject subject = sessions.currentSubject();
        return BackofficeApiResponse.success(access.permissionCodes(subject.tenantId(), subject.membershipId()));
    }

    @GetMapping("/menu/all")
    BackofficeApiResponse<List<VbenMenuTreeMapper.MenuRoute>> menus() {
        AuthorizationSubject subject = sessions.currentSubject();
        return BackofficeApiResponse.success(menuMapper.map(
            access.menus(subject.tenantId(), subject.membershipId())));
    }

    @GetMapping("/health")
    BackofficeApiResponse<HealthResponse> health() {
        return BackofficeApiResponse.success(new HealthResponse("UP"));
    }

    record LoginRequest(@NotBlank @Size(max = 100) String username,
                        @NotBlank @Size(max = 256) String password) { }
    record LoginResponse(String accessToken) { }
    record UserInfoResponse(String userId, String username, String realName, String avatar,
                            List<String> roles, String homePath, String desc, String token,
                            boolean systemAdministrator) { }
    record HealthResponse(String status) { }
}
