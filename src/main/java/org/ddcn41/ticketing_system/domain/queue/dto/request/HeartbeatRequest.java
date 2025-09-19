package org.ddcn41.ticketing_system.domain.queue.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Heartbeat 요청 DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class HeartbeatRequest {
    @NotNull(message = "공연 ID는 필수입니다")
    private Long performanceId;

    @NotNull(message = "스케줄 ID는 필수입니다")
    private Long scheduleId;
}