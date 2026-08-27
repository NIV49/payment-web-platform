package com.niv.payment.identity.oidc;

import com.niv.payment.identity.lifecycle.IdentityInvitationRepository;
import com.niv.payment.identity.lifecycle.MemberInvitationCommand;
import com.niv.payment.identity.lifecycle.MemberInvitationService;
import com.niv.payment.permission.security.SaTokenSessionBridge;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/identity")
final class IdentityGovernanceController {
    private final MemberInvitationService invitations;
    private final SaTokenSessionBridge sessions;
    private final OidcRequestTrace trace;

    IdentityGovernanceController(MemberInvitationService invitations,
                                 SaTokenSessionBridge sessions,
                                 OidcRequestTrace trace) {
        this.invitations = invitations;
        this.sessions = sessions;
        this.trace = trace;
    }

    @PostMapping("/invitations")
    Response<Invitation> invite(@Valid @RequestBody InvitationRequest request) {
        List<Long> roleIds = request.roleIds().stream().map(Long::parseLong).toList();
        IdentityInvitationRepository.Invitation result = invitations.invite(
            sessions.currentSubject(), new MemberInvitationCommand(request.email(),
                request.displayName(), roleIds, request.idempotencyKey()));
        return success(new Invitation(Long.toString(result.invitationId()),
            Long.toString(result.membershipId()), result.status().name()));
    }

    private <T> Response<T> success(T data) {
        return new Response<>(0, data, null, "success", trace.current());
    }

    record InvitationRequest(
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank @Size(max = 128) String displayName,
        @NotEmpty @Size(max = 50) List<
            @NotBlank @Pattern(regexp = "[1-9][0-9]{0,18}") String> roleIds,
        @NotNull UUID idempotencyKey
    ) { }

    record Invitation(String invitationId, String membershipId, String status) { }
    record Response<T>(int code, T data, String error, String message, String traceId) { }
}
