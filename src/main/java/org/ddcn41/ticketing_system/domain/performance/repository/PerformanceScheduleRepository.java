package org.ddcn41.ticketing_system.domain.performance.repository;

import org.ddcn41.ticketing_system.domain.performance.entity.PerformanceSchedule;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface PerformanceScheduleRepository extends JpaRepository<PerformanceSchedule, Long> {
    
    List<PerformanceSchedule> findByPerformance_PerformanceIdOrderByShowDatetimeAsc(Long performanceId);

    @Modifying(clearAutomatically = false, flushAutomatically = false)
    @Query("UPDATE PerformanceSchedule s SET s.availableSeats = s.availableSeats + :delta WHERE s.scheduleId = :scheduleId")
    int incrementAvailableSeats(@Param("scheduleId") Long scheduleId, @Param("delta") int delta);

    @Modifying(clearAutomatically = false, flushAutomatically = false)
    @Query("UPDATE PerformanceSchedule s SET s.availableSeats = s.availableSeats - :delta WHERE s.scheduleId = :scheduleId AND s.availableSeats >= :delta")
    int decrementAvailableSeats(@Param("scheduleId") Long scheduleId, @Param("delta") int delta);

    @Modifying(clearAutomatically = false, flushAutomatically = false)
    @Query("""
            UPDATE PerformanceSchedule s
            SET s.status = CASE
                    WHEN s.showDatetime <= CURRENT_TIMESTAMP THEN 'CLOSED'
                    WHEN s.availableSeats <= 0 THEN 'SOLDOUT'
                    ELSE 'OPEN'
                END
            WHERE s.scheduleId = :scheduleId
            """)
    int refreshScheduleStatus(@Param("scheduleId") Long scheduleId);

    @Modifying(clearAutomatically = false, flushAutomatically = false)
    @Query("""
            UPDATE PerformanceSchedule s
            SET s.status = 'CLOSED'
            WHERE s.showDatetime <= CURRENT_TIMESTAMP AND s.status <> 'CLOSED'
            """)
    int closePastSchedules();

    @Modifying(clearAutomatically = false, flushAutomatically = false)
    @Query("""
            UPDATE PerformanceSchedule s
            SET s.status = CASE
                    WHEN s.showDatetime <= CURRENT_TIMESTAMP THEN 'CLOSED'
                    WHEN s.availableSeats <= 0 THEN 'SOLDOUT'
                    ELSE 'OPEN'
                END
            """)
    int refreshAllScheduleStatuses();
}
