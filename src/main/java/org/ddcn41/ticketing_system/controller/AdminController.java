package org.ddcn41.ticketing_system.controller;

import lombok.RequiredArgsConstructor;
import org.ddcn41.ticketing_system.dto.DashboardDto;
import org.ddcn41.ticketing_system.service.AdminService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/admin/dashboard/system-status")
@RequiredArgsConstructor
public class AdminController {
    private final AdminService adminService;

    @GetMapping
    public ResponseEntity<DashboardDto> getDashboard() {
        DashboardDto dashboard = adminService.getDashboardData();
        return ResponseEntity.ok(dashboard);
    }
}
