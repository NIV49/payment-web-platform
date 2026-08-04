package com.niv.payment.permission.backoffice;

record BackofficeApiResponse<T>(int code, T data, String error, String message, String traceId) {
    static <T> BackofficeApiResponse<T> success(T data) {
        return new BackofficeApiResponse<>(0, data, null, "success", BackofficeRequestTrace.current());
    }

    static BackofficeApiResponse<Void> failure(int code, String error, String message) {
        return new BackofficeApiResponse<>(code, null, error, message, BackofficeRequestTrace.current());
    }
}
