package com.niv.payment.identity.lifecycle;

import com.niv.payment.permission.domain.AccountDomain;

public enum TenantType {
    PLATFORM,
    AGENT,
    DIRECT_MERCHANT,
    INDIRECT_MERCHANT;

    public AccountDomain accountDomain() {
        return AccountDomain.fromTenantType(name());
    }
}
