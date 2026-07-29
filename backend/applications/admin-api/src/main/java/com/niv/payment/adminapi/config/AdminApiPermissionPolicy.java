package com.niv.payment.adminapi.config;

import com.niv.payment.adminapi.web.AccessDeniedException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

/** Explicit method/path policy for the browser administration API. Unknown routes fail closed. */
@Component
public final class AdminApiPermissionPolicy {
    private static final Pattern USER_ITEM = Pattern.compile("^/api/system/user/[1-9][0-9]*$");
    private static final Pattern USER_STATUS = Pattern.compile("^/api/system/user/[1-9][0-9]*/status$");
    private static final Pattern ROLE_ITEM = Pattern.compile("^/api/system/role/[1-9][0-9]*$");
    private static final Pattern ROLE_STATUS = Pattern.compile("^/api/system/role/[1-9][0-9]*/status$");
    private static final Pattern MENU_ITEM = Pattern.compile("^/api/system/menu/[1-9][0-9]*$");
    private static final Pattern DEPARTMENT_ITEM = Pattern.compile("^/api/system/dept/[1-9][0-9]*$");

    public boolean isPublic(String method, String path) {
        return "POST".equals(method) && "/api/auth/login".equals(path);
    }

    public List<String> requiredPermissions(String method, String path) {
        if (sessionOnly(method, path)) return List.of();

        if ("GET".equals(method) && "/api/system/user/list".equals(path)) return List.of("user:view");
        if ("POST".equals(method) && "/api/system/user".equals(path)) return List.of("user:create");
        if ("PUT".equals(method) && USER_ITEM.matcher(path).matches()) {
            return List.of("user:update", "user:disable", "user:assign-role");
        }
        if ("PATCH".equals(method) && USER_STATUS.matcher(path).matches()) return List.of("user:disable");
        if ("DELETE".equals(method) && USER_ITEM.matcher(path).matches()) return List.of("user:delete");

        if ("GET".equals(method) && "/api/system/role/list".equals(path)) return List.of("role:view");
        if ("POST".equals(method) && "/api/system/role".equals(path)) return List.of("role:create");
        if (("PUT".equals(method) && ROLE_ITEM.matcher(path).matches())
            || ("PATCH".equals(method) && ROLE_STATUS.matcher(path).matches())) {
            return List.of("role:update");
        }
        if ("DELETE".equals(method) && ROLE_ITEM.matcher(path).matches()) return List.of("role:delete");

        if ("GET".equals(method) && ("/api/system/menu/list".equals(path)
            || "/api/system/menu/name-exists".equals(path) || "/api/system/menu/path-exists".equals(path))) {
            return List.of("menu:view");
        }
        if ("POST".equals(method) && "/api/system/menu".equals(path)) return List.of("menu:manage");
        if (("PUT".equals(method) || "DELETE".equals(method)) && MENU_ITEM.matcher(path).matches()) {
            return List.of("menu:manage");
        }

        if ("GET".equals(method) && "/api/system/dept/list".equals(path)) return List.of("department:view");
        if ("POST".equals(method) && "/api/system/dept".equals(path)) return List.of("department:manage");
        if (("PUT".equals(method) || "DELETE".equals(method)) && DEPARTMENT_ITEM.matcher(path).matches()) {
            return List.of("department:manage");
        }

        throw new AccessDeniedException();
    }

    private static boolean sessionOnly(String method, String path) {
        return ("POST".equals(method) && "/api/auth/logout".equals(path))
            || ("GET".equals(method) && ("/api/user/info".equals(path)
                || "/api/auth/codes".equals(path) || "/api/menu/all".equals(path)));
    }
}
