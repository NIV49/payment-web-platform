package com.niv.payment.permission.domain;

import java.util.Locale;

public enum AccountDomain {
    PLATFORM("platform-admin", "PAYMENT_PLATFORM_SESSION", "backoffice:platform-access"),
    MERCHANT("merchant-admin", "PAYMENT_MERCHANT_SESSION", "backoffice:merchant-access"),
    AGENT("agent-admin", "PAYMENT_AGENT_SESSION", "backoffice:agent-access");

    private final String loginType;
    private final String cookieName;
    private final String accessPermissionCode;

    AccountDomain(String loginType, String cookieName, String accessPermissionCode) {
        this.loginType = loginType;
        this.cookieName = cookieName;
        this.accessPermissionCode = accessPermissionCode;
    }

    public static AccountDomain fromTenantType(String tenantType) {
        return switch (tenantType) {
            case "PLATFORM" -> PLATFORM;
            case "DIRECT_MERCHANT", "INDIRECT_MERCHANT" -> MERCHANT;
            case "AGENT" -> AGENT;
            default -> throw new IllegalArgumentException("Unsupported tenant type: " + tenantType);
        };
    }

    public String cacheNamespace() {
        return name().toLowerCase(Locale.ROOT);
    }

    public String loginType() {
        return loginType;
    }

    public String cookieName() {
        return cookieName;
    }

    public String accessPermissionCode() {
        return accessPermissionCode;
    }
}
