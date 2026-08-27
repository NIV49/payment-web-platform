package com.niv.payment.merchant.core;

/** Internal, bounded maintenance for unattached Merchant document uploads. */
public interface MerchantDocumentMaintenance {
    int deleteExpiredTemporaryDocuments(int batchSize);
}
