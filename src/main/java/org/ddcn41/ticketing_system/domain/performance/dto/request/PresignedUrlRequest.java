package org.ddcn41.ticketing_system.domain.performance.dto.request;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;

@Data
@Builder
@AllArgsConstructor
public class PresignedUrlRequest {
    private String imageName;
    private String imageType;
}
