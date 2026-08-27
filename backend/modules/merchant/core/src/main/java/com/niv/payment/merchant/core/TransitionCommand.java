package com.niv.payment.merchant.core;

import java.util.UUID;

public record TransitionCommand(long merchantId, MerchantCommand command, String reasonCode,
                                long expectedVersion, UUID idempotencyKey) {
    public TransitionCommand {
        if (merchantId <= 0 || command == null || idempotencyKey == null || expectedVersion < 0
            || command == MerchantCommand.SUBMIT || command == MerchantCommand.RESUBMIT) {
            throw new MerchantException.InvalidRequest("Invalid merchant transition command");
        }
        reasonCode = MerchantLifecyclePolicy.reason(command, reasonCode);
    }
}
