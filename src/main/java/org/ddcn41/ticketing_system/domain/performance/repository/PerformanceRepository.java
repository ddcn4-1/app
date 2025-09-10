package org.ddcn41.ticketing_system.domain.performance.repository;

import org.ddcn41.ticketing_system.domain.performance.entity.Performance;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

@Repository
public interface PerformanceRepository extends JpaRepository<Performance, Long> {

    @EntityGraph(attributePaths = {"venue"})
    @Override
    Optional<Performance> findById(Long id);

    @EntityGraph(attributePaths = {"venue"})
    @Override
    List<Performance> findAll();

    @Query("SELECT DISTINCT p FROM Performance p " +
            "LEFT JOIN FETCH p.venue " +
            "LEFT JOIN FETCH p.schedules")
    List<Performance> findAllWithVenueAndSchedules();

    @Query("SELECT DISTINCT p FROM Performance p " +
            "LEFT JOIN FETCH p.venue " +
            "LEFT JOIN FETCH p.schedules " +
            "WHERE p.performanceId = :id")
    Optional<Performance> findByIdWithVenueAndSchedules(@Param("id") Long id);

    @Query("SELECT DISTINCT p FROM Performance p " +
            "LEFT JOIN FETCH p.venue v " +
            "LEFT JOIN FETCH p.schedules s " +
            "WHERE (:title IS NULL OR :title = '' OR LOWER(p.title) LIKE LOWER(CONCAT('%', :title, '%'))) " +
            "AND (:venue IS NULL OR :venue = '' OR LOWER(v.venueName) LIKE LOWER(CONCAT('%', :venue, '%'))) " +
            "AND (:status IS NULL OR :status = '' OR p.status = :status)")
    List<Performance> searchPerformances(@Param("title") String title,
                                         @Param("venue") String venue,
                                         @Param("status") Performance.PerformanceStatus status);

}
