package com.niv.payment.adminapi.web;

import com.niv.payment.permission.domain.AuthorizationSubject;
import com.niv.payment.permission.port.DepartmentAdministrationPort;
import com.niv.payment.permission.port.IdentityQueryPort;
import com.niv.payment.permission.port.MenuAdministrationPort;
import com.niv.payment.permission.port.RoleAdministrationPort;
import com.niv.payment.permission.port.UserAdministrationPort;
import com.niv.payment.permission.service.AuthenticationService;
import com.niv.payment.permission.service.IdentityAdministrationService;
import com.niv.payment.permission.service.IdentityModels;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import tools.jackson.databind.ObjectMapper;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

class AuthUserMenuControllerTest {
    private static final String SUPPORTED_DUMMY_HASH =
        "$2a$12$N9qo8uLOickgx2ZMRZoMyeIjZAgcfl7p92ldGxad68LJZdL17lhWy";

    @Test
    void allMenusDropsOnlyUnsafeBranchesAndKeepsSafeSiblingsWithTheirAncestors() {
        List<IdentityModels.Menu> storedMenus = List.of(
            menu(1, null, "catalog", "System", "{\"title\":\"system.title\"}"),
            menu(2, 1L, "menu", "SafePage", "{\"title\":\"system.safe.title\"}"),
            menu(3, 1L, "menu", "DirtySibling",
                "{\"title\":\"system.dirty.title\",\"link\":\"javascript:alert(1)\"}"),
            menu(6, 1L, "menu", "MalformedJson", "{not-json"),
            menu(4, null, "catalog", "DirtyAncestor",
                "{\"title\":\"system.dirty-ancestor.title\",\"iframeSrc\":\"data:text/html,pwned\"}"),
            menu(5, 4L, "menu", "SafeChildOfDirtyAncestor",
                "{\"title\":\"system.child.title\"}")
        );
        AuthUserMenuController controller = new AuthUserMenuController(
            authentication(), identities(storedMenus), new ObjectMapper(), new VbenMenuContract(""));
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AuthorizationSubject.class.getName(),
            new AuthorizationSubject(100, 1_000, 1, 10L, 0, 0, false));

        ApiResponse<List<AuthUserMenuController.MenuResponse>> response = controller.allMenus(request);

        assertThat(response.data()).extracting(AuthUserMenuController.MenuResponse::name)
            .containsExactly("System");
        assertThat(response.data().getFirst().children())
            .extracting(AuthUserMenuController.MenuResponse::name)
            .containsExactly("SafePage");
    }

    private static IdentityModels.Menu menu(long id, Long parentId, String type, String name, String metaJson) {
        return new IdentityModels.Menu(id, parentId, type, name, "/" + name.toLowerCase(), null,
            null, null, metaJson, 1, 0);
    }

    private static AuthenticationService authentication() {
        return new AuthenticationService(
            (username, tenantId) -> Optional.empty(),
            (raw, encoded) -> false,
            new AuthenticationService.LoginAttemptLimiter() {
                @Override public void acquire(String clientKey, String normalizedUsername) { }
                @Override public void recordSuccess(String clientKey, String normalizedUsername) { }
            },
            account -> new AuthenticationService.LoginSession("unused"),
            SUPPORTED_DUMMY_HASH);
    }

    private static IdentityAdministrationService identities(List<IdentityModels.Menu> menus) {
        IdentityQueryPort queries = new IdentityQueryPort() {
            @Override
            public Optional<IdentityModels.CurrentUser> findCurrentUser(long tenantId, long membershipId) {
                return Optional.empty();
            }

            @Override
            public List<String> findPermissionCodes(long tenantId, long membershipId) {
                return List.of();
            }

            @Override
            public List<IdentityModels.Menu> findAccessibleMenus(long tenantId, long membershipId) {
                return menus;
            }
        };
        return new IdentityAdministrationService(
            queries,
            unsupported(UserAdministrationPort.class),
            unsupported(RoleAdministrationPort.class),
            unsupported(DepartmentAdministrationPort.class),
            unsupported(MenuAdministrationPort.class));
    }

    @SuppressWarnings("unchecked")
    private static <T> T unsupported(Class<T> type) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type},
            (proxy, method, arguments) -> {
                throw new UnsupportedOperationException(method.getName());
            });
    }
}
