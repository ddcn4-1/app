package org.ddcn41.ticketing_system.domain.admin.controller;

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
public class AdminController {
    private final AdminService adminService;

    @GetMapping
    public ResponseEntity<DashboardDto> getDashboard() {
        DashboardDto dashboard = adminService.getDashboardData();
        return ResponseEntity.ok(dashboard);
    }
}
