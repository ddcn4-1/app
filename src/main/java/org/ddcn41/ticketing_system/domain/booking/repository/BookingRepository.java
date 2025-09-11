package org.ddcn41.ticketing_system.domain.booking.repository;

import org.ddcn41.ticketing_system.domain.booking.dto.BookingProjection;
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
     * 예약 확정용 - 필요한 연관 엔티티들을 모두 JOIN FETCH
     */
    @Query("SELECT b FROM Booking b " +
           "JOIN FETCH b.user " +
           "JOIN FETCH b.schedule " +
           "JOIN FETCH b.bookingSeats bs " +
           "JOIN FETCH bs.seat " +
           "WHERE b.bookingId = :bookingId")
    Booking findByIdWithSeats(@Param("bookingId") Long bookingId);

    /**
     * 특정 기간의 예약들 조회
     */
    @Query("SELECT b FROM Booking b WHERE b.bookedAt BETWEEN :startDate AND :endDate")
    List<Booking> findBookingsBetweenDates(@Param("startDate") LocalDateTime startDate,
                                           @Param("endDate") LocalDateTime endDate);

    /**
     * 효율적인 예약 목록 조회 (DTO Projection 사용)
     * 필요한 데이터만 JOIN으로 한 번에 조회
     */
    @Query("SELECT " +
           "b.bookingId as bookingId, " +
           "b.bookingNumber as bookingNumber, " +
           "b.user.userId as userId, " +
           "b.user.name as userName, " +
           "b.user.phone as userPhone, " +
           "b.schedule.scheduleId as scheduleId, " +
           "b.schedule.showDatetime as showDatetime, " +
           "b.schedule.performance.title as performanceTitle, " +
           "b.schedule.performance.venue.venueName as venueName, " +
           "CONCAT(vs.seatRow, vs.seatNumber) as seatCode, " +
           "vs.seatZone as seatZone, " +
           "b.seatCount as seatCount, " +
           "b.totalAmount as totalAmount, " +
           "b.status as status, " +
           "b.expiresAt as expiresAt, " +
           "b.bookedAt as bookedAt, " +
           "b.cancelledAt as cancelledAt, " +
           "b.cancellationReason as cancellationReason, " +
           "b.createdAt as createdAt, " +
           "b.updatedAt as updatedAt " +
           "FROM Booking b " +
           "JOIN b.user " +
           "JOIN b.schedule " +
           "JOIN b.schedule.performance " +
           "JOIN b.schedule.performance.venue " +
           "LEFT JOIN b.bookingSeats bs " +
           "LEFT JOIN bs.seat ss " +
           "LEFT JOIN ss.venueSeat vs")
    Page<BookingProjection> findAllWithDetails(Pageable pageable);

    /**
     * 상태별 효율적인 예약 목록 조회
     */
    @Query("SELECT " +
           "b.bookingId as bookingId, " +
           "b.bookingNumber as bookingNumber, " +
           "b.user.userId as userId, " +
           "b.user.name as userName, " +
           "b.user.phone as userPhone, " +
           "b.schedule.scheduleId as scheduleId, " +
           "b.schedule.showDatetime as showDatetime, " +
           "b.schedule.performance.title as performanceTitle, " +
           "b.schedule.performance.venue.venueName as venueName, " +
           "CONCAT(vs.seatRow, vs.seatNumber) as seatCode, " +
           "vs.seatZone as seatZone, " +
           "b.seatCount as seatCount, " +
           "b.totalAmount as totalAmount, " +
           "b.status as status, " +
           "b.expiresAt as expiresAt, " +
           "b.bookedAt as bookedAt, " +
           "b.cancelledAt as cancelledAt, " +
           "b.cancellationReason as cancellationReason, " +
           "b.createdAt as createdAt, " +
           "b.updatedAt as updatedAt " +
           "FROM Booking b " +
           "JOIN b.user " +
           "JOIN b.schedule " +
           "JOIN b.schedule.performance " +
           "JOIN b.schedule.performance.venue " +
           "LEFT JOIN b.bookingSeats bs " +
           "LEFT JOIN bs.seat ss " +
           "LEFT JOIN ss.venueSeat vs " +
           "WHERE b.status = :status")
    Page<BookingProjection> findAllByStatusWithDetails(@Param("status") BookingStatus status, Pageable pageable);
}