package com.niv.payment.permission.backoffice;

import com.niv.payment.permission.domain.AuthorizationSubject;
import com.niv.payment.permission.security.SaTokenSessionBridge;
import com.niv.payment.permission.service.IdentityModels;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import java.util.List;

@RestController
@RequestMapping("/api")
final class BackofficeAuthController {
    private final SaTokenSessionBridge sessions;
    private final BackofficeAccessService access;
    private final VbenMenuTreeMapper menuMapper;

    BackofficeAuthController(SaTokenSessionBridge sessions, BackofficeAccessService access,
                             VbenMenuTreeMapper menuMapper) {
        this.sessions = sessions;
        this.access = access;
        this.menuMapper = menuMapper;
    }

    @GetMapping("/auth/csrf")
    BackofficeApiResponse<RequestProofResponse> requestProof() {
        return BackofficeApiResponse.success(new RequestProofResponse(sessions.requestProof()));
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

    record RequestProofResponse(String requestProof) { }
    record UserInfoResponse(String userId, String username, String realName, String avatar,
                            List<String> roles, String homePath, String desc, String token,
                            boolean systemAdministrator) { }
    record HealthResponse(String status) { }
}
