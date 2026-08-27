package com.niv.payment.merchant.core;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class MerchantLifecyclePolicyTest {
    @Test
    void implementsTheExactFiveStateTransitionTable() {
        assertEquals(MerchantStatus.PENDING_REVIEW,
            MerchantLifecyclePolicy.next(null, MerchantCommand.SUBMIT));
        assertEquals(MerchantStatus.ACTIVE,
            MerchantLifecyclePolicy.next(MerchantStatus.PENDING_REVIEW, MerchantCommand.APPROVE));
        assertEquals(MerchantStatus.REVIEW_REJECTED,
            MerchantLifecyclePolicy.next(MerchantStatus.PENDING_REVIEW, MerchantCommand.REJECT));
        assertEquals(MerchantStatus.PENDING_REVIEW,
            MerchantLifecyclePolicy.next(MerchantStatus.REVIEW_REJECTED, MerchantCommand.RESUBMIT));
        assertEquals(MerchantStatus.DISABLED,
            MerchantLifecyclePolicy.next(MerchantStatus.ACTIVE, MerchantCommand.DISABLE));
        assertEquals(MerchantStatus.ACTIVE,
            MerchantLifecyclePolicy.next(MerchantStatus.DISABLED, MerchantCommand.ENABLE));
        assertEquals(MerchantStatus.TERMINATED,
            MerchantLifecyclePolicy.next(MerchantStatus.ACTIVE, MerchantCommand.TERMINATE));
        assertEquals(MerchantStatus.TERMINATED,
            MerchantLifecyclePolicy.next(MerchantStatus.DISABLED, MerchantCommand.TERMINATE));
        assertEquals(MerchantStatus.TERMINATED,
            MerchantLifecyclePolicy.next(MerchantStatus.REVIEW_REJECTED, MerchantCommand.TERMINATE));
    }

    @Test
    void rejectsEveryUnlistedTransitionAndTerminalMutation() {
        assertThrows(MerchantException.StateConflict.class,
            () -> MerchantLifecyclePolicy.next(MerchantStatus.ACTIVE, MerchantCommand.RESUBMIT));
        for (MerchantCommand command : MerchantCommand.values()) {
            assertThrows(MerchantException.StateConflict.class,
                () -> MerchantLifecyclePolicy.next(MerchantStatus.TERMINATED, command));
        }
    }

    @Test
    void acceptsOnlyActionSpecificReasonCodes() {
        assertEquals("APPLICATION_SUBMITTED",
            MerchantLifecyclePolicy.reason(MerchantCommand.SUBMIT, null));
        assertEquals("APPLICATION_RESUBMITTED",
            MerchantLifecyclePolicy.reason(MerchantCommand.RESUBMIT, null));
        assertEquals("PROFILE_VERIFIED",
            MerchantLifecyclePolicy.reason(MerchantCommand.APPROVE, "PROFILE_VERIFIED"));
        assertEquals("REGISTRATION_UNVERIFIED",
            MerchantLifecyclePolicy.reason(MerchantCommand.REJECT, "REGISTRATION_UNVERIFIED"));
        assertThrows(MerchantException.InvalidRequest.class,
            () -> MerchantLifecyclePolicy.reason(MerchantCommand.DISABLE, "BUSINESS_CLOSED"));
        assertThrows(MerchantException.InvalidRequest.class,
            () -> MerchantLifecyclePolicy.reason(MerchantCommand.SUBMIT, "APPLICATION_SUBMITTED"));
    }
}
