package org.ddcn41.ticketing_system.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AuthLogDto {
    private String username;
    private String action;
    private Instant timestamp;
    private String details;
}
