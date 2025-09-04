package org.ddcn41.ticketing_system.service;

import org.ddcn41.ticketing_system.dto.DashboardDto;

public class AdminService {

    public DashboardDto getDashboardData() {
        return DashboardDto.builder().build();
    }

}
