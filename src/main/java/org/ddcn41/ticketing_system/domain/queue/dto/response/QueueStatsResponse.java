package org.ddcn41.ticketing_system.domain.queue.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 대기열 통계 DTO (관리자용)
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QueueStatsResponse {
    private Long performanceId;
    private String performanceTitle;
    private Long waitingCount;
    private Long activeCount;
    private Long usedCount;
    private Long expiredCount;
    private Integer averageWaitTimeMinutes;
}