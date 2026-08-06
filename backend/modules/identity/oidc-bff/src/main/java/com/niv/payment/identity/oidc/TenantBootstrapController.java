package com.niv.payment.identity.oidc;

import com.niv.payment.identity.lifecycle.IdentityInvitationRepository;
import com.niv.payment.identity.lifecycle.TenantBootstrapCommand;
import com.niv.payment.identity.lifecycle.TenantBootstrapRepository;
import com.niv.payment.identity.lifecycle.TenantBootstrapService;
import com.niv.payment.identity.lifecycle.TenantType;
import com.niv.payment.permission.security.SaTokenSessionBridge;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/identity/tenant-bootstraps")
@ConditionalOnProperty(prefix = "payment.identity.lifecycle", name = "tenant-bootstrap-enabled",
    havingValue = "true")
final class TenantBootstrapController {
    private final TenantBootstrapService bootstraps;
    private final SaTokenSessionBridge sessions;
    private final OidcRequestTrace trace;

    TenantBootstrapController(TenantBootstrapService bootstraps,
                              SaTokenSessionBridge sessions,
                              OidcRequestTrace trace) {
        this.bootstraps = bootstraps;
        this.sessions = sessions;
        this.trace = trace;
    }

    @PostMapping
    Response create(@Valid @RequestBody Request request) {
        TenantBootstrapRepository.TenantBootstrap result = bootstraps.bootstrap(
            sessions.currentSubject(), new TenantBootstrapCommand(request.tenantCode(),
                request.tenantName(), TenantType.valueOf(request.tenantType()), request.entryHost(),
                request.firstAdministrator().email(), request.firstAdministrator().displayName(),
                request.idempotencyKey()));
        return new Response(0, new Bootstrap(Long.toString(result.tenantId()),
            Long.toString(result.invitationId()),
            Long.toString(result.firstAdministratorMembershipId()), result.status().name()),
            null, "success", trace.current());
    }

    record Request(
        @NotBlank @Pattern(regexp = "[a-z0-9][a-z0-9-]{1,62}") String tenantCode,
        @NotBlank @Size(max = 128) String tenantName,
        @NotBlank @Pattern(regexp = "AGENT|DIRECT_MERCHANT|INDIRECT_MERCHANT") String tenantType,
        @NotBlank @Size(max = 253) String entryHost,
        @NotNull @Valid FirstAdministrator firstAdministrator,
        @NotNull UUID idempotencyKey
    ) { }

    record FirstAdministrator(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(max = 128) String displayName
    ) { }

    record Bootstrap(String tenantId, String invitationId,
                     String firstAdministratorMembershipId, String status) { }
    record Response(int code, Bootstrap data, String error, String message, String traceId) { }
}
