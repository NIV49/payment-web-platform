package com.niv.payment.adminapi.config;

import com.niv.payment.merchant.core.MerchantDocumentMaintenance;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "payment.merchant.document-cleanup", name = "enabled",
    havingValue = "true", matchIfMissing = true)
final class MerchantTemporaryDocumentCleanupScheduler {
    private static final Logger LOG =
        LoggerFactory.getLogger(MerchantTemporaryDocumentCleanupScheduler.class);

    private final MerchantDocumentMaintenance maintenance;
    private final int batchSize;

    MerchantTemporaryDocumentCleanupScheduler(
        MerchantDocumentMaintenance maintenance,
        @Value("${payment.merchant.document-cleanup.batch-size:100}") int batchSize
    ) {
        if (batchSize < 1 || batchSize > 1_000) {
            throw new IllegalArgumentException("Merchant document cleanup batch size must be 1..1000");
        }
        this.maintenance = maintenance;
        this.batchSize = batchSize;
    }

    @Scheduled(
        initialDelayString = "${payment.merchant.document-cleanup.initial-delay:PT1M}",
        fixedDelayString = "${payment.merchant.document-cleanup.poll-delay:PT5M}")
    void cleanup() {
        try {
            int deleted = maintenance.deleteExpiredTemporaryDocuments(batchSize);
            if (deleted > 0) LOG.info("Deleted {} expired temporary Merchant documents", deleted);
        } catch (RuntimeException failure) {
            LOG.warn("Expired temporary Merchant document cleanup failed", failure);
        }
    }
}
