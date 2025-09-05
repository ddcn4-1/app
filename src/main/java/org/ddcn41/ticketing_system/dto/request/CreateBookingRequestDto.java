package org.ddcn41.ticketing_system.dto.request;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

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
    private List<Long> seatIds;

    private String queueToken;
}

