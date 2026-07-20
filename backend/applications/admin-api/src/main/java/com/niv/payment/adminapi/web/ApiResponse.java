package com.niv.payment.adminapi.web;

public record ApiResponse<T>(int code, T data, String error, String message, String traceId) {
    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(0, data, null, "success", RequestTrace.current());
    }

    public static ApiResponse<Void> failure(int code, String error, String message) {
        return new ApiResponse<>(code, null, error, message, RequestTrace.current());
    }
}
