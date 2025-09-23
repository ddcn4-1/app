package org.ddcn41.ticketing_system.domain.performance.service;

import jakarta.persistence.EntityNotFoundException;
import lombok.RequiredArgsConstructor;
import org.ddcn41.ticketing_system.domain.booking.entity.Booking;
import org.ddcn41.ticketing_system.domain.booking.repository.BookingRepository;
import org.ddcn41.ticketing_system.domain.performance.dto.request.PerformanceRequestDto;
import org.ddcn41.ticketing_system.domain.performance.dto.response.AdminPerformanceResponse;
import org.ddcn41.ticketing_system.domain.performance.dto.response.PerformanceResponse;
import org.ddcn41.ticketing_system.domain.performance.entity.Performance;
import org.ddcn41.ticketing_system.domain.performance.entity.PerformanceSchedule;
import org.ddcn41.ticketing_system.domain.performance.repository.PerformanceRepository;
import org.ddcn41.ticketing_system.domain.performance.repository.PerformanceScheduleRepository;
import org.ddcn41.ticketing_system.domain.seat.dto.response.InitializeSeatsResponse;
import org.ddcn41.ticketing_system.domain.seat.service.ScheduleSeatInitializationService;
import org.ddcn41.ticketing_system.domain.venue.entity.Venue;
import org.ddcn41.ticketing_system.domain.venue.repository.VenueRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional
public class PerformanceService {

    private final PerformanceRepository performanceRepository;
    private final PerformanceScheduleRepository performanceScheduleRepository;

    private final BookingRepository bookingRepository;
    private final VenueRepository venueRepository;
    private final S3Service s3ImageService;

    private final ScheduleSeatInitializationService initializationService;

    public PerformanceResponse getPerformanceById(Long performanceId){
        return convertToPerformanceResponse(performanceRepository.findById(performanceId)
                .orElseThrow(()-> new EntityNotFoundException("Performance not found with id: "+performanceId)));
    }

    public List<PerformanceResponse> getAllPerformances() {
        return performanceRepository.findAllWithVenueAndSchedules()
                .stream()
                .map(this::convertToPerformanceResponse)
                .collect(Collectors.toList());
    }

    public List<AdminPerformanceResponse> getAllAdminPerformances() {
        return performanceRepository.findAllWithVenueAndSchedules()
                .stream()
                .map(this::convertToAdminPerformanceResponse)
                .collect(Collectors.toList());
    }

    public List<PerformanceResponse> searchPerformances(String name, String venue, String status) {
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
        ).stream().map(this::convertToPerformanceResponse).collect(Collectors.toList());
    }

    public List<PerformanceSchedule> getPerformanceSchedules(Long performanceId) {
        // TODO: 404 exception handling - 나중에 수정 예정
        return performanceScheduleRepository.findByPerformance_PerformanceIdOrderByShowDatetimeAsc(performanceId);
    }

    public AdminPerformanceResponse createPerformance(PerformanceRequestDto createPerformanceRequestDto) {
        Venue venue = venueRepository.findById(createPerformanceRequestDto.getVenueId())
                .orElseThrow(() -> new EntityNotFoundException("venue not found with id: "+ createPerformanceRequestDto.getVenueId()));

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
                .build();

        if (createPerformanceRequestDto.getSchedules() != null && !createPerformanceRequestDto.getSchedules().isEmpty()) {
            // 각 스케줄에 performance 설정
            for (PerformanceSchedule schedule : createPerformanceRequestDto.getSchedules()) {
                schedule.setPerformance(performance);

            }

            performance.setSchedules(createPerformanceRequestDto.getSchedules());
        }

        Performance savedPerformance = performanceRepository.save(performance);

        if (savedPerformance.getSchedules() != null && !savedPerformance.getSchedules().isEmpty()) {
            for (PerformanceSchedule schedule : savedPerformance.getSchedules()) {
                initializationService.initialize(schedule.getScheduleId(), false);
            }
        }

        return convertToAdminPerformanceResponse(savedPerformance);
    }

    public void deletePerformance(Long performanceId) {
        Performance performance = performanceRepository.findById(performanceId)
                .orElseThrow(()-> new EntityNotFoundException("Performance not found with id: "+performanceId));

        deleteExistingImages(performance);

        performanceRepository.delete(performance);
    }

    public AdminPerformanceResponse updatePerformance(Long performanceId, PerformanceRequestDto updatePerformanceRequestDto) {
        Performance performance = performanceRepository.findById(performanceId)
                .orElseThrow(()-> new EntityNotFoundException("Performance not found with id: "+performanceId));

        Venue venue = venueRepository.findById(updatePerformanceRequestDto.getVenueId())
                .orElseThrow(() -> new EntityNotFoundException("venue not found with id: "+ updatePerformanceRequestDto.getVenueId()));

        // 기존 이미지 삭제
        if (performance.getPosterUrl() != null && !updatePerformanceRequestDto.getPosterUrl().isEmpty() &&
                !Objects.equals(performance.getPosterUrl(), updatePerformanceRequestDto.getPosterUrl())) {
            deleteExistingImages(performance);

            performance.setPosterUrl(updatePerformanceRequestDto.getPosterUrl());
        }

        performance.setVenue(venue);
        performance.setTitle(updatePerformanceRequestDto.getTitle());
        performance.setDescription(updatePerformanceRequestDto.getDescription());
        performance.setTheme(updatePerformanceRequestDto.getTheme());
        performance.setStartDate(updatePerformanceRequestDto.getStartDate());
        performance.setEndDate(updatePerformanceRequestDto.getEndDate());
        performance.setRunningTime(updatePerformanceRequestDto.getRunningTime());
        performance.setBasePrice(updatePerformanceRequestDto.getBasePrice());
        performance.setStatus(updatePerformanceRequestDto.getStatus());


        if (updatePerformanceRequestDto.getSchedules() != null && !updatePerformanceRequestDto.getSchedules().isEmpty()) {
            for (PerformanceSchedule schedule : updatePerformanceRequestDto.getSchedules()) {
                schedule.setPerformance(performance);
            }

            performance.setSchedules(updatePerformanceRequestDto.getSchedules());
        }

        Performance updatedPerformance = performanceRepository.save(performance);

        if (updatedPerformance.getSchedules() != null && !updatedPerformance.getSchedules().isEmpty()) {
            for (PerformanceSchedule schedule : updatedPerformance.getSchedules()) {
                initializationService.initialize(schedule.getScheduleId(), false);
            }
        }

        return convertToAdminPerformanceResponse(updatedPerformance);
    }

    /**
     * 기존 이미지 삭제
     */
    private void deleteExistingImages(Performance performance) {
        // 기존 포스터 이미지 삭제
        if (performance.getPosterUrl() != null) {
            s3ImageService.deleteImage(performance.getPosterUrl());
        }
    }

    private List<Booking> getBookingsByPerformanceId(Long performanceId) {
        return bookingRepository.findAll().
                stream().
                filter(
                        book -> book.getSchedule().
                                getPerformance().
                                getPerformanceId().
                                equals(performanceId))
                .collect(Collectors.toList());
    }

    private PerformanceResponse convertToPerformanceResponse(Performance performance) {
        List<PerformanceResponse.ScheduleResponse> scheduleResponses = performance.getSchedules() != null
                ? performance.getSchedules().stream()
                .map(PerformanceResponse.ScheduleResponse::from)
                .collect(Collectors.toList())
                : new ArrayList<>();

        String posterImageUrl = s3ImageService.generateDownloadPresignedUrl(performance.getPosterUrl(), 3);

        return PerformanceResponse.builder()
                .performanceId(performance.getPerformanceId())
                .title(performance.getTitle())
                .venue(performance.getVenue().getVenueName())
                .theme(performance.getTheme())
                .posterUrl(posterImageUrl)
                .price(performance.getBasePrice())
                .status(performance.getStatus())
                .startDate(performance.getStartDate().toString())
                .endDate(performance.getEndDate().toString())
                .runningTime(performance.getRunningTime())
                .venueAddress(performance.getVenue().getAddress())
                .venueId(performance.getVenue().getVenueId())
                .schedules(scheduleResponses)
                .description(performance.getDescription())
                .build();
    }

    private AdminPerformanceResponse convertToAdminPerformanceResponse(Performance performance) {
        PerformanceResponse performanceResponse = convertToPerformanceResponse(performance);
        List<Booking> bookings =  getBookingsByPerformanceId(performanceResponse.getPerformanceId());

        int totalBookings = bookings.stream().mapToInt(Booking::getSeatCount).sum();
        BigDecimal revenue = bookings.stream().map(Booking::getTotalAmount).reduce(BigDecimal.ZERO, BigDecimal::add);

        return AdminPerformanceResponse.builder()
                .performanceResponse(performanceResponse)
                .totalBookings(totalBookings)
                .revenue(revenue)
                .build();
    }

}
