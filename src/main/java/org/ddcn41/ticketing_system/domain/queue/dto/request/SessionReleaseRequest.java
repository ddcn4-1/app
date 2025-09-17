package org.ddcn41.ticketing_system.domain.queue.dto.request;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 세션 해제 요청 DTO
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SessionReleaseRequest {
    @NotNull(message = "공연 ID는 필수입니다")
    private Long performanceId;

    @NotNull(message = "스케줄 ID는 필수입니다")
    private Long scheduleId;

    private Long userId; // 사용자 ID (인증된 사용자 정보로 대체 가능)

    private String reason; // 해제 사유 (선택사항)
}