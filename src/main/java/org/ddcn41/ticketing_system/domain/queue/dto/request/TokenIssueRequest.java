package org.ddcn41.ticketing_system.domain.queue.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TokenIssueRequest {
    @NotNull(message = "공연 ID는 필수입니다")
    private Long performanceId;
}