package org.ddcn41.ticketing_system.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.ddcn41.ticketing_system.entity.Performance;

import java.math.BigDecimal;

@Builder
@Getter
@AllArgsConstructor
public class PerformanceResponse {
    private String title;
    private String venue;
    private String theme;
    private String posterUrl;
    private BigDecimal price;
    private Performance.PerformanceStatus status;
}
