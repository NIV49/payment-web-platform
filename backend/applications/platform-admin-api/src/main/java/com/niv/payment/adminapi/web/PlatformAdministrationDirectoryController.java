package com.niv.payment.adminapi.web;

import com.niv.payment.permission.domain.AccountDomain;
import com.niv.payment.permission.domain.AdministrationActor;
import com.niv.payment.permission.domain.AuthorizationSubject;
import com.niv.payment.permission.port.PlatformAdministrationDirectoryPort;
import com.niv.payment.permission.port.PlatformAdministrationDirectoryPort.DirectoryMenu;
import com.niv.payment.permission.port.PlatformAdministrationDirectoryPort.DirectoryRole;
import com.niv.payment.permission.service.IdentityModels;
import com.niv.payment.permission.service.PlatformAdministrationDirectoryService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** PLATFORM-only read API for tenant-bound role and menu directories. */
@RestController
@RequestMapping("/api/platform")
public final class PlatformAdministrationDirectoryController {
    private final PlatformAdministrationDirectoryService directory;
    private final ObjectMapper json;
    private final ZoneId queryZone;

    public PlatformAdministrationDirectoryController(
            PlatformAdministrationDirectoryService directory,
            ObjectMapper json,
            @Value("${payment.time-zone}") String timeZone) {
        this.directory = directory;
        this.json = json;
        this.queryZone = ZoneId.of(timeZone);
    }

    @GetMapping("/role-directory")
    ApiResponse<PageResponse<RoleResponse>> roles(
            @RequestParam Map<String, String> query,
            HttpServletRequest request) {
        AuthorizationSubject subject = AuthUserMenuController.subject(request);
        var criteria = new IdentityModels.RoleQuery(
            query.get("name"), nullableLong(query.get("id")), nullableInteger(query.get("status")),
            query.get("remark"), parseTime(query.get("startTime"), false),
            parseTime(query.get("endTime"), true), integer(query.get("page"), 1),
            integer(query.get("pageSize"), 20));
        var result = directory.findRoles(
            subject.tenantId(), actor(subject),
            new PlatformAdministrationDirectoryPort.RoleDirectoryQuery(
                accountDomain(query.getOrDefault("accountDomain", "PLATFORM")),
                nullableLong(query.get("tenantId")), criteria));
        return ApiResponse.success(new PageResponse<>(
            result.items().stream().map(PlatformAdministrationDirectoryController::role).toList(),
            result.total()));
    }

    @GetMapping("/menu-directory")
    ApiResponse<List<MenuResponse>> menus(
            @RequestParam Map<String, String> query,
            HttpServletRequest request) {
        AuthorizationSubject subject = AuthUserMenuController.subject(request);
        var result = directory.findMenus(
            subject.tenantId(), actor(subject),
            new PlatformAdministrationDirectoryPort.MenuDirectoryQuery(
                accountDomain(query.getOrDefault("accountDomain", "PLATFORM")),
                nullableLong(query.get("tenantId")),
                Boolean.parseBoolean(query.getOrDefault("selectableOnly", "false"))));
        return ApiResponse.success(menuTree(result));
    }

    private static RoleResponse role(DirectoryRole directoryRole) {
        var scope = directoryRole.scope();
        var role = directoryRole.role();
        return new RoleResponse(
            scope.accountDomain(), Long.toString(scope.tenantId()), scope.tenantName(),
            scope.managementMode(), Long.toString(role.id()), role.name(),
            role.menuIds().stream().map(String::valueOf).toList(), role.status(), role.remark(),
            role.rowVersion(), role.systemRole(), role.assignable(), role.createdAt().toString());
    }

    private List<MenuResponse> menuTree(List<DirectoryMenu> rows) {
        Map<Long, List<DirectoryMenu>> grouped = new LinkedHashMap<>();
        Set<Long> ids = rows.stream().map(row -> row.menu().id())
            .collect(java.util.stream.Collectors.toSet());
        rows.forEach(row -> grouped.computeIfAbsent(row.menu().parentId(), ignored -> new ArrayList<>())
            .add(row));
        return rows.stream()
            .filter(row -> row.menu().parentId() == null || !ids.contains(row.menu().parentId()))
            .map(row -> menuNode(row, grouped))
            .toList();
    }

    private MenuResponse menuNode(
            DirectoryMenu directoryMenu,
            Map<Long, List<DirectoryMenu>> grouped) {
        var scope = directoryMenu.scope();
        var menu = directoryMenu.menu();
        Map<String, Object> meta;
        try {
            meta = json.readValue(menu.metaJson(), new TypeReference<>() { });
        } catch (JacksonException invalid) {
            throw new IllegalStateException("Stored menu metadata is invalid", invalid);
        }
        List<MenuResponse> children = grouped.getOrDefault(menu.id(), List.of()).stream()
            .map(child -> menuNode(child, grouped))
            .toList();
        return new MenuResponse(
            scope.accountDomain(), Long.toString(scope.tenantId()), scope.tenantName(),
            scope.managementMode(), Long.toString(menu.id()), id(menu.parentId()), menu.type(),
            menu.name(), menu.path(), menu.component(), menu.redirect(), menu.authCode(), meta,
            menu.status(), menu.rowVersion(), menu.systemManaged(), children);
    }

    private Instant parseTime(String value, boolean endOfDay) {
        if (value == null || value.isBlank()) return null;
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            // Continue with the API's accepted local date formats.
        }
        try {
            return OffsetDateTime.parse(value).toInstant();
        } catch (DateTimeParseException ignored) {
            // Continue with the API's accepted local date formats.
        }
        try {
            return LocalDateTime.parse(value, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"))
                .atZone(queryZone).toInstant();
        } catch (DateTimeParseException ignored) {
            // Continue with a date-only value.
        }
        try {
            LocalDate date = LocalDate.parse(value);
            return (endOfDay
                ? date.plusDays(1).atStartOfDay(queryZone).minusNanos(1)
                : date.atStartOfDay(queryZone)).toInstant();
        } catch (DateTimeParseException invalid) {
            throw new IllegalArgumentException("Invalid time query", invalid);
        }
    }

    private static AdministrationActor actor(AuthorizationSubject subject) {
        return AdministrationActor.from(subject);
    }

    private static AccountDomain accountDomain(String value) {
        try {
            return AccountDomain.valueOf(value);
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid accountDomain", invalid);
        }
    }

    private static int integer(String value, int fallback) {
        return value == null ? fallback : Integer.parseInt(value);
    }

    private static Integer nullableInteger(String value) {
        return value == null || value.isBlank() ? null : Integer.valueOf(value);
    }

    private static Long nullableLong(String value) {
        return value == null || value.isBlank() ? null : Long.valueOf(value);
    }

    private static String id(Long value) {
        return value == null ? "0" : value.toString();
    }

    record PageResponse<T>(List<T> items, long total) { }

    record RoleResponse(
        AccountDomain accountDomain,
        String tenantId,
        String tenantName,
        PlatformAdministrationDirectoryPort.ManagementMode managementMode,
        String id,
        String name,
        List<String> menuIds,
        int status,
        String remark,
        long rowVersion,
        boolean systemRole,
        boolean assignable,
        String createTime) { }

    record MenuResponse(
        AccountDomain accountDomain,
        String tenantId,
        String tenantName,
        PlatformAdministrationDirectoryPort.ManagementMode managementMode,
        String id,
        String pid,
        String type,
        String name,
        String path,
        String component,
        String redirect,
        String authCode,
        Map<String, Object> meta,
        int status,
        long rowVersion,
        boolean systemManaged,
        List<MenuResponse> children) { }
}
