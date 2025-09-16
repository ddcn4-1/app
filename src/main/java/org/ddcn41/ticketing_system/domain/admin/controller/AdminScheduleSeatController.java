package org.ddcn41.ticketing_system.domain.admin.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.ddcn41.ticketing_system.domain.seat.dto.response.InitializeSeatsResponse;
import org.ddcn41.ticketing_system.domain.seat.service.ScheduleSeatInitializationService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/admin/schedules")
@Tag(name = "Admin Schedules", description = "Admin APIs for schedule seat generation")
public class AdminScheduleSeatController {

    private final ScheduleSeatInitializationService initializationService;

    @PostMapping("/initialize")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(
            summary = "Initialize seats for ALL schedules",
            description = "Runs the seat expansion for every schedule in performance_schedules."
    )
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OK",
                    content = @Content(schema = @Schema(implementation = org.ddcn41.ticketing_system.domain.seat.dto.response.InitializeSeatsResponse.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
            @ApiResponse(responseCode = "403", description = "Forbidden", content = @Content)
    })
    public ResponseEntity<org.ddcn41.ticketing_system.dto.response.ApiResponse<List<InitializeSeatsResponse>>> initializeAllSchedules(
            @Parameter(description = "Dry run without persisting", required = false)
            @RequestParam(name = "dryRun", required = false, defaultValue = "false") boolean dryRun
    ) {
        List<InitializeSeatsResponse> results = initializationService.initializeAll(dryRun);
        return ResponseEntity.ok(
                org.ddcn41.ticketing_system.dto.response.ApiResponse
                        .success(dryRun ? "모든 스케줄 좌석 초기화 미리보기" : "모든 스케줄 좌석 초기화 완료", results)
        );
    }

    @PostMapping("/{scheduleId}/initialize")
    @PreAuthorize("hasRole('ADMIN')")
    @Deprecated
    @Operation(summary = "[Deprecated] Initialize one schedule's seats", description = "Use POST /v1/admin/schedules/initialize to initialize all schedules.")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses({
            @ApiResponse(responseCode = "200", description = "OK",
                    content = @Content(schema = @Schema(implementation = InitializeSeatsResponse.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content),
            @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
            @ApiResponse(responseCode = "403", description = "Forbidden", content = @Content),
            @ApiResponse(responseCode = "404", description = "Not found", content = @Content)
    })
    public ResponseEntity<org.ddcn41.ticketing_system.dto.response.ApiResponse<InitializeSeatsResponse>> initializeScheduleSeats(
            @Parameter(description = "Schedule ID", required = true)
            @PathVariable Long scheduleId,
            @Parameter(description = "Dry run without persisting", required = false)
            @RequestParam(name = "dryRun", required = false, defaultValue = "false") boolean dryRun
    ) {
        InitializeSeatsResponse result = initializationService.initialize(scheduleId, dryRun);
        return ResponseEntity.ok(
                org.ddcn41.ticketing_system.dto.response.ApiResponse
                        .success(dryRun ? "좌석 초기화 미리보기" : "좌석 초기화 완료", result)
        );
    }
}
