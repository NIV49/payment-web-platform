package com.niv.payment.adminapi.web;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.List;
import java.util.regex.Pattern;

/** Validates the persisted string form consumed by Vben's backend-route converter. */
@Component
public final class VbenMenuContract {
    private static final String IFRAME_COMPONENT = "IFrameView";
    private static final Set<String> TYPES = Set.of("catalog", "menu", "embedded", "link", "button");
    private static final Pattern ROUTE_NAME = Pattern.compile("^[A-Za-z][A-Za-z0-9_]{1,127}$");
    private static final Pattern LOCALE_KEY = Pattern.compile("^[a-z][A-Za-z0-9_-]*(?:\\.[A-Za-z0-9_-]+)+$");
    private static final Pattern AUTH_CODE = Pattern.compile("^[a-z][a-z0-9-]*:[a-z][a-z0-9-]*$");
    // Keep this grammar aligned with V11__enforce_menu_external_navigation_safety.sql.
    // Deliberately exclude credentials and non-ASCII host spellings; punycode remains supported.
    private static final Pattern ABSOLUTE_HTTP_URL = Pattern.compile(
        "^https?://(?:[A-Za-z0-9](?:[A-Za-z0-9.-]*[A-Za-z0-9])?|\\[[0-9A-Fa-f:.]+\\])"
            + "(?::[0-9]{1,5})?(?:[/?#][^\\p{Cntrl}\\s]*)?$",
        Pattern.CASE_INSENSITIVE);
    private static final int MAX_META_CONTAINER_ITEMS = 32;
    private static final int MAX_META_KEY_LENGTH = 64;
    private static final int MAX_META_STRING_LENGTH = 1_024;
    private static final int MAX_META_DEPTH = 4;
    private static final int MAX_META_VALUES = 128;

    private final Set<String> allowedPageComponents;

    public VbenMenuContract(@Value("${payment.menu.allowed-page-components:}") String components) {
        this.allowedPageComponents = Set.copyOf(Arrays.stream(components.split(","))
            .map(String::trim).filter(value -> !value.isEmpty()).toList());
    }

    public void validate(String typeValue, String name, String path, String component, String redirect,
                         String authCode, Map<String, Object> meta) {
        String type = menuType(typeValue);
        if (!ROUTE_NAME.matcher(text(name, "Route name")).matches()) throw invalid("Route name");

        validateMetadata(meta);

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

        validateExternalNavigation(type, meta);
    }

    /**
     * Revalidates persisted metadata before it crosses the backend-route trust boundary.
     * Full route validation is intentionally write-only; historical display text is not
     * executable, while external navigation fields are.
     */
    void validateStoredMetadata(String typeValue, Map<String, Object> meta) {
        String type = menuType(typeValue);
        validateMetadata(meta);
        validateExternalNavigation(type, meta);
    }

    private static String menuType(String typeValue) {
        String type = text(typeValue, "Menu type").toLowerCase(Locale.ROOT);
        if (!TYPES.contains(type)) throw invalid("Menu type");
        return type;
    }

    private static void validateExternalNavigation(String type, Map<String, Object> meta) {
        boolean hasIframeSource = meta.containsKey("iframeSrc");
        boolean hasLink = meta.containsKey("link");
        if ("embedded".equals(type)) {
            if (hasLink) throw invalid("link");
            validateHttpUrl(meta.get("iframeSrc"), "iframeSrc");
        } else if ("link".equals(type)) {
            if (hasIframeSource) throw invalid("iframeSrc");
            validateHttpUrl(meta.get("link"), "link");
        } else if (hasIframeSource || hasLink) {
            throw invalid("External navigation metadata");
        }
    }

    private static void validateMetadata(Map<String, Object> meta) {
        if (meta == null) throw invalid("Menu metadata");
        int[] values = {0};
        validateMap(meta, 0, values);
    }

    private static void validateMap(Map<?, ?> values, int depth, int[] valueCount) {
        validateContainer(values.size(), depth);
        for (Map.Entry<?, ?> entry : values.entrySet()) {
            if (!(entry.getKey() instanceof String key) || key.isBlank() || key.length() > MAX_META_KEY_LENGTH) {
                throw invalid("Menu metadata key");
            }
            validateValue(entry.getValue(), depth, valueCount);
        }
    }

    private static void validateList(List<?> values, int depth, int[] valueCount) {
        validateContainer(values.size(), depth);
        for (Object value : values) validateValue(value, depth, valueCount);
    }

    private static void validateContainer(int size, int depth) {
        if (size > MAX_META_CONTAINER_ITEMS || depth > MAX_META_DEPTH) {
            throw invalid("Menu metadata structure");
        }
    }

    private static void validateValue(Object value, int depth, int[] valueCount) {
        if (++valueCount[0] > MAX_META_VALUES) throw invalid("Menu metadata size");
        if (value == null || value instanceof Boolean) return;
        if (value instanceof String text) {
            if (text.length() > MAX_META_STRING_LENGTH) throw invalid("Menu metadata value");
            return;
        }
        if (value instanceof Number number) {
            boolean nonFiniteDouble = number instanceof Double decimal && !Double.isFinite(decimal);
            boolean nonFiniteFloat = number instanceof Float decimal && !Float.isFinite(decimal);
            if (nonFiniteDouble || nonFiniteFloat || number.toString().length() > 64) {
                throw invalid("Menu metadata number");
            }
            return;
        }
        if (value instanceof Map<?, ?> nested) {
            validateMap(nested, depth + 1, valueCount);
            return;
        }
        if (value instanceof List<?> nested) {
            validateList(nested, depth + 1, valueCount);
            return;
        }
        throw invalid("Menu metadata value type");
    }

    private static void validateRoutePath(String value, String label) {
        String path = text(value, label);
        if (!path.startsWith("/") || path.startsWith("//") || path.contains("..") || path.contains("\\")
            || path.contains("?") || path.contains("#") || path.chars().anyMatch(Character::isWhitespace)) {
            throw invalid(label);
        }
    }

    private static void validateHttpUrl(Object value, String label) {
        if (!(value instanceof String text) || !ABSOLUTE_HTTP_URL.matcher(text).matches()) throw invalid(label);
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
