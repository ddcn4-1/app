package org.ddcn41.ticketing_system.domain.queue.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.ddcn41.ticketing_system.domain.queue.entity.QueueToken;

import java.time.LocalDateTime;

/**
 * 대기열 상태 응답 DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QueueStatusResponse {
    private String token;
    private QueueToken.TokenStatus status;
    private Integer positionInQueue;
    private Integer estimatedWaitTime;
    private boolean isActiveForBooking;
    private LocalDateTime bookingExpiresAt;
    private String performanceTitle; // 공연 제목 (사용자 토큰 목록에서 사용)
}