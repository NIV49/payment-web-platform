package com.niv.payment.permission.backoffice;

import com.niv.payment.permission.service.IdentityModels;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/** Maps persisted IAM menus to the executable Vben route contract and drops unsafe branches. */
public final class VbenMenuTreeMapper {
    private static final Logger LOG = LoggerFactory.getLogger(VbenMenuTreeMapper.class);

    private final ObjectMapper json;
    private final VbenMenuContract contract;

    public VbenMenuTreeMapper(ObjectMapper json, VbenMenuContract contract) {
        this.json = json;
        this.contract = contract;
    }

    public List<MenuRoute> map(List<IdentityModels.Menu> menus) {
        Map<Long, StoredMenu> candidates = new LinkedHashMap<>();
        for (IdentityModels.Menu menu : menus) {
            try {
                Map<String, Object> meta = json.readValue(menu.metaJson(), new TypeReference<>() { });
                contract.validate(menu.type(), menu.name(), menu.path(), menu.component(), menu.redirect(),
                    menu.authCode(), meta);
                candidates.put(menu.id(), new StoredMenu(menu, meta));
            } catch (JacksonException | IllegalArgumentException invalidStoredMenu) {
                LOG.warn("Suppressing unsafe stored menu branch: menuId={}, reason={}",
                    menu.id(), invalidStoredMenu.getClass().getSimpleName());
            }
        }

        Map<Long, StoredMenu> safeMenus = new LinkedHashMap<>();
        for (StoredMenu candidate : candidates.values()) {
            if (hasCompleteAncestorChain(candidate, candidates)) {
                safeMenus.put(candidate.source().id(), candidate);
            }
        }

        Map<Long, List<StoredMenu>> children = new LinkedHashMap<>();
        safeMenus.values().forEach(menu ->
            children.computeIfAbsent(menu.source().parentId(), ignored -> new ArrayList<>()).add(menu));
        Set<String> accessiblePaths = safeMenus.values().stream()
            .map(StoredMenu::source)
            .map(IdentityModels.Menu::path)
            .filter(path -> path != null)
            .collect(Collectors.toUnmodifiableSet());
        return safeMenus.values().stream()
            .filter(menu -> menu.source().parentId() == null)
            .map(menu -> route(menu, children, accessiblePaths))
            .toList();
    }

    public String resolveHomePath(String preferred, List<MenuRoute> menus) {
        if (containsPath(menus, preferred)) {
            return preferred;
        }
        return firstLeafPath(menus).orElse("/profile");
    }

    private MenuRoute route(StoredMenu item, Map<Long, List<StoredMenu>> children,
                            Set<String> accessiblePaths) {
        IdentityModels.Menu source = item.source();
        List<MenuRoute> nested = children.getOrDefault(source.id(), List.of()).stream()
            .map(child -> route(child, children, accessiblePaths))
            .toList();
        String redirect = source.redirect() != null && accessiblePaths.contains(source.redirect())
            ? source.redirect()
            : nested.stream().findFirst().map(MenuRoute::path).orElse(null);
        return new MenuRoute(Long.toString(source.id()),
            source.parentId() == null ? "0" : source.parentId().toString(),
            source.name(), source.path(), source.component(), redirect, source.authCode(), source.type(),
            item.meta(), source.status(), nested);
    }

    private static boolean hasCompleteAncestorChain(StoredMenu item, Map<Long, StoredMenu> candidates) {
        Set<Long> visited = new HashSet<>();
        StoredMenu current = item;
        while (current != null) {
            if (!visited.add(current.source().id())) {
                return false;
            }
            Long parentId = current.source().parentId();
            if (parentId == null) {
                return true;
            }
            current = candidates.get(parentId);
        }
        return false;
    }

    private static boolean containsPath(List<MenuRoute> menus, String path) {
        return path != null && menus.stream()
            .anyMatch(menu -> path.equals(menu.path()) || containsPath(menu.children(), path));
    }

    private static Optional<String> firstLeafPath(List<MenuRoute> menus) {
        for (MenuRoute menu : menus) {
            Optional<String> child = firstLeafPath(menu.children());
            if (child.isPresent()) {
                return child;
            }
            if (menu.path() != null) {
                return Optional.of(menu.path());
            }
        }
        return Optional.empty();
    }

    public record MenuRoute(String id, String pid, String name, String path, String component,
                            String redirect, String authCode, String type, Map<String, Object> meta,
                            int status, List<MenuRoute> children) { }

    private record StoredMenu(IdentityModels.Menu source, Map<String, Object> meta) { }
}
