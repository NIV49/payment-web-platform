package com.niv.payment.identity.lifecycle;

import com.niv.payment.permission.domain.AccountDomain;
import com.niv.payment.permission.domain.AuthorizationSubject;

import java.util.List;

public interface IdentityGovernanceRepository {
    MemberPage members(AccountDomain accountDomain, AuthorizationSubject actor,
                       int page, int pageSize);

    List<InvitationRole> invitationRoles(AccountDomain accountDomain,
                                         AuthorizationSubject actor);

    record MemberPage(List<Member> items, long total) {
        public MemberPage {
            items = List.copyOf(items);
            if (total < items.size()) {
                throw new IllegalArgumentException("Member page total is invalid");
            }
        }
    }

    record Member(long membershipId, String displayName, String membershipStatus,
                  String identityStatus, String provisioningStatus,
                  boolean systemAdministrator, boolean currentMembership) { }

    record InvitationRole(long roleId, String roleName) { }
}
