package org.ddcn41.ticketing_system.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Getter;

import java.time.LocalDateTime;

@Getter
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ApiResponse<T> {
    private final String message;
    private final T data;
    private final boolean success;
    private final String error;
    @Builder.Default
    private final LocalDateTime timestamp = LocalDateTime.now();

    // 성공 응답 생성 메서드들
    public static <T> ApiResponse<T> success(String message, T data) {
        return ApiResponse.<T>builder()
                .message(message)
                .data(data)
                .success(true)
                .build();
    }

    public static <T> ApiResponse<T> success(String message) {
        return ApiResponse.<T>builder()
                .message(message)
                .success(true)
                .build();
    }

    // 실패 응답 생성 메서드들
    public static <T> ApiResponse<T> error(String message, String error, T data) {
        return ApiResponse.<T>builder()
                .message(message)
                .error(error)
                .data(data)
                .success(false)
                .build();
    }

    public static <T> ApiResponse<T> error(String message, String error) {
        return ApiResponse.<T>builder()
                .message(message)
                .error(error)
                .success(false)
                .build();
    }

    public static <T> ApiResponse<T> error(String message) {
        return ApiResponse.<T>builder()
                .message(message)
                .success(false)
                .build();
    }
}