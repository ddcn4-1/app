package org.ddcn41.ticketing_system.repository;

import org.ddcn41.ticketing_system.entity.ScheduleSeat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ScheduleSeatRepository extends JpaRepository<ScheduleSeat, Long> {
    List<ScheduleSeat> findBySchedule_ScheduleIdAndSeatIdIn(Long scheduleId, List<Long> seatIds);
}

