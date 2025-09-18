package org.ddcn41.ticketing_system.domain.queue.config;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ddcn41.ticketing_system.domain.queue.service.QueueService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class SessionCleanupScheduler {

//todo. 스케줄 시간 수정 필요 위의 내용 기본내용.
        private final QueueService queueService;
    /**
     * 10초마다 빠른 대기자 활성화 체크 //todo. 필요시 활성화
     */
 /*   @Scheduled(fixedRate = 10000) // 10초
    public void quickActivateNext() {
        try {
            log.debug("=== 10초 주기: 대기자 활성화 체크 ===");
            queueService.quickCleanupAndActivate();
        } catch (Exception e) {
            log.error("빠른 활성화 체크 중 오류", e);
        }
    }*/

    /**
     * 1분마다 비활성 세션 정리
     */
    @Value("${queue.max-inactive-seconds:120}")
//    @Scheduled(fixedRate = 60000) // 1분
    public void cleanupInactiveSessions() {
        try {
            log.debug("=== 비활성 세션 정리 시작 ===");
            queueService.cleanupInactiveSessions();
            log.debug("=== 비활성 세션 정리 완료 ===");
        } catch (Exception e) {
            log.error("비활성 세션 정리 중 오류", e);
        }
    }

    /**
     * 30초마다 전체 대기열 처리 //todo. 필요시 활성화
     */
    /*@Scheduled(fixedRate = 30000) // 30초
    public void processQueue() {
        try {
            log.debug("=== 30초 주기: 전체 대기열 처리 ===");
            queueService.processQueue();
        } catch (Exception e) {
            log.error("대기열 처리 중 오류", e);
        }
    }*/
}
