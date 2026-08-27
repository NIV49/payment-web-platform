package com.niv.payment.identity.oidc;

import com.niv.payment.identity.lifecycle.IdentityGovernanceRepository;
import com.niv.payment.identity.lifecycle.IdentityGovernanceService;
import com.niv.payment.permission.security.SaTokenSessionBridge;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/identity")
final class IdentityGovernanceQueryController {
    private final IdentityGovernanceService governance;
    private final SaTokenSessionBridge sessions;
    private final OidcRequestTrace trace;

    IdentityGovernanceQueryController(IdentityGovernanceService governance,
                                      SaTokenSessionBridge sessions,
                                      OidcRequestTrace trace) {
        this.governance = governance;
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

    private <T> Response<T> success(T data) {
        return new Response<>(0, data, null, "success", trace.current());
    }

    record Member(String membershipId, String displayName, String membershipStatus,
                  String identityStatus, String provisioningStatus,
                  boolean systemAdministrator, boolean currentMembership) { }
    record MemberPage(List<Member> items, long total) { }
    record Role(String roleId, String roleName) { }
    record RoleList(List<Role> items) { }
    record Response<T>(int code, T data, String error, String message, String traceId) { }
}
