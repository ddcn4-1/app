package org.ddcn41.ticketing_system.domain.performance.dto.request;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.ddcn41.ticketing_system.domain.performance.entity.Performance;
import org.ddcn41.ticketing_system.domain.performance.entity.PerformanceSchedule;
import org.ddcn41.ticketing_system.domain.venue.entity.Venue;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreatePerformanceRequestDto {
    private Long venueId;
    private String title;
    private String description;
    private String theme;
    private String posterUrl;
    private LocalDate startDate;
    private LocalDate endDate;
    private Integer runningTime;
    private BigDecimal basePrice;
    private Performance.PerformanceStatus status;
    private List<PerformanceSchedule> schedules;
}
