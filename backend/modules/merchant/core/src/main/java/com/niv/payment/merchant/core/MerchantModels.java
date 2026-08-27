package com.niv.payment.merchant.core;

import java.time.OffsetDateTime;
import java.util.List;

public final class MerchantModels {
    private MerchantModels() { }

    public record LastDecision(String decision, String reasonCode, OffsetDateTime decidedAt,
                               Long decidedByMembershipId) { }
}
