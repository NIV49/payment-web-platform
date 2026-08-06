package com.niv.payment.identity.oidc;

import org.springframework.scheduling.annotation.Scheduled;

final class IdentityInvitationRelayScheduler {
    private final IdentityInvitationRelay relay;

    IdentityInvitationRelayScheduler(IdentityInvitationRelay relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${payment.identity.lifecycle.poll-delay:PT5S}")
    void poll() {
        relay.runOnce();
    }
}
