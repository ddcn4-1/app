package org.ddcn41.ticketing_system.domain.booking.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import org.ddcn41.ticketing_system.domain.booking.dto.request.SeatSelectorDto;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateBookingRequestDto {
    @NotNull
    private Long scheduleId;

    @Valid
    @NotNull
    @Size(min = 1)
    private List<SeatSelectorDto> seats;

    private String queueToken;
}
