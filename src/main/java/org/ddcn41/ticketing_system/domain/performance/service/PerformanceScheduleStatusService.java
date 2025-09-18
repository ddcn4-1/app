package org.ddcn41.ticketing_system.domain.performance.service;

import lombok.RequiredArgsConstructor;
import org.ddcn41.ticketing_system.domain.performance.repository.PerformanceScheduleRepository;
import org.ddcn41.ticketing_system.global.util.AuditEventBuilder;
import org.springframework.boot.actuate.audit.AuditEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PerformanceScheduleStatusService {

    private final PerformanceScheduleRepository scheduleRepository;
    private final AuditEventRepository auditEventRepository;

    private static final String SYSTEM_PRINCIPAL = "system";

    @Transactional
    public int synchronizeAllStatuses() {
        int affected = scheduleRepository.refreshAllScheduleStatuses();
        logStatusSyncEvent("SCHEDULE_STATUS_SYNC", affected);
        return affected;
    }

    @Transactional
    public int closePastSchedules() {
        int closed = scheduleRepository.closePastSchedules();
        logStatusSyncEvent("SCHEDULE_CLOSE_AUTOMATION", closed);
        return closed;
    }

    private void logStatusSyncEvent(String type, int affected) {
        auditEventRepository.add(
                AuditEventBuilder.builder()
                        .principal(SYSTEM_PRINCIPAL)
                        .type(type)
                        .data("affected", affected)
                        .build()
        );
    }
}
