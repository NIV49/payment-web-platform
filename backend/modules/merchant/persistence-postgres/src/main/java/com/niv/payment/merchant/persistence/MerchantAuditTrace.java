package com.niv.payment.merchant.persistence;

@FunctionalInterface
public interface MerchantAuditTrace {
    String current();
}
