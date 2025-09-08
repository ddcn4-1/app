package org.ddcn41.ticketing_system.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SeatConfirmRequest {
    @NotEmpty(message = "좌석 ID 목록은 필수입니다")
    private List<Long> seatIds;

    @NotNull(message = "사용자 ID는 필수입니다")
    private Long userId;

    @NotNull(message = "예약 ID는 필수입니다")
    private Long bookingId;
}