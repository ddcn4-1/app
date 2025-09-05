package org.ddcn41.ticketing_system.repository;

import org.ddcn41.ticketing_system.entity.PerformanceSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface PerformanceScheduleRepository extends JpaRepository<PerformanceSchedule, Long> {
}

