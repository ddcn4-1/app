package org.ddcn41.ticketing_system.domain.booking.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SeatSelectorDto {
    @NotBlank
    private String grade;
    @NotBlank
    private String zone;
    @NotBlank
    private String rowLabel;
    @NotBlank
    private String colNum;
}

