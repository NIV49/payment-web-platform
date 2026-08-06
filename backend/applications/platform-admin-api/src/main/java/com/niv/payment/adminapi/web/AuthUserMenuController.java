package com.niv.payment.adminapi.web;

import com.niv.payment.permission.domain.AuthorizationSubject;
import com.niv.payment.permission.backoffice.VbenMenuTreeMapper;
import com.niv.payment.permission.service.IdentityAdministrationService;
import com.niv.payment.permission.service.IdentityModels;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
@RequestMapping("/api")
public class AuthUserMenuController {
    private final IdentityAdministrationService identities;
    private final VbenMenuTreeMapper menuMapper;

    public AuthUserMenuController(IdentityAdministrationService identities,
                                  VbenMenuTreeMapper menuMapper) {
        this.identities = identities;
        this.menuMapper = menuMapper;
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

    record HealthResponse(String status) { }
    record UserInfoResponse(String userId, String username, String realName, String avatar,
                            List<String> roles, String homePath, String desc, String token,
                            boolean systemAdministrator) { }
}
