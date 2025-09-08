package org.ddcn41.ticketing_system.domain.booking.repository;

import org.ddcn41.ticketing_system.domain.booking.entity.Booking;
import org.ddcn41.ticketing_system.domain.booking.entity.Booking.BookingStatus;
import org.ddcn41.ticketing_system.domain.user.entity.User;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface BookingRepository extends JpaRepository<Booking, Long> {

    Page<Booking> findAllByStatus(BookingStatus status, Pageable pageable);

    /**
     * 특정 사용자의 모든 예약 조회
     */
    Page<Booking> findByUser(User user, Pageable pageable);

    /**
     * 특정 사용자의 특정 상태 예약 조회
     */
    Page<Booking> findByUserAndStatus(User user, BookingStatus status, Pageable pageable);

    /**
     * 특정 사용자의 예약 개수 조회
     */
    long countByUser(User user);

    /**
     * 특정 사용자의 특정 상태 예약 개수 조회
     */
    long countByUserAndStatus(User user, BookingStatus status);

    /**
     * 만료된 PENDING 예약들 조회 (스케줄러용)
     */
    @Query("SELECT b FROM Booking b WHERE b.status = 'PENDING' AND b.expiresAt < :now")
    List<Booking> findExpiredPendingBookings(@Param("now") LocalDateTime now);

    /**
     * 특정 스케줄의 예약들 조회
     */
    @Query("SELECT b FROM Booking b WHERE b.schedule.scheduleId = :scheduleId")
    Page<Booking> findByScheduleId(@Param("scheduleId") Long scheduleId, Pageable pageable);

    /**
     * 예약 번호로 조회
     */
    @Query("SELECT b FROM Booking b WHERE b.bookingNumber = :bookingNumber")
    Booking findByBookingNumber(@Param("bookingNumber") String bookingNumber);

    /**
     * 특정 기간의 예약들 조회
     */
    @Query("SELECT b FROM Booking b WHERE b.bookedAt BETWEEN :startDate AND :endDate")
    List<Booking> findBookingsBetweenDates(@Param("startDate") LocalDateTime startDate,
                                           @Param("endDate") LocalDateTime endDate);
}