package org.ddcn41.ticketing_system.service;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.ddcn41.ticketing_system.entity.Performance;
import org.ddcn41.ticketing_system.repository.PerformanceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PerformanceService {

    private final PerformanceRepository performanceRepository;

    public Performance getPerformanceById(Long performanceId){
        return performanceRepository.findById(performanceId)
                .orElseThrow(()-> new EntityNotFoundException("Performance not found with id: "+performanceId));
    }


}
