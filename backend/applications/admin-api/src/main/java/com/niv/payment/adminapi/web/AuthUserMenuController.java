package com.niv.payment.adminapi.web;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import com.niv.payment.permission.domain.AuthorizationSubject;
import com.niv.payment.permission.service.AuthenticationService;
import com.niv.payment.permission.service.IdentityAdministrationService;
import com.niv.payment.permission.service.IdentityModels;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

@RestController
@RequestMapping("/api")
public class AuthUserMenuController {
    private final AuthenticationService authentication;
    private final IdentityAdministrationService identities;
    private final ObjectMapper json;

    public AuthUserMenuController(AuthenticationService authentication,
                                  IdentityAdministrationService identities,
                                  ObjectMapper json) {
        this.authentication = authentication;
        this.identities = identities;
        this.json = json;
    }

    @PostMapping("/auth/login")
    ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request, HttpServletRequest http) {
        authentication.login(new AuthenticationService.LoginCommand(
            request.username(), request.password(), parseOptionalId(request.tenantId()), http.getRemoteAddr()));
        return ApiResponse.success(new LoginResponse("cookie-session"));
    }

    @PostMapping("/auth/logout")
    ApiResponse<Void> logout() {
        authentication.logout();
        return ApiResponse.success(null);
    }

    @GetMapping("/user/info")
    ApiResponse<UserInfoResponse> currentUser(HttpServletRequest request) {
        AuthorizationSubject subject = subject(request);
        IdentityModels.CurrentUser user = identities.currentUser(subject.tenantId(), subject.membershipId());
        return ApiResponse.success(new UserInfoResponse(Long.toString(user.id()), user.username(), user.realName(),
            user.avatar(), user.roles(), user.homePath()));
    }

    @GetMapping("/auth/codes")
    ApiResponse<List<String>> permissionCodes(HttpServletRequest request) {
        AuthorizationSubject subject = subject(request);
        return ApiResponse.success(identities.permissionCodes(subject.tenantId(), subject.membershipId()));
    }

    @GetMapping("/menu/all")
    ApiResponse<List<MenuResponse>> allMenus(HttpServletRequest request) {
        AuthorizationSubject subject = subject(request);
        return ApiResponse.success(menuTree(identities.accessibleMenus(subject.tenantId(), subject.membershipId())));
    }

    private List<MenuResponse> menuTree(List<IdentityModels.Menu> menus) {
        Map<Long, List<IdentityModels.Menu>> children = new LinkedHashMap<>();
        Set<Long> ids = menus.stream().map(IdentityModels.Menu::id).collect(java.util.stream.Collectors.toSet());
        for (IdentityModels.Menu menu : menus) children.computeIfAbsent(menu.parentId(), ignored -> new ArrayList<>()).add(menu);
        List<IdentityModels.Menu> roots = menus.stream().filter(m -> m.parentId() == null || !ids.contains(m.parentId())).toList();
        return roots.stream().map(item -> menu(item, children)).toList();
    }

    private MenuResponse menu(IdentityModels.Menu item, Map<Long, List<IdentityModels.Menu>> children) {
        Map<String, Object> meta;
        try {
            meta = json.readValue(item.metaJson(), new TypeReference<>() { });
        } catch (Exception invalidStoredJson) {
            throw new IllegalStateException("Stored menu metadata is invalid", invalidStoredJson);
        }
        List<MenuResponse> nested = children.getOrDefault(item.id(), List.of()).stream()
            .map(child -> menu(child, children)).toList();
        return new MenuResponse(Long.toString(item.id()), item.parentId() == null ? "0" : item.parentId().toString(),
            item.name(), item.path(), item.component(), item.redirect(), item.authCode(), item.type(), meta,
            item.status(), nested);
    }

    private static Long parseOptionalId(String value) {
        return value == null ? null : Long.parseLong(value);
    }

    static AuthorizationSubject subject(HttpServletRequest request) {
        Object value = request.getAttribute(AuthorizationSubject.class.getName());
        if (!(value instanceof AuthorizationSubject subject)) throw new IllegalStateException("Trusted session is missing");
        return subject;
    }

    record LoginRequest(@NotBlank String username, @NotBlank String password,
                        @Pattern(regexp = "[1-9][0-9]*") String tenantId) { }
    record LoginResponse(String accessToken) { }
    record UserInfoResponse(String userId, String username, String realName, String avatar,
                            List<String> roles, String homePath) { }
    record MenuResponse(String id, String pid, String name, String path, String component, String redirect,
                        String authCode, String type, Map<String, Object> meta, int status,
                        List<MenuResponse> children) { }
}
