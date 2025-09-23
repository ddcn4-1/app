package org.ddcn41.ticketing_system.domain.aws;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ScaleRequest {
    @NotNull(message = "Desired count is required")
    @Min(value = 1, message = "Minimum instance count is 1")
    @Max(value = 10, message = "Maximum instance count is 10")
    private Integer desiredCount;
}