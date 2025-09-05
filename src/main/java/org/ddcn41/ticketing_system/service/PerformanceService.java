package org.ddcn41.ticketing_system.service;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.ddcn41.ticketing_system.entity.Performance;
import org.ddcn41.ticketing_system.repository.PerformanceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PerformanceService {

    private final PerformanceRepository performanceRepository;

    public Performance getPerformanceById(Long performanceId){
        return performanceRepository.findById(performanceId)
                .orElseThrow(()-> new EntityNotFoundException("Performance not found with id: "+performanceId));
    }

    public List<Performance> getAllPerformances(){
        return performanceRepository.findAll();
    }

    public List<Performance> searchPerformances(String title, String venue, Performance.PerformanceStatus status){
        List<Performance> result = performanceRepository.findAll();

        // Java 스트림으로 필터링
        return result.stream()
                .filter(p -> title == null || title.trim().isEmpty() ||
                        p.getTitle().toLowerCase().contains(title.toLowerCase()))
                .filter(p -> venue == null || venue.trim().isEmpty() ||
                        p.getVenue().getVenueName().toLowerCase().contains(venue.toLowerCase()))
                .filter(p -> status == null || p.getStatus().equals(status))
                .collect(Collectors.toList());
    }

}
