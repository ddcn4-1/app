package org.ddcn41.ticketing_system.domain.seat.repository;

import org.ddcn41.ticketing_system.domain.seat.entity.ScheduleSeat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ScheduleSeatRepository extends JpaRepository<ScheduleSeat, Long> {

    // 기존 메서드들
    List<ScheduleSeat> findBySchedule_ScheduleIdAndSeatIdIn(Long scheduleId, List<Long> seatIds);

    @Query("SELECT s FROM ScheduleSeat s WHERE s.schedule.scheduleId = :scheduleId AND s.status = 'AVAILABLE'")
    List<ScheduleSeat> findAvailableSeatsByScheduleId(@Param("scheduleId") Long scheduleId);

    @Query("SELECT COUNT(s) FROM ScheduleSeat s WHERE s.schedule.scheduleId = :scheduleId AND s.status = 'AVAILABLE'")
    int countAvailableSeatsByScheduleId(@Param("scheduleId") Long scheduleId);

    // SeatService에서 필요한 핵심 메서드들

    /**
     * 스케줄 ID로 모든 좌석 조회 (SeatService.getSeatsAvailability에서 사용)
     */
    List<ScheduleSeat> findBySchedule_ScheduleId(Long scheduleId);

    /**
     * 스케줄과 상태로 좌석 조회
     */
    List<ScheduleSeat> findBySchedule_ScheduleIdAndStatus(Long scheduleId, ScheduleSeat.SeatStatus status);

    /**
     * 특정 상태의 좌석들 조회
     */
    List<ScheduleSeat> findByStatus(ScheduleSeat.SeatStatus status);

    /**
     * 여러 좌석 ID로 좌석 조회 (SeatService.areSeatsAvailable에서 사용)
     */
    List<ScheduleSeat> findBySeatIdIn(List<Long> seatIds);

    /**
     * 좌석 상태별 개수 조회
     */
    @Query("SELECT s.status, COUNT(s) FROM ScheduleSeat s WHERE s.schedule.scheduleId = :scheduleId GROUP BY s.status")
    List<Object[]> countSeatsByStatusAndScheduleId(@Param("scheduleId") Long scheduleId);

    /**
     * 특정 사용자가 락한 좌석들 조회
     */
    @Query("SELECT ss FROM ScheduleSeat ss JOIN SeatLock sl ON ss.seatId = sl.seat.seatId " +
            "WHERE sl.user.userId = :userId AND sl.status = 'ACTIVE'")
    List<ScheduleSeat> findLockedSeatsByUser(@Param("userId") Long userId);

    //  검증 메서드들

    /**
     * 스케줄 ID와 좌석 ID 목록으로 좌석 조회 (cross-schedule 검증용)
     * 이미 위에 정의되어 있음: findBySchedule_ScheduleIdAndSeatIdIn
     */

    /**
     * 특정 좌석들의 스케줄 ID 조회 (동일 스케줄 검증용)
     */
    @Query("SELECT DISTINCT s.schedule.scheduleId FROM ScheduleSeat s WHERE s.seatId IN :seatIds")
    List<Long> findDistinctScheduleIdsBySeatIds(@Param("seatIds") List<Long> seatIds);

    /**
     * 좌석 ID로 해당 좌석이 속한 스케줄 정보 조회
     */
    @Query("SELECT s.schedule.scheduleId FROM ScheduleSeat s WHERE s.seatId = :seatId")
    Long findScheduleIdBySeatId(@Param("seatId") Long seatId);

    /**
     * 스케줄과 상태로 좌석 개수 조회
     */
    @Query("SELECT COUNT(s) FROM ScheduleSeat s WHERE s.schedule.scheduleId = :scheduleId AND s.status = :status")
    Long countByScheduleIdAndStatus(@Param("scheduleId") Long scheduleId, @Param("status") ScheduleSeat.SeatStatus status);

    /**
     * 여러 스케줄의 가용 좌석 수 조회 (배치 처리용)
     */
    @Query("SELECT s.schedule.scheduleId, COUNT(s) FROM ScheduleSeat s " +
            "WHERE s.schedule.scheduleId IN :scheduleIds AND s.status = 'AVAILABLE' " +
            "GROUP BY s.schedule.scheduleId")
    List<Object[]> countAvailableSeatsByScheduleIds(@Param("scheduleIds") List<Long> scheduleIds);
}