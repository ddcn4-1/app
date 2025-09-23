package org.ddcn41.ticketing_system.domain.performance.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;

@Data
@Builder
@AllArgsConstructor
public class AdminPerformanceResponse {
    private PerformanceResponse performanceResponse;
    private BigDecimal revenue;
    private Integer totalBookings;
}
