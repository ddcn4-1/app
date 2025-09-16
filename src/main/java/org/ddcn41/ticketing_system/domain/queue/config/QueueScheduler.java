package org.ddcn41.ticketing_system.domain.queue.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ddcn41.ticketing_system.domain.queue.service.QueueService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class QueueScheduler {

    private final QueueService queueService;

    /**
     * 30초마다 대기열 처리
     * - 만료된 토큰 정리
     * - 대기 중인 토큰 활성화
     * - 대기열 순서 업데이트
     */
    @Scheduled(fixedRate = 30000) // 30초
    public void processQueue() {
        try {
            log.debug("대기열 처리 작업 시작");
            queueService.processQueue();
            log.debug("대기열 처리 작업 완료");
        } catch (Exception e) {
            log.error("대기열 처리 중 오류 발생", e);
        }
    }

    /**
     * 1시간마다 오래된 사용 완료 토큰 정리
     */
    @Scheduled(fixedRate = 3600000) // 1시간
    public void cleanupOldTokens() {
        try {
            log.info("오래된 토큰 정리 작업 시작");
            queueService.cleanupOldTokens();
            log.info("오래된 토큰 정리 작업 완료");
        } catch (Exception e) {
            log.error("오래된 토큰 정리 중 오류 발생", e);
        }
    }
}