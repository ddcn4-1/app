package org.ddcn41.ticketing_system.domain.performance.service;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.ddcn41.ticketing_system.domain.performance.dto.request.PerformanceRequestDto;
import org.ddcn41.ticketing_system.domain.performance.dto.response.PerformanceResponse;
import org.ddcn41.ticketing_system.domain.performance.entity.Performance;
import org.ddcn41.ticketing_system.domain.performance.entity.PerformanceSchedule;
import org.ddcn41.ticketing_system.domain.performance.repository.PerformanceRepository;
import org.ddcn41.ticketing_system.domain.performance.repository.PerformanceScheduleRepository;
import org.ddcn41.ticketing_system.domain.venue.entity.Venue;
import org.ddcn41.ticketing_system.domain.venue.repository.VenueRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
@Transactional
public class PerformanceService {

    private final PerformanceRepository performanceRepository;
    private final PerformanceScheduleRepository performanceScheduleRepository;

    private final VenueRepository venueRepository;

    public Performance getPerformanceById(Long performanceId){
        return performanceRepository.findById(performanceId)
                .orElseThrow(()-> new EntityNotFoundException("Performance not found with id: "+performanceId));
    }

    public List<Performance> getAllPerformances() {
        return performanceRepository.findAllWithVenueAndSchedules();
    }

    public List<Performance> searchPerformances(String name, String venue, String status) {
        Performance.PerformanceStatus performanceStatus = null;

        // status 문자열을 enum으로 변환
        if (status != null && !status.trim().isEmpty() && !status.equalsIgnoreCase("all")) {
            try {
                performanceStatus = Performance.PerformanceStatus.valueOf(status.toUpperCase());
            } catch (IllegalArgumentException e) {
                // 잘못된 status 값인 경우 null로 처리 (모든 상태 조회)
                performanceStatus = null;
            }
        }

        return performanceRepository.searchPerformances(
                name != null && !name.trim().isEmpty() ? name : null,
                venue != null && !venue.trim().isEmpty() ? venue : null,
                performanceStatus
        );
    }

    public List<PerformanceSchedule> getPerformanceSchedules(Long performanceId) {
        // TODO: 404 exception handling - 나중에 수정 예정
        return performanceScheduleRepository.findByPerformance_PerformanceIdOrderByShowDatetimeAsc(performanceId);
    }

    public PerformanceResponse createPerformance(PerformanceRequestDto createPerformanceRequestDto) {
        Venue venue = venueRepository.findById(createPerformanceRequestDto.getVenueId())
                .orElseThrow(() -> new EntityNotFoundException("venue not found with id: "+ createPerformanceRequestDto.getVenueId()));

        // TODO: 공연 스케줄 처리 구현

        Performance performance = Performance.builder()
                .venue(venue)
                .title(createPerformanceRequestDto.getTitle())
                .description(createPerformanceRequestDto.getDescription())
                .theme(createPerformanceRequestDto.getTheme())
                .posterUrl(createPerformanceRequestDto.getPosterUrl())
                .startDate(createPerformanceRequestDto.getStartDate())
                .endDate(createPerformanceRequestDto.getEndDate())
                .runningTime(createPerformanceRequestDto.getRunningTime())
                .basePrice(createPerformanceRequestDto.getBasePrice())
                .status(createPerformanceRequestDto.getStatus())
                .schedules(createPerformanceRequestDto.getSchedules())
                .build();

        Performance savedPerformance = performanceRepository.save(performance);
        return PerformanceResponse.from(savedPerformance);
    }

    public void deletePerformance(Long performanceId) {
        if (!performanceRepository.existsById(performanceId)) {
            throw new EntityNotFoundException("Performance not found with id: "+performanceId);
        }
        performanceRepository.deleteById(performanceId);
    }

    public PerformanceResponse updatePerformance(Long performanceId, PerformanceRequestDto updatePerformanceRequestDto) {
        Performance performance = getPerformanceById(performanceId);

        Venue venue = venueRepository.findById(updatePerformanceRequestDto.getVenueId())
                .orElseThrow(() -> new EntityNotFoundException("venue not found with id: "+ updatePerformanceRequestDto.getVenueId()));

        performance.setVenue(venue);
        performance.setTitle(updatePerformanceRequestDto.getTitle());
        performance.setDescription(updatePerformanceRequestDto.getDescription());
        performance.setTheme(updatePerformanceRequestDto.getTheme());
        performance.setPosterUrl(updatePerformanceRequestDto.getPosterUrl());
        performance.setStartDate(updatePerformanceRequestDto.getStartDate());
        performance.setEndDate(updatePerformanceRequestDto.getEndDate());
        performance.setRunningTime(updatePerformanceRequestDto.getRunningTime());
        performance.setBasePrice(updatePerformanceRequestDto.getBasePrice());
        performance.setStatus(updatePerformanceRequestDto.getStatus());
        performance.setSchedules(updatePerformanceRequestDto.getSchedules());

        Performance updatedPerformance = performanceRepository.save(performance);
        return PerformanceResponse.from(updatedPerformance);
    }
}
