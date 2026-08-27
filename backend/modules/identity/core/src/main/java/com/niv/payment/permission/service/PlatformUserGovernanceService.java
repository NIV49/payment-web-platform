package com.niv.payment.permission.service;

import com.niv.payment.permission.domain.AccountDomain;
import com.niv.payment.permission.domain.AdministrationActor;
import com.niv.payment.permission.port.PlatformUserGovernancePort;

import java.util.List;
import java.util.Objects;

/** Validates the narrow PLATFORM control-plane contract before invoking persistence. */
public final class PlatformUserGovernanceService {
    private final PlatformUserGovernancePort port;

    public PlatformUserGovernanceService(PlatformUserGovernancePort port) {
        this.port = Objects.requireNonNull(port, "port");
    }

    public IdentityModels.Page<PlatformUserGovernancePort.DirectoryUser> findUsers(
        long sourceTenantId, AdministrationActor actor,
        PlatformUserGovernancePort.DirectoryQuery query) {
        Objects.requireNonNull(query, "query");
        AccountDomain domain = Objects.requireNonNull(query.accountDomain(), "accountDomain");
        if (query.tenantId() != null) positive(query.tenantId(), "tenantId");
        if (query.departmentId() != null) {
            positive(query.departmentId(), "departmentId");
            if (domain != AccountDomain.PLATFORM) {
                throw invalid("Department filtering is limited to PLATFORM accounts");
            }
        }
        if (query.status() != null) status(query.status());
        if (query.page() < 1 || query.pageSize() < 1 || query.pageSize() > 200) {
            throw invalid("Invalid directory paging");
        }
        long offset = (long) (query.page() - 1) * query.pageSize();
        if (offset > Integer.MAX_VALUE) throw invalid("Directory offset is too large");
        return port.findUsers(positive(sourceTenantId, "sourceTenantId"), actor,
            new PlatformUserGovernancePort.DirectoryQuery(
                domain, query.tenantId(), query.departmentId(),
                trim(query.email()), trim(query.name()),
                query.status(), query.page(), query.pageSize()));
    }

    public List<PlatformUserGovernancePort.TenantOption> findTargetTenants(
        long sourceTenantId, AdministrationActor actor, AccountDomain accountDomain) {
        return port.findTargetTenants(positive(sourceTenantId, "sourceTenantId"), actor,
            writableDomain(accountDomain));
    }

    public long createTenantAdministrator(
        long sourceTenantId, AdministrationActor actor,
        PlatformUserGovernancePort.CreateAdministratorCommand command) {
        Objects.requireNonNull(command, "command");
        String email = LoginEmailPolicy.normalize(command.email());
        String name = required(command.name(), "name");
        status(command.status());
        return port.createTenantAdministrator(positive(sourceTenantId, "sourceTenantId"), actor,
            new PlatformUserGovernancePort.CreateAdministratorCommand(
                writableDomain(command.accountDomain()), positive(command.tenantId(), "tenantId"),
                email, name, command.status()));
    }

    public void updateTenantAdministrator(
        long sourceTenantId, AdministrationActor actor, long userId,
        PlatformUserGovernancePort.UpdateAdministratorCommand command) {
        Objects.requireNonNull(command, "command");
        String email = LoginEmailPolicy.normalize(command.email());
        String name = required(command.name(), "name");
        status(command.status());
        version(command.membershipVersion());
        version(command.identityVersion());
        version(command.credentialVersion());
        port.updateTenantAdministrator(positive(sourceTenantId, "sourceTenantId"), actor,
            positive(userId, "userId"), new PlatformUserGovernancePort.UpdateAdministratorCommand(
                writableDomain(command.accountDomain()), positive(command.tenantId(), "tenantId"),
                email, name, command.status(), command.membershipVersion(),
                command.identityVersion(), command.credentialVersion()));
    }

    public IdentityModels.PasswordResetResult resetUserPassword(
        long sourceTenantId, AdministrationActor actor, long userId,
        PlatformUserGovernancePort.PasswordResetCommand command) {
        Objects.requireNonNull(command, "command");
        return port.resetUserPassword(positive(sourceTenantId, "sourceTenantId"), actor,
            positive(userId, "userId"), new PlatformUserGovernancePort.PasswordResetCommand(
                writableDomain(command.accountDomain()), positive(command.tenantId(), "tenantId"),
                validVersion(command.credentialVersion()),
                LocalPasswordPolicy.requireValid(command.password())));
    }

    private static AccountDomain writableDomain(AccountDomain domain) {
        if (domain != AccountDomain.MERCHANT && domain != AccountDomain.AGENT) {
            throw invalid("Only MERCHANT or AGENT can be a tenant administrator target");
        }
        return domain;
    }

    private static long positive(long value, String label) {
        if (value <= 0) throw invalid(label + " must be positive");
        return value;
    }

    private static void version(long value) {
        if (value < 0) throw invalid("Version must not be negative");
    }

    private static long validVersion(long value) {
        version(value);
        return value;
    }

    private static void status(int value) {
        if (value != 0 && value != 1) throw invalid("Status must be 0 or 1");
    }

    private static String required(String value, String label) {
        if (value == null || value.isBlank()) throw invalid(label + " is required");
        String normalized = value.trim();
        if (normalized.length() > 128) throw invalid(label + " is too long");
        return normalized;
    }

    private static String trim(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static IdentityAdministrationService.InvalidCommandException invalid(String message) {
        return new IdentityAdministrationService.InvalidCommandException(message);
    }
}
