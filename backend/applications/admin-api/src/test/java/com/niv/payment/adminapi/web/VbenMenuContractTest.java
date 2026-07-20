package com.niv.payment.adminapi.web;

import org.junit.jupiter.api.Test;

import java.util.Map;

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
}
