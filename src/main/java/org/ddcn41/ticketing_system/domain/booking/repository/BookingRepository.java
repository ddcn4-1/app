package org.ddcn41.ticketing_system.domain.booking.repository;

import org.ddcn41.ticketing_system.domain.booking.entity.Booking;
import org.ddcn41.ticketing_system.domain.booking.entity.Booking.BookingStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BookingRepository extends JpaRepository<Booking, Long> {
    Page<Booking> findAllByStatus(BookingStatus status, Pageable pageable);
}
