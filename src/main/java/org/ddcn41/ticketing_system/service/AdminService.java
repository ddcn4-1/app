package org.ddcn41.ticketing_system.service;

import lombok.RequiredArgsConstructor;
import org.ddcn41.ticketing_system.dto.DashboardDto;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AdminService {

    public DashboardDto getDashboardData() {
        return DashboardDto.builder().build();
    }

}
