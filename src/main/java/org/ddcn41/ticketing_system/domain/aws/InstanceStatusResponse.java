package org.ddcn41.ticketing_system.domain.aws;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class InstanceStatusResponse {
    private String asgName;
    private Integer currentCapacity;
    private Integer desiredCapacity;
    private Integer minSize;
    private Integer maxSize;
    private List<InstanceInfo> instances;
    private LocalDateTime lastUpdated;

    @Data
    @Builder
    public static class InstanceInfo {
        private String instanceId;
        private String privateIp;
        private String state;
        private String healthStatus;
    }
}