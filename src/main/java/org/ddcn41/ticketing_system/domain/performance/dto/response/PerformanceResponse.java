package org.ddcn41.ticketing_system.domain.performance.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import org.ddcn41.ticketing_system.domain.performance.entity.Performance;
import org.ddcn41.ticketing_system.domain.performance.entity.PerformanceSchedule;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

@Builder
@Getter
@AllArgsConstructor
public class PerformanceResponse {
    private Long performanceId;
    private String title;
    private String venue;
    private String theme;
    private String posterUrl;
    private BigDecimal price;
    private Performance.PerformanceStatus status;
    private String description;
    private String startDate;
    private String endDate;
    private Integer runningTime;
    private String venueAddress;
    private List<ScheduleResponse> schedules;

    public static PerformanceResponse from(Performance performance) {
        List<ScheduleResponse> scheduleResponses = performance.getSchedules() != null
                ? performance.getSchedules().stream()
                .map(ScheduleResponse::from)
                .collect(Collectors.toList())
                : new ArrayList<>();

        return PerformanceResponse.builder()
                .performanceId(performance.getPerformanceId())
                .title(performance.getTitle())
                .venue(performance.getVenue().getVenueName())
                .theme(performance.getTheme())
                .posterUrl(performance.getPosterUrl())
                .price(performance.getBasePrice())
                .status(performance.getStatus())
                .startDate(performance.getStartDate().toString())
                .endDate(performance.getEndDate().toString())
                .runningTime(performance.getRunningTime())
                .venueAddress(performance.getVenue().getAddress())
                .schedules(scheduleResponses)
                .description(performance.getDescription())
                .build();
    }

    @Builder
    @Getter
    @AllArgsConstructor
    public static class ScheduleResponse {
        private Long scheduleId;
        private String showDatetime;
        private Integer availableSeats;
        private Integer totalSeats;
        private String status;

        public static ScheduleResponse from(PerformanceSchedule schedule) {

            String statusStr = switch (schedule.getStatus()) {
                case OPEN -> "SCHEDULED";  // Active/bookable shows
                case SOLDOUT -> "SCHEDULED";  // Still scheduled but no seats
                case CLOSED -> "COMPLETED";  // Show has ended
                default -> "SCHEDULED";
            };

            return ScheduleResponse.builder()
                    .scheduleId(schedule.getScheduleId())
                    .showDatetime(schedule.getShowDatetime().toString())
                    .availableSeats(schedule.getAvailableSeats())
                    .totalSeats(schedule.getTotalSeats())
                    .status(statusStr)
                    .build();
        }
    }
}
