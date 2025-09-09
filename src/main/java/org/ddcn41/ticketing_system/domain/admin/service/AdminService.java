package org.ddcn41.ticketing_system.domain.admin.service;

import lombok.RequiredArgsConstructor;
import org.ddcn41.ticketing_system.dto.AuthLogDto;
import org.ddcn41.ticketing_system.dto.DashboardDto;
import org.ddcn41.ticketing_system.domain.auth.service.AuthAuditService;
import org.springframework.boot.actuate.audit.AuditEvent;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AdminService {

    private final AuthAuditService authAuditService;

    // 대시보드에 나타낼 값 가져오기
    public DashboardDto getDashboardData() {
        List<AuthLogDto> recentAuthLogs = authAuditService.getRecentAuthEvents(10)
                .stream()
                .map(this::convertToAuthLogDto)
                .collect(Collectors.toList());

        return DashboardDto.builder()
                .recentAuthLogs(recentAuthLogs)
                .build();
    }

    private AuthLogDto convertToAuthLogDto(AuditEvent auditEvent) {
        Map<String, Object> data = auditEvent.getData();

        return AuthLogDto.builder()
                .username(auditEvent.getPrincipal())
                .action(auditEvent.getType())
                .timestamp(auditEvent.getTimestamp())
                .details(data.get("details").toString())
                .build();
    }
}
