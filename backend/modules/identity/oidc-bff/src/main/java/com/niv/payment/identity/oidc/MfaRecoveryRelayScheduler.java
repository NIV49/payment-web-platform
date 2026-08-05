package com.niv.payment.identity.oidc;

import org.springframework.scheduling.annotation.Scheduled;

final class MfaRecoveryRelayScheduler {
    private static final int MAX_BATCH = 25;
    private final MfaRecoveryRelay relay;

    MfaRecoveryRelayScheduler(MfaRecoveryRelay relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${payment.identity.lifecycle.poll-delay:PT5S}")
    void poll() {
        for (int processed = 0; processed < MAX_BATCH && relay.runOnce(); processed++) {
            // Bound each scheduler invocation so a busy realm cannot monopolize the scheduler thread.
        }
    }
}
