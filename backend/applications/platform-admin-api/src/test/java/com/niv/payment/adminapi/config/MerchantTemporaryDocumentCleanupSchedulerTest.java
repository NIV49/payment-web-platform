package com.niv.payment.adminapi.config;

import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MerchantTemporaryDocumentCleanupSchedulerTest {
    @Test
    void invokesOnlyTheConfiguredBoundedBatchAndHasDelayedScheduling() throws Exception {
        AtomicInteger observed = new AtomicInteger();
        var scheduler = new MerchantTemporaryDocumentCleanupScheduler(batch -> {
            observed.set(batch);
            return batch;
        }, 37);

        scheduler.cleanup();

        assertThat(observed).hasValue(37);
        Scheduled scheduled = MerchantTemporaryDocumentCleanupScheduler.class
            .getDeclaredMethod("cleanup").getAnnotation(Scheduled.class);
        assertThat(scheduled.initialDelayString())
            .isEqualTo("${payment.merchant.document-cleanup.initial-delay:PT1M}");
        assertThat(scheduled.fixedDelayString())
            .isEqualTo("${payment.merchant.document-cleanup.poll-delay:PT5M}");
    }

    @Test
    void rejectsUnboundedConfigurationAndContainsMaintenanceFailures() {
        assertThatThrownBy(() -> new MerchantTemporaryDocumentCleanupScheduler(batch -> 0, 0))
            .isInstanceOf(IllegalArgumentException.class);
        var scheduler = new MerchantTemporaryDocumentCleanupScheduler(batch -> {
            throw new IllegalStateException("synthetic cleanup failure");
        }, 100);

        scheduler.cleanup();
    }
}
