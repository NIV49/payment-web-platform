package com.niv.payment.adminapi.web;

import tools.jackson.core.JacksonException;
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
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@RestController
@RequestMapping("/api")
public class AuthUserMenuController {
    private static final Logger LOG = LoggerFactory.getLogger(AuthUserMenuController.class);
    private final AuthenticationService authentication;
    private final IdentityAdministrationService identities;
    private final ObjectMapper json;
    private final VbenMenuContract menuContract;

    public AuthUserMenuController(AuthenticationService authentication,
                                  IdentityAdministrationService identities,
                                  ObjectMapper json,
                                  VbenMenuContract menuContract) {
        this.authentication = authentication;
        this.identities = identities;
        this.json = json;
        this.menuContract = menuContract;
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
        List<MenuResponse> menus = menuTree(identities.accessibleMenus(subject.tenantId(), subject.membershipId()));
        String homePath = resolveHomePath(user.homePath(), menus);
        return ApiResponse.success(new UserInfoResponse(Long.toString(user.id()), user.username(), user.realName(),
            user.avatar(), user.roles(), homePath, "", "cookie-session"));
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
        Map<Long, StoredMenu> candidates = new LinkedHashMap<>();
        for (IdentityModels.Menu menu : menus) {
            try {
                Map<String, Object> meta = json.readValue(menu.metaJson(), new TypeReference<>() { });
                menuContract.validateStoredMetadata(menu.type(), meta);
                candidates.put(menu.id(), new StoredMenu(menu, meta));
            } catch (JacksonException | IllegalArgumentException invalidStoredMetadata) {
                // Fail closed per node. Its descendants are removed below because their
                // complete, explicitly returned ancestor chain no longer exists.
                LOG.warn("Suppressing unsafe stored menu branch: menuId={}, reason={}",
                    menu.id(), invalidStoredMetadata.getClass().getSimpleName());
            }
        }

        Map<Long, StoredMenu> safeMenus = new LinkedHashMap<>();
        for (StoredMenu candidate : candidates.values()) {
            if (hasCompleteAncestorChain(candidate, candidates)) {
                safeMenus.put(candidate.source().id(), candidate);
            }
        }

        Map<Long, List<StoredMenu>> children = new LinkedHashMap<>();
        for (StoredMenu menu : safeMenus.values()) {
            children.computeIfAbsent(menu.source().parentId(), ignored -> new ArrayList<>()).add(menu);
        }
        List<StoredMenu> roots = safeMenus.values().stream()
            .filter(menu -> menu.source().parentId() == null).toList();
        Set<String> accessiblePaths = safeMenus.values().stream()
            .map(StoredMenu::source)
            .map(IdentityModels.Menu::path)
            .filter(path -> path != null)
            .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return roots.stream().map(item -> menu(item, children, accessiblePaths)).toList();
    }

    private static boolean hasCompleteAncestorChain(StoredMenu item, Map<Long, StoredMenu> candidates) {
        Set<Long> visited = new java.util.HashSet<>();
        StoredMenu current = item;
        while (current != null) {
            if (!visited.add(current.source().id())) return false;
            Long parentId = current.source().parentId();
            if (parentId == null) return true;
            current = candidates.get(parentId);
        }
        return false;
    }

    private MenuResponse menu(StoredMenu item, Map<Long, List<StoredMenu>> children,
                              Set<String> accessiblePaths) {
        IdentityModels.Menu source = item.source();
        List<MenuResponse> nested = children.getOrDefault(source.id(), List.of()).stream()
            .map(child -> menu(child, children, accessiblePaths)).toList();
        String redirect = source.redirect() != null && accessiblePaths.contains(source.redirect())
            ? source.redirect()
            : nested.stream().findFirst().map(MenuResponse::path).orElse(null);
        return new MenuResponse(Long.toString(source.id()),
            source.parentId() == null ? "0" : source.parentId().toString(),
            source.name(), source.path(), source.component(), redirect, source.authCode(), source.type(),
            item.meta(), source.status(), nested);
    }

    private static String resolveHomePath(String preferred, List<MenuResponse> menus) {
        if (containsPath(menus, preferred)) {
            return preferred;
        }
        return firstLeafPath(menus).orElse("/profile");
    }

    private static boolean containsPath(List<MenuResponse> menus, String path) {
        return path != null && menus.stream()
            .anyMatch(menu -> path.equals(menu.path()) || containsPath(menu.children(), path));
    }

    private static Optional<String> firstLeafPath(List<MenuResponse> menus) {
        for (MenuResponse menu : menus) {
            Optional<String> childPath = firstLeafPath(menu.children());
            if (childPath.isPresent()) {
                return childPath;
            }
            if (menu.path() != null) {
                return Optional.of(menu.path());
            }
        }
        return Optional.empty();
    }

    private static Long parseOptionalId(String value) {
        return value == null ? null : Long.parseLong(value);
    }

    static AuthorizationSubject subject(HttpServletRequest request) {
        Object value = request.getAttribute(AuthorizationSubject.class.getName());
        if (!(value instanceof AuthorizationSubject subject)) throw new IllegalStateException("Trusted session is missing");
        return subject;
    }

    record LoginRequest(@NotBlank @Size(max = 100) String username,
                        @NotBlank @Size(max = 256) String password,
                        @Pattern(regexp = "[1-9][0-9]{0,18}") String tenantId) { }
    record LoginResponse(String accessToken) { }
    record UserInfoResponse(String userId, String username, String realName, String avatar,
                            List<String> roles, String homePath, String desc, String token) { }
    record MenuResponse(String id, String pid, String name, String path, String component, String redirect,
                        String authCode, String type, Map<String, Object> meta, int status,
                        List<MenuResponse> children) { }
    private record StoredMenu(IdentityModels.Menu source, Map<String, Object> meta) { }
}
