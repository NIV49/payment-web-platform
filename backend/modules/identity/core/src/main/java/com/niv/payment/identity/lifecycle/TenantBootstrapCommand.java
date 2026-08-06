package com.niv.payment.identity.lifecycle;

import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;

public record TenantBootstrapCommand(String tenantCode, String tenantName, TenantType tenantType,
                                     String entryHost, String firstAdministratorEmail,
                                     String firstAdministratorDisplayName, UUID idempotencyKey) {
    private static final Pattern TENANT_CODE = Pattern.compile("[a-z0-9][a-z0-9-]{1,62}");
    private static final Pattern ENTRY_HOST = Pattern.compile(
        "[a-z0-9]([a-z0-9-]*[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]*[a-z0-9])?)+");

    public TenantBootstrapCommand {
        tenantCode = tenantCode == null ? null : tenantCode.trim().toLowerCase(Locale.ROOT);
        if (tenantCode == null || !TENANT_CODE.matcher(tenantCode).matches()) {
            throw new IllegalArgumentException("tenantCode is invalid");
        }
        tenantName = MemberInvitationCommand.requireText(tenantName, "tenantName", 128);
        if (tenantType == null) {
            throw new IllegalArgumentException("tenantType is required");
        }
        entryHost = entryHost == null ? null : entryHost.trim().toLowerCase(Locale.ROOT);
        if (entryHost == null || entryHost.length() > 253 || !ENTRY_HOST.matcher(entryHost).matches()) {
            throw new IllegalArgumentException("entryHost is invalid");
        }
        firstAdministratorEmail = MemberInvitationCommand.normalizeEmail(firstAdministratorEmail);
        firstAdministratorDisplayName = MemberInvitationCommand.requireText(
            firstAdministratorDisplayName, "firstAdministratorDisplayName", 128);
        if (idempotencyKey == null) {
            throw new IllegalArgumentException("idempotencyKey is required");
        }
    }
}
