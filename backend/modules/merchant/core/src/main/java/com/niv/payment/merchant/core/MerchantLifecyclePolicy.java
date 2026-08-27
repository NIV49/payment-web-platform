package com.niv.payment.merchant.core;

import java.util.Map;
import java.util.Set;

public final class MerchantLifecyclePolicy {
    private static final Map<MerchantCommand, Set<String>> REASONS = Map.of(
        MerchantCommand.APPROVE, Set.of("PROFILE_VERIFIED"),
        MerchantCommand.REJECT, Set.of("PROFILE_MISMATCH", "REGISTRATION_UNVERIFIED", "COMPLIANCE_REJECTED"),
        MerchantCommand.DISABLE, Set.of("COMPLIANCE_HOLD", "RISK_CONTROL"),
        MerchantCommand.ENABLE, Set.of("COMPLIANCE_CLEARED", "RISK_CLEARED"),
        MerchantCommand.TERMINATE, Set.of("BUSINESS_CLOSED", "COMPLIANCE_TERMINATION")
    );

    private MerchantLifecyclePolicy() {
    }

    public static MerchantStatus next(MerchantStatus current, MerchantCommand command) {
        if (command == null) throw new MerchantException.InvalidRequest("command is required");
        if (current == null && (command == MerchantCommand.SUBMIT
            || command == MerchantCommand.CREATE)) return MerchantStatus.PENDING_REVIEW;
        if (current == MerchantStatus.PENDING_REVIEW && command == MerchantCommand.APPROVE) return MerchantStatus.ACTIVE;
        if (current == MerchantStatus.PENDING_REVIEW && command == MerchantCommand.REJECT) return MerchantStatus.REVIEW_REJECTED;
        if (current == MerchantStatus.REVIEW_REJECTED && command == MerchantCommand.RESUBMIT) return MerchantStatus.PENDING_REVIEW;
        if (current == MerchantStatus.ACTIVE && command == MerchantCommand.DISABLE) return MerchantStatus.DISABLED;
        if (current == MerchantStatus.DISABLED && command == MerchantCommand.ENABLE) return MerchantStatus.ACTIVE;
        if ((current == MerchantStatus.ACTIVE || current == MerchantStatus.DISABLED
            || current == MerchantStatus.REVIEW_REJECTED) && command == MerchantCommand.TERMINATE) {
            return MerchantStatus.TERMINATED;
        }
        throw new MerchantException.StateConflict();
    }

    public static String reason(MerchantCommand command, String supplied) {
        if (command == MerchantCommand.SUBMIT || command == MerchantCommand.RESUBMIT) {
            if (supplied != null) throw new MerchantException.InvalidRequest("Submission reason is server-derived");
            return command == MerchantCommand.SUBMIT ? "APPLICATION_SUBMITTED" : "APPLICATION_RESUBMITTED";
        }
        if (supplied == null || !REASONS.getOrDefault(command, Set.of()).contains(supplied)) {
            throw new MerchantException.InvalidRequest("reasonCode is not allowed for this command");
        }
        return supplied;
    }
}
