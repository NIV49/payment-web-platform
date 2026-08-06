package com.niv.payment.identity.oidc;

import com.niv.payment.identity.lifecycle.MfaRecoveryRepository;
import com.niv.payment.identity.lifecycle.MfaRecoveryService;
import com.niv.payment.permission.security.SaTokenSessionBridge;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/identity/mfa-recoveries")
final class MfaRecoveryController {
    private final MfaRecoveryService recoveries;
    private final SaTokenSessionBridge sessions;
    private final OidcRequestTrace trace;

    MfaRecoveryController(MfaRecoveryService recoveries, SaTokenSessionBridge sessions,
                          OidcRequestTrace trace) {
        this.recoveries = recoveries;
        this.sessions = sessions;
        this.trace = trace;
    }

    @PostMapping
    Response request(@Valid @RequestBody Request body) {
        long targetMembershipId = Long.parseLong(body.targetMembershipId());
        MfaRecoveryRepository.RecoveryRequest result = recoveries.request(
            sessions.currentSubject(), targetMembershipId, body.idempotencyKey());
        return new Response(0, new Recovery(Long.toString(result.recoveryId()), result.status().name()),
            null, "success", trace.current());
    }

    record Request(
        @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String targetMembershipId,
        @NotNull UUID idempotencyKey
    ) { }

    record Recovery(String recoveryId, String status) { }
    record Response(int code, Recovery data, String error, String message, String traceId) { }
}
