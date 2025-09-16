package org.ddcn41.ticketing_system.domain.queue.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ddcn41.ticketing_system.domain.queue.service.QueueService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class SessionCleanupScheduler {

    private final QueueService queueService;

    /**
     * 1분마다 비활성 세션 정리
     * heartbeat가 없는 사용자들의 세션을 해제하여 다음 대기자가 들어올 수 있게 함
     */
    @Scheduled(fixedRate = 60000) // 1분
    public void cleanupInactiveSessions() {
        try {
            log.debug("비활성 세션 정리 작업 시작");
            // QueueService에 실제로 있는 메서드 사용
            queueService.cleanupInactiveSessions(); // 실제 메서드명
            log.debug("비활성 세션 정리 작업 완료");
        } catch (Exception e) {
            log.error("비활성 세션 정리 중 오류 발생", e);
        }
    }

    /**
     * 30초마다 대기열 처리 (기존 기능 유지)
     */
    @Scheduled(fixedRate = 30000) // 30초
    public void processQueue() {
        try {
            log.debug("대기열 처리 작업 시작");
            queueService.processQueue(); // 기존에 있는 메서드
            log.debug("대기열 처리 작업 완료");
        } catch (Exception e) {
            log.error("대기열 처리 중 오류 발생", e);
        }
    }

    /**
     * 5분마다 오버부킹 상태 모니터링
     */
    @Scheduled(fixedRate = 300000) // 5분
    public void monitorOverbookingStatus() {
        try {
            log.debug("오버부킹 상태 모니터링 시작");

            // 공연별 대기열 통계 조회 (기존 메서드 활용)
            var queueStats = queueService.getQueueStatsByPerformance();

            for (var stat : queueStats) {
                log.info("공연 {} - 대기: {}, 활성: {}, 사용완료: {}",
                        stat.getPerformanceTitle(),
                        stat.getWaitingCount(),
                        stat.getActiveCount(),
                        stat.getUsedCount());
            }

            log.debug("오버부킹 상태 모니터링 완료");
        } catch (Exception e) {
            log.error("오버부킹 상태 모니터링 중 오류 발생", e);
        }
    }
}