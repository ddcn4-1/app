package org.ddcn41.ticketing_system.domain.performance.repository;

import org.ddcn41.ticketing_system.domain.performance.entity.PerformanceSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PerformanceScheduleRepository extends JpaRepository<PerformanceSchedule, Long> {
}

