package com.niv.payment.permission.domain;

import java.util.Objects;

public record AuthorizationDecision(boolean allowed, DecisionReason reason, Long matchedGrantId) {
    public AuthorizationDecision {
        Objects.requireNonNull(reason, "reason");
        if (allowed != (reason == DecisionReason.ALLOWED)) {
            throw new IllegalArgumentException("Allowed flag and decision reason are inconsistent");
        }
    }

    public static AuthorizationDecision allow(long grantId) {
        return new AuthorizationDecision(true, DecisionReason.ALLOWED, grantId);
    }

    public static AuthorizationDecision deny(DecisionReason reason) {
        if (reason == DecisionReason.ALLOWED) {
            throw new IllegalArgumentException("An allowed reason cannot be used for denial");
        }
        return new AuthorizationDecision(false, reason, null);
    }
}
