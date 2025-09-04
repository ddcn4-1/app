package org.ddcn41.ticketing_system.repository;

import org.ddcn41.ticketing_system.entity.Performance;
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
}
