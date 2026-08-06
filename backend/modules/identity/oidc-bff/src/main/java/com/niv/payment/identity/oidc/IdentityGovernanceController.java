package com.niv.payment.identity.oidc;

import com.niv.payment.identity.lifecycle.IdentityGovernanceRepository;
import com.niv.payment.identity.lifecycle.IdentityGovernanceService;
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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/identity")
final class IdentityGovernanceController {
    private final IdentityGovernanceService governance;
    private final MemberInvitationService invitations;
    private final SaTokenSessionBridge sessions;
    private final OidcRequestTrace trace;

    IdentityGovernanceController(IdentityGovernanceService governance,
                                 MemberInvitationService invitations,
                                 SaTokenSessionBridge sessions,
                                 OidcRequestTrace trace) {
        this.governance = governance;
        this.invitations = invitations;
        this.sessions = sessions;
        this.trace = trace;
    }

    @GetMapping("/members")
    Response<MemberPage> members(@RequestParam(defaultValue = "1") int page,
                                 @RequestParam(defaultValue = "20") int pageSize) {
        IdentityGovernanceRepository.MemberPage result = governance.members(
            sessions.currentSubject(), page, pageSize);
        List<Member> items = result.items().stream().map(member -> new Member(
            Long.toString(member.membershipId()), member.displayName(), member.membershipStatus(),
            member.identityStatus(), member.provisioningStatus(), member.systemAdministrator(),
            member.currentMembership()))
            .toList();
        return success(new MemberPage(items, result.total()));
    }

    @GetMapping("/invitation-roles")
    Response<RoleList> invitationRoles() {
        List<Role> roles = governance.invitationRoles(sessions.currentSubject()).stream()
            .map(role -> new Role(Long.toString(role.roleId()), role.roleName())).toList();
        return success(new RoleList(roles));
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

    record Member(String membershipId, String displayName, String membershipStatus,
                  String identityStatus, String provisioningStatus,
                  boolean systemAdministrator, boolean currentMembership) { }
    record MemberPage(List<Member> items, long total) { }
    record Role(String roleId, String roleName) { }
    record RoleList(List<Role> items) { }
    record Invitation(String invitationId, String membershipId, String status) { }
    record Response<T>(int code, T data, String error, String message, String traceId) { }
}
