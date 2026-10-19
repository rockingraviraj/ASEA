package com.asea.common;

import java.time.LocalDateTime;

public record ApiResponse<T>(boolean success, T data, LocalDateTime timestamp) {

    public static <T> ApiResponse<T> ok(T data) {
        return new ApiResponse<>(true, data, LocalDateTime.now());
    }

    public static <T> ApiResponse<T> error(String message) {
        return new ApiResponse<>(false, null, LocalDateTime.now());
    }
}
