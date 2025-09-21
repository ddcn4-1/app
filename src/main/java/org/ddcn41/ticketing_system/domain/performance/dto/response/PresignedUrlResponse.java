package org.ddcn41.ticketing_system.domain.performance.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@AllArgsConstructor
public class PresignedUrlResponse {
    private String presignedUrl;
    private String imageKey;
}
