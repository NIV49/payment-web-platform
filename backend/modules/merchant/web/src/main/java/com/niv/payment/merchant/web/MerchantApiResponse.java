package com.niv.payment.merchant.web;

import com.fasterxml.jackson.annotation.JsonInclude;

record MerchantApiResponse<T>(int code,
                              @JsonInclude(JsonInclude.Include.ALWAYS) T data,
                              String error, String message, String traceId) {
    static <T> MerchantApiResponse<T> success(T data, MerchantRequestTrace trace) {
        return new MerchantApiResponse<>(0, data, null, "success", trace.current());
    }

    static MerchantApiResponse<Void> failure(int code, String error, String message,
                                             MerchantRequestTrace trace) {
        return new MerchantApiResponse<>(code, null, error, message, trace.current());
    }
}
