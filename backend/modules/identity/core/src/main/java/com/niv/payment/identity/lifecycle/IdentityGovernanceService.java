package com.niv.payment.identity.lifecycle;

import com.niv.payment.permission.domain.AccountDomain;
import com.niv.payment.permission.domain.AuthorizationSubject;

import java.util.List;
import java.util.Objects;

public final class IdentityGovernanceService {
    private final AccountDomain accountDomain;
    private final IdentityGovernanceRepository repository;

    public IdentityGovernanceService(AccountDomain accountDomain,
                                     IdentityGovernanceRepository repository) {
        this.accountDomain = Objects.requireNonNull(accountDomain, "accountDomain");
        this.repository = Objects.requireNonNull(repository, "repository");
    }

    public IdentityGovernanceRepository.MemberPage members(AuthorizationSubject actor,
                                                           int page, int pageSize) {
        Objects.requireNonNull(actor, "actor");
        if (page < 1 || pageSize < 1 || pageSize > 100) {
            throw new IllegalArgumentException("Member pagination is invalid");
        }
        return repository.members(accountDomain, actor, page, pageSize);
    }

    public List<IdentityGovernanceRepository.InvitationRole> invitationRoles(
        AuthorizationSubject actor) {
        Objects.requireNonNull(actor, "actor");
        return repository.invitationRoles(accountDomain, actor);
    }
}
