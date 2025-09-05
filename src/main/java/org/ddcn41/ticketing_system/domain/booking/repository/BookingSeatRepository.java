package org.ddcn41.ticketing_system.domain.booking.repository;

import org.ddcn41.ticketing_system.domain.booking.entity.BookingSeat;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BookingSeatRepository extends JpaRepository<BookingSeat, Long> {
}
