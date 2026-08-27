package com.niv.payment.merchant.core;

import java.util.List;
import java.util.Set;

public record MerchantPage(List<MerchantDetail> items, long total,
                           Set<Long> reviewPendingMerchantIds) {
    public MerchantPage(List<MerchantDetail> items, long total) {
        this(items, total, Set.of());
    }

    public MerchantPage {
        items = List.copyOf(items);
        reviewPendingMerchantIds = Set.copyOf(reviewPendingMerchantIds);
        if (total < 0) throw new IllegalArgumentException("total cannot be negative");
    }
}
