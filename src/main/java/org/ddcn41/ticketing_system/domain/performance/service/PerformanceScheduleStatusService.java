package org.ddcn41.ticketing_system.domain.performance.service;

import lombok.RequiredArgsConstructor;
import org.ddcn41.ticketing_system.domain.performance.repository.PerformanceScheduleRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class PerformanceScheduleStatusService {

    private final PerformanceScheduleRepository scheduleRepository;

    @Transactional
    public int synchronizeAllStatuses() {
        return scheduleRepository.refreshAllScheduleStatuses();
    }

    @Transactional
    public int closePastSchedules() {
        return scheduleRepository.closePastSchedules();
    }
}
