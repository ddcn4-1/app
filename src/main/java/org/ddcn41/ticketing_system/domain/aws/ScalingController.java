package org.ddcn41.ticketing_system.domain.aws;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/v1/aws")
@RequiredArgsConstructor
@Slf4j
public class ScalingController {

    private final ScalingService scalingService;

    @PostMapping("/scale-instances")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<?> scaleInstances(@Valid @RequestBody ScaleRequest request) {
        log.info("Scaling request received: {}", request.getDesiredCount());

        try {
            String result = scalingService.scaleInstances(request.getDesiredCount());
            return ResponseEntity.ok().body(Map.of(
                    "message", "Scaling initiated successfully",
                    "result", result,
                    "desiredCount", request.getDesiredCount()
            ));
        } catch (Exception e) {
            log.error("Failed to scale instances", e);
            return ResponseEntity.internalServerError().body(Map.of(
                    "error", "Failed to scale instances: " + e.getMessage()
            ));
        }
    }

    @GetMapping("/instances/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<InstanceStatusResponse> getInstanceStatus() {
        log.info("Instance status request received");

        try {
            InstanceStatusResponse status = scalingService.getInstanceStatus();
            return ResponseEntity.ok(status);
        } catch (Exception e) {
            log.error("Failed to get instance status", e);
            return ResponseEntity.internalServerError().build();
        }
    }
}
