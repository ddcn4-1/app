package org.ddcn41.ticketing_system.domain.queue.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.ddcn41.ticketing_system.domain.queue.entity.QueueToken;

import java.time.LocalDateTime;

/**
 * 토큰 발급 응답 DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TokenIssueResponse {
    private String token;
    private QueueToken.TokenStatus status;
    private Integer positionInQueue;
    private Integer estimatedWaitTime;
    private String message;
    private LocalDateTime expiresAt;
    private LocalDateTime bookingExpiresAt;
}
