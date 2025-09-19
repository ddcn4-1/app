package org.ddcn41.ticketing_system.domain.performance.config;

import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.ddcn41.ticketing_system.domain.performance.service.PerformanceScheduleStatusService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class PerformanceScheduleStatusScheduler {

    private static final Logger log = LoggerFactory.getLogger(PerformanceScheduleStatusScheduler.class);

    private final PerformanceScheduleStatusService scheduleStatusService;

    @PostConstruct
    public void initializeStatuses() {
        try {
            int affected = scheduleStatusService.synchronizeAllStatuses();
            log.info("초기 공연 스케줄 상태 동기화 완료: {}건 업데이트", affected);
        } catch (Exception e) {
            log.error("초기 공연 스케줄 상태 동기화 실패", e);
        }
    }

    @Scheduled(cron = "0 */5 * * * *", zone = "Asia/Seoul")
    public void closePastSchedules() {
        try {
            int closed = scheduleStatusService.closePastSchedules();
            if (closed > 0) {
                log.info("지난 공연 스케줄 {}건을 CLOSED 상태로 변경", closed);
            }
        } catch (Exception e) {
            log.error("공연 스케줄 상태 자동 마감 중 오류", e);
        }
    }
}
