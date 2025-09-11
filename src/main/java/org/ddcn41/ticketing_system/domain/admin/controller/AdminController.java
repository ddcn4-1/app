package org.ddcn41.ticketing_system.domain.admin.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.ddcn41.ticketing_system.domain.admin.service.AdminService;
import org.ddcn41.ticketing_system.dto.DashboardDto;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1/admin/dashboard/system-status")
@RequiredArgsConstructor
@Tag(name = "Admin", description = "APIs for admin")
public class AdminController {
    private final AdminService adminService;

    @Operation(summary = "Get Admin Dashboard data", description = "Get Admin Dashboard data")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "OK",
                    content = @Content(schema = @Schema(implementation = DashboardDto.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content)
    })
    @GetMapping
    public ResponseEntity<DashboardDto> getDashboard() {
        DashboardDto dashboard = adminService.getDashboardData();
        return ResponseEntity.ok(dashboard);
    }
}
