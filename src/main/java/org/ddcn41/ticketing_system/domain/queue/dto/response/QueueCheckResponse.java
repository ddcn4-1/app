package org.ddcn41.ticketing_system.domain.queue.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QueueCheckResponse {

    /**
     * 대기열이 필요한지 여부
     */
    private boolean requiresQueue;

    /**
     * 바로 예매 진행 가능 여부
     */
    private boolean canProceedDirectly;

    /**
     * 세션 ID (바로 진입 시 발급)
     */
    private String sessionId;

    /**
     * 응답 메시지
     */
    private String message;

    /**
     * 현재 활성 세션 수
     */
    private Integer currentActiveSessions;

    /**
     * 최대 동시 세션 수
     */
    private Integer maxConcurrentSessions;

    /**
     * 예상 대기 시간 (초)
     */
    private Integer estimatedWaitTime;

    /**
     * 현재 대기열 대기자 수
     */
    private Integer currentWaitingCount;

    /**
     * 대기열 필요 사유
     */
    private String reason;
}