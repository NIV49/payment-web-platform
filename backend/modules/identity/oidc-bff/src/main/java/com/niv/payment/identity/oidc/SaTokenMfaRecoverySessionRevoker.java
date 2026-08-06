package com.niv.payment.identity.oidc;

import cn.dev33.satoken.stp.StpLogic;
import com.niv.payment.identity.lifecycle.MfaRecoveryTask;

import java.util.Objects;

final class SaTokenMfaRecoverySessionRevoker implements MfaRecoveryRelay.ApplicationSessionActions {
    private final StpLogic stpLogic;

    SaTokenMfaRecoverySessionRevoker(StpLogic stpLogic) {
        this.stpLogic = Objects.requireNonNull(stpLogic, "stpLogic");
    }

    @Override
    public void revoke(MfaRecoveryTask task) {
        try {
            for (Long membershipId : task.membershipIds()) {
                stpLogic.logout(membershipId);
            }
        } catch (RuntimeException exception) {
            throw new MfaRecoveryRelay.ApplicationSessionRevocationException(exception);
        }
    }
}
