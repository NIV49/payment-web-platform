package com.niv.payment.adminapi.web;

import com.niv.payment.permission.domain.AuthorizationSubject;
import com.niv.payment.permission.backoffice.VbenMenuContract;
import com.niv.payment.permission.backoffice.VbenMenuTreeMapper;
import com.niv.payment.permission.port.DepartmentAdministrationPort;
import com.niv.payment.permission.port.IdentityQueryPort;
import com.niv.payment.permission.port.MenuAdministrationPort;
import com.niv.payment.permission.port.RoleAdministrationPort;
import com.niv.payment.permission.port.UserAdministrationPort;
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
            identities(storedMenus), menuMapper());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AuthorizationSubject.class.getName(),
            new AuthorizationSubject(100, 1_000, 1, 10L, 0, 0, false));

        ApiResponse<List<VbenMenuTreeMapper.MenuRoute>> response = controller.allMenus(request);

        assertThat(response.data()).extracting(VbenMenuTreeMapper.MenuRoute::name)
            .containsExactly("System");
        assertThat(response.data().getFirst().children())
            .extracting(VbenMenuTreeMapper.MenuRoute::name)
            .containsExactly("SafePage");
    }

    @Test
    void allMenusRedirectsToTheFirstAccessibleChildWhenStoredTargetWasFilteredOut() {
        List<IdentityModels.Menu> storedMenus = List.of(
            menu(1, null, "catalog", "Dashboard", "/dashboard", "/dashboard/analytics",
                "{\"title\":\"page.dashboard.title\"}"),
            menu(3, 1L, "menu", "Workspace", "/dashboard/workspace", null,
                "{\"title\":\"page.dashboard.workspace\"}")
        );
        AuthUserMenuController controller = new AuthUserMenuController(
            identities(storedMenus), menuMapper());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AuthorizationSubject.class.getName(),
            new AuthorizationSubject(100, 1_000, 1, 10L, 0, 0, false));

        ApiResponse<List<VbenMenuTreeMapper.MenuRoute>> response = controller.allMenus(request);

        assertThat(response.data().getFirst().redirect()).isEqualTo("/dashboard/workspace");
    }

    @Test
    void currentUserAddsOnlyTheApprovedWebCompatibilityFields() {
        IdentityModels.CurrentUser currentUser = new IdentityModels.CurrentUser(
            100, "admin", "Platform Administrator", "", List.of("platform-admin"), "/dashboard", true);
        AuthUserMenuController controller = new AuthUserMenuController(
            identities(List.of(), Optional.of(currentUser)),
            menuMapper());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AuthorizationSubject.class.getName(),
            new AuthorizationSubject(100, 1_000, 1, 10L, 0, 0, false));

        ApiResponse<AuthUserMenuController.UserInfoResponse> response = controller.currentUser(request);

        assertThat(response.data()).isEqualTo(new AuthUserMenuController.UserInfoResponse(
            "100", "admin", "Platform Administrator", "", List.of("platform-admin"),
            "/profile", "", "cookie-session", true));
    }

    @Test
    void currentUserUsesTheFirstAccessibleLeafWhenPreferredHomeIsMissing() {
        IdentityModels.CurrentUser currentUser = new IdentityModels.CurrentUser(
            100, "admin", "Platform Administrator", "", List.of("restricted"), "/dashboard", false);
        List<IdentityModels.Menu> storedMenus = List.of(
            menu(10, null, "catalog", "System", "/system", null,
                "{\"title\":\"system.title\"}"),
            menu(11, 10L, "menu", "SystemUser", "/system/user", null,
                "{\"title\":\"system.user.title\"}")
        );
        AuthUserMenuController controller = new AuthUserMenuController(
            identities(storedMenus, Optional.of(currentUser)),
            menuMapper());
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setAttribute(AuthorizationSubject.class.getName(),
            new AuthorizationSubject(100, 1_000, 1, 10L, 0, 0, false));

        ApiResponse<AuthUserMenuController.UserInfoResponse> response = controller.currentUser(request);

        assertThat(response.data().homePath()).isEqualTo("/system/user");
    }

    private static IdentityModels.Menu menu(long id, Long parentId, String type, String name, String metaJson) {
        return menu(id, parentId, type, name, "/" + name.toLowerCase(), null, metaJson);
    }

    private static IdentityModels.Menu menu(long id, Long parentId, String type, String name,
                                            String path, String redirect, String metaJson) {
        return new IdentityModels.Menu(id, parentId, type, name, path,
            "menu".equals(type) ? "/test/index" : null,
            redirect, null, metaJson, 1, 0);
    }

    private static VbenMenuTreeMapper menuMapper() {
        return new VbenMenuTreeMapper(new ObjectMapper(), new VbenMenuContract("/test/index"));
    }

    private static IdentityAdministrationService identities(List<IdentityModels.Menu> menus) {
        return identities(menus, Optional.empty());
    }

    private static IdentityAdministrationService identities(
        List<IdentityModels.Menu> menus,
        Optional<IdentityModels.CurrentUser> currentUser
    ) {
        IdentityQueryPort queries = new IdentityQueryPort() {
            @Override
            public Optional<IdentityModels.CurrentUser> findCurrentUser(long tenantId, long membershipId) {
                return currentUser;
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
