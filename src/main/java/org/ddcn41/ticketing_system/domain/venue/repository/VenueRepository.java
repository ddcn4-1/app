package org.ddcn41.ticketing_system.domain.venue.repository;

import org.ddcn41.ticketing_system.domain.venue.entity.Venue;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface VenueRepository extends JpaRepository<Venue, Long> {
    // 추가적인 쿼리 메소드들이 필요하면 여기에 추가
}
