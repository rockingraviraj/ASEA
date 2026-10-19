package com.asea.common;

import java.time.LocalDateTime;

public record ApiError(
        int status,
        String code,
        String message,
        LocalDateTime timestamp,
        String path) {
}
