package com.niv.payment.adminapi.web;

import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.ArrayList;
import java.util.LinkedHashMap;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class VbenMenuContractTest {
    private final VbenMenuContract contract = new VbenMenuContract(
        "/dashboard/analytics/index,/system/user/list,/system/role/list");

    @Test
    void acceptsCurrentVbenCatalogPageEmbeddedLinkAndButtonShapes() {
        assertThatCode(() -> contract.validate("catalog", "System", "/system", null, null, null,
            Map.of("title", "system.title"))).doesNotThrowAnyException();
        assertThatCode(() -> contract.validate("menu", "SystemUser", "/system/user", "/system/user/list",
            null, "user:view", Map.of("title", "system.user.title"))).doesNotThrowAnyException();
        assertThatCode(() -> contract.validate("embedded", "StatusPage", "/status", "IFrameView", null, null,
            Map.of("title", "page.status.title", "iframeSrc", "https://status.example.com")))
            .doesNotThrowAnyException();
        assertThatCode(() -> contract.validate("link", "Documentation", "/documentation", "IFrameView", null, null,
            Map.of("title", "page.docs.title", "link", "https://docs.example.com")))
            .doesNotThrowAnyException();
        assertThatCode(() -> contract.validate("button", "CreateUser", null, null, null, "user:create",
            Map.of("title", "common.create"))).doesNotThrowAnyException();
    }

    @Test
    void rejectsLiteralTitlesAndUnregisteredPageComponents() {
        assertThatThrownBy(() -> contract.validate("menu", "LiteralTitle", "/literal", "/system/user/list",
            null, null, Map.of("title", "Literal title"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> contract.validate("menu", "UnknownPage", "/unknown", "/unknown/index",
            null, null, Map.of("title", "page.unknown.title"))).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsLegacyLayoutComponentsAndUnsafeLinks() {
        assertThatThrownBy(() -> contract.validate("catalog", "LegacyCatalog", "/legacy", "BasicLayout",
            null, null, Map.of("title", "page.legacy.title"))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> contract.validate("link", "UnsafeLink", "/unsafe-link", "IFrameView", null, null,
            Map.of("title", "page.link.title", "link", "javascript:alert(1)")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> contract.validate("embedded", "MissingIframeComponent", "/embedded", null,
            null, null, Map.of("title", "page.embedded.title", "iframeSrc", "https://status.example.com")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> contract.validate("link", "MissingLinkPath", null, "IFrameView", null, null,
            Map.of("title", "page.link.title", "link", "https://docs.example.com")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsProtocolRelativeRouteAndRedirectPaths() {
        assertThatThrownBy(() -> contract.validate("menu", "ProtocolRelativePage", "//evil.example/path",
            "/system/user/list", null, null, Map.of("title", "system.user.title")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> contract.validate("menu", "ProtocolRelativeRedirect", "/safe",
            "/system/user/list", "//evil.example/path", null, Map.of("title", "system.user.title")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsExternalNavigationMetadataOnMenuTypesThatDoNotOwnIt() {
        assertThatThrownBy(() -> contract.validate("catalog", "UnsafeCatalog", "/unsafe-catalog", null,
            null, null, Map.of("title", "system.title", "iframeSrc", "https://evil.example")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> contract.validate("menu", "UnsafePage", "/unsafe-page", "/system/user/list",
            null, null, Map.of("title", "system.user.title", "link", "https://evil.example")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> contract.validate("button", "UnsafeButton", null, null,
            null, "user:create", Map.of("title", "common.create", "iframeSrc", "javascript:alert(1)")))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsMismatchedOrUnsafeExternalNavigationMetadata() {
        assertThatThrownBy(() -> contract.validate("embedded", "UnsafeEmbedded", "/unsafe-embedded",
            "IFrameView", null, null, Map.of(
                "title", "page.embedded.title",
                "iframeSrc", "https://status.example.com",
                "link", "https://evil.example")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> contract.validate("link", "UnsafeExternalLink", "/unsafe-external-link",
            "IFrameView", null, null, Map.of(
                "title", "page.link.title",
                "link", "https://docs.example.com",
                "iframeSrc", "https://evil.example")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> contract.validate("embedded", "JavascriptEmbedded", "/javascript-embedded",
            "IFrameView", null, null,
            Map.of("title", "page.embedded.title", "iframeSrc", "javascript:alert(1)")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> contract.validate("link", "DataLink", "/data-link",
            "IFrameView", null, null,
            Map.of("title", "page.link.title", "link", "data:text/html,pwned")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> contract.validate("link", "FileLink", "/file-link",
            "IFrameView", null, null,
            Map.of("title", "page.link.title", "link", "file:///etc/passwd")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> contract.validate("link", "ProtocolRelativeLink", "/protocol-relative-link",
            "IFrameView", null, null,
            Map.of("title", "page.link.title", "link", "//evil.example")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> contract.validate("link", "MissingHostLink", "/missing-host-link",
            "IFrameView", null, null,
            Map.of("title", "page.link.title", "link", "https:///missing-host")))
            .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> contract.validate("embedded", "NumericIframe", "/numeric-iframe",
            "IFrameView", null, null,
            Map.of("title", "page.embedded.title", "iframeSrc", 42)))
            .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsOversizedOrUnsupportedMetadata() {
        Map<String, Object> tooManyItems = new LinkedHashMap<>();
        tooManyItems.put("title", "system.user.title");
        for (int index = 0; index < 32; index++) tooManyItems.put("key" + index, index);
        assertInvalidMeta(tooManyItems);

        assertInvalidMeta(Map.of("title", "system.user.title", "k".repeat(65), true));
        assertInvalidMeta(Map.of("title", "system.user.title", "description", "x".repeat(1_025)));
        assertInvalidMeta(Map.of("title", "system.user.title", "unsupported", new Object()));

        ArrayList<Object> tooManyListItems = new ArrayList<>();
        for (int index = 0; index < 33; index++) tooManyListItems.add(index);
        assertInvalidMeta(Map.of("title", "system.user.title", "items", tooManyListItems));
    }

    @Test
    void rejectsMetadataNestedBeyondFourContainerLevels() {
        Map<String, Object> nested = Map.of("leaf", "value");
        for (int depth = 0; depth < 5; depth++) nested = Map.of("nested", nested);
        assertInvalidMeta(Map.of("title", "system.user.title", "options", nested));
    }

    private void assertInvalidMeta(Map<String, Object> meta) {
        assertThatThrownBy(() -> contract.validate("menu", "BoundedMeta", "/bounded-meta",
            "/system/user/list", null, null, meta))
            .isInstanceOf(IllegalArgumentException.class);
    }
}
