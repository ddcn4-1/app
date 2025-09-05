package org.ddcn41.ticketing_system.repository;

import org.ddcn41.ticketing_system.entity.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface SeatLockRepository extends JpaRepository<SeatLock, Long> {

    // 기존 메서드들
    Optional<SeatLock> findBySeatSeatIdAndStatus(Long seatId, SeatLock.LockStatus status);
    Optional<SeatLock> findBySeatAndStatusAndExpiresAtAfter(ScheduleSeat seat, SeatLock.LockStatus status, LocalDateTime now);
    Optional<SeatLock> findBySeatSeatIdAndUserAndStatus(Long seatId, User user, SeatLock.LockStatus status);
    List<SeatLock> findByUser(User user);
    List<SeatLock> findByUserAndStatus(User user, SeatLock.LockStatus status);
    List<SeatLock> findBySessionId(String sessionId);
    List<SeatLock> findBySessionIdAndStatus(String sessionId, SeatLock.LockStatus status);
    List<SeatLock> findByStatusAndExpiresAtBefore(SeatLock.LockStatus status, LocalDateTime now);
    void deleteByUser(User user);
    void deleteBySessionId(String sessionId);

    @Query("SELECT sl FROM SeatLock sl WHERE sl.user = :user AND sl.status = 'ACTIVE' AND sl.expiresAt > :now")
    List<SeatLock> findActiveUserLocks(@Param("user") User user, @Param("now") LocalDateTime now);

    // 새로 추가된 메서드들

    /**
     * 여러 좌석에 대한 활성 락 조회
     */
    @Query("SELECT sl FROM SeatLock sl WHERE sl.seat.seatId IN :seatIds AND sl.status = 'ACTIVE'")
    List<SeatLock> findActiveLocksBySeatIds(@Param("seatIds") List<Long> seatIds);

    /**
     * 사용자의 특정 좌석들에 대한 활성 락 조회
     */
    @Query("SELECT sl FROM SeatLock sl WHERE sl.seat.seatId IN :seatIds AND sl.user = :user AND sl.status = 'ACTIVE'")
    List<SeatLock> findActiveUserLocksBySeatIds(@Param("seatIds") List<Long> seatIds, @Param("user") User user);

    /**
     * 세션의 특정 좌석들에 대한 활성 락 조회
     */
    @Query("SELECT sl FROM SeatLock sl WHERE sl.seat.seatId IN :seatIds AND sl.sessionId = :sessionId AND sl.status = 'ACTIVE'")
    List<SeatLock> findActiveSessionLocksBySeatIds(@Param("seatIds") List<Long> seatIds, @Param("sessionId") String sessionId);

    /**
     * 스케줄의 모든 활성 락 조회
     */
    @Query("SELECT sl FROM SeatLock sl WHERE sl.seat.schedule.scheduleId = :scheduleId AND sl.status = 'ACTIVE'")
    List<SeatLock> findActiveLocksByScheduleId(@Param("scheduleId") Long scheduleId);

    /**
     * 만료 예정인 락들 조회 (알림용)
     */
    @Query("SELECT sl FROM SeatLock sl WHERE sl.status = 'ACTIVE' AND sl.expiresAt BETWEEN :start AND :end")
    List<SeatLock> findLocksExpiringBetween(@Param("start") LocalDateTime start, @Param("end") LocalDateTime end);
}