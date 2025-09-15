package org.ddcn41.ticketing_system.domain.seat.dto.response;

import lombok.Builder;
import lombok.Getter;

@Getter
@Builder
public class InitializeSeatsResponse {
    private final Long scheduleId;
    private final int created;
    private final int total;
    private final int available;
    private final boolean dryRun;
}

