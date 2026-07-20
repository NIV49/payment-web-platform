package com.niv.payment.adminapi.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/** Validates the persisted string form consumed by Vben's backend-route converter. */
@Component
public final class VbenMenuContract {
    private static final String IFRAME_COMPONENT = "IFrameView";
    private static final Set<String> TYPES = Set.of("catalog", "menu", "embedded", "link", "button");
    private static final Pattern ROUTE_NAME = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{1,127}$");
    private static final Pattern LOCALE_KEY = Pattern.compile("^[a-z][A-Za-z0-9_-]*(?:\\.[A-Za-z0-9_-]+)+$");
    private static final Pattern AUTH_CODE = Pattern.compile("^[a-z][a-z0-9-]*:[a-z][a-z0-9-]*$");

    private final Set<String> allowedPageComponents;

    public VbenMenuContract(@Value("${payment.menu.allowed-page-components:}") String components) {
        this.allowedPageComponents = Set.copyOf(Arrays.stream(components.split(","))
            .map(String::trim).filter(value -> !value.isEmpty()).toList());
    }

    public void validate(String typeValue, String name, String path, String component, String redirect,
                         String authCode, Map<String, Object> meta) {
        String type = text(typeValue, "Menu type").toLowerCase(Locale.ROOT);
        if (!TYPES.contains(type)) throw invalid("Menu type");
        if (!ROUTE_NAME.matcher(text(name, "Route name")).matches()) throw invalid("Route name");

        String title = meta == null || !(meta.get("title") instanceof String value) ? null : value;
        if (title == null || !LOCALE_KEY.matcher(title).matches()) throw invalid("Menu title locale key");

        if (Set.of("catalog", "menu", "embedded", "link").contains(type)) validateRoutePath(path, "Route path");
        else if (!blank(path)) throw invalid("Route path");
        if (!blank(redirect)) validateRoutePath(redirect, "Redirect path");

        if ("menu".equals(type)) {
            if (!allowedPageComponents.contains(text(component, "Page component"))) throw invalid("Page component");
        } else if (Set.of("embedded", "link").contains(type)) {
            if (!IFRAME_COMPONENT.equals(text(component, "Iframe component"))) throw invalid("Iframe component");
        } else if (!blank(component)) {
            throw invalid("Page component");
        }

        if (!blank(authCode) && !AUTH_CODE.matcher(authCode).matches()) throw invalid("Permission code");
        if ("button".equals(type) && blank(authCode)) throw invalid("Permission code");

        if ("embedded".equals(type)) validateHttpUrl(meta.get("iframeSrc"), "iframeSrc");
        if ("link".equals(type)) validateHttpUrl(meta.get("link"), "link");
    }

    private static void validateRoutePath(String value, String label) {
        String path = text(value, label);
        if (!path.startsWith("/") || path.contains("..") || path.contains("\\")
            || path.contains("?") || path.contains("#") || path.chars().anyMatch(Character::isWhitespace)) {
            throw invalid(label);
        }
    }

    private static void validateHttpUrl(Object value, String label) {
        if (!(value instanceof String text) || text.isBlank()) throw invalid(label);
        try {
            URI uri = URI.create(text);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                || uri.getHost() == null) {
                throw invalid(label);
            }
        } catch (IllegalArgumentException invalidUri) {
            throw invalid(label);
        }
    }

    private static String text(String value, String label) {
        if (blank(value)) throw invalid(label);
        return value.trim();
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }

    private static IllegalArgumentException invalid(String label) {
        return new IllegalArgumentException(label + " does not match the Vben menu contract");
    }
}
