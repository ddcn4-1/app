package org.ddcn41.ticketing_system.config;

import org.ddcn41.ticketing_system.service.SeatService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@EnableScheduling
public class SeatLockCleanupScheduler {

    private static final Logger logger = LoggerFactory.getLogger(SeatLockCleanupScheduler.class);

    @Autowired
    private SeatService seatService;

    /**
     * 1분마다 만료된 좌석 잠금 정리
     */
    @Scheduled(fixedRate = 60000) // 1분
    public void cleanupExpiredLocks() {
        try {
            logger.debug("만료된 좌석 잠금 정리 작업 시작");
            seatService.cleanupExpiredLocks();
            logger.debug("만료된 좌석 잠금 정리 작업 완료");
        } catch (Exception e) {
            logger.error("만료된 좌석 잠금 정리 중 오류 발생", e);
        }
    }

    /**
     * 5분마다 시스템 상태 점검
     */
    @Scheduled(fixedRate = 300000) // 5분
    public void systemHealthCheck() {
        try {
            logger.info("좌석 잠금 시스템 상태 점검 실행");
            // 필요시 Redis와 DB 동기화 로직 추가
        } catch (Exception e) {
            logger.error("시스템 상태 점검 중 오류 발생", e);
        }
    }
}

