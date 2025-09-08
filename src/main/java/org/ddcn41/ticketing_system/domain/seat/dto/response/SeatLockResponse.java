package org.ddcn41.ticketing_system.domain.seat.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SeatLockResponse {
    private boolean success;
    private String message;
    private LocalDateTime expiresAt;

    public static SeatLockResponse success(String message, LocalDateTime expiresAt) {
        return SeatLockResponse.builder()
                .success(true)
                .message(message)
                .expiresAt(expiresAt)
                .build();
    }

    public static SeatLockResponse failure(String message) {
        return SeatLockResponse.builder()
                .success(false)
                .message(message)
                .build();
    }
}