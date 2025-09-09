package org.ddcn41.ticketing_system.domain.auth.service;

import lombok.RequiredArgsConstructor;
import org.ddcn41.ticketing_system.global.util.AuditEventBuilder;
import org.springframework.boot.actuate.audit.AuditEvent;
import org.springframework.boot.actuate.audit.AuditEventRepository;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class AuthAuditService {
    private final AuditEventRepository auditEventRepository;

    // 로그인 성공 로그
    public void logLoginSuccess(String username) {
        AuditEvent auditEvent = AuditEventBuilder.builder()
                .principal(username)
                .type("LOGIN_SUCCESS")
                .details("Successful login")
                .build();

        auditEventRepository.add(auditEvent);
    }

    // 로그인 실패 로그
    public void logLoginFailure(String username, String errorMessage) {
        AuditEvent auditEvent = AuditEventBuilder.builder()
                .principal(username)
                .type("LOGIN_FAILURE")
                .details("Login failed: " + errorMessage)
                .build();

        auditEventRepository.add(auditEvent);
    }

    // 로그아웃 로그
    public void logLogout(String username) {
        AuditEvent auditEvent = AuditEventBuilder.builder()
                .principal(username)
                .type("LOGOUT")
                .details("User logged out")
                .build();

        auditEventRepository.add(auditEvent);
    }

    // 로그인, 로그아웃 관련 이벤트 전체 조회
    public List<AuditEvent> getAllAuthEvents() {
        return auditEventRepository.find(null, null, null)
                .stream()
                .filter(event -> {
                    String type = event.getType();
                    return "LOGIN_SUCCESS".equals(type) ||
                            "LOGIN_FAILURE".equals(type) ||
                            "LOGOUT".equals(type);
                })
                .collect(Collectors.toList());
    }

    // 최근 활동 조회
    public List<AuditEvent> getRecentAuthEvents(int limit) {
        return getAllAuthEvents().stream()
                .sorted((a, b) -> b.getTimestamp().compareTo(a.getTimestamp()))
                .limit(limit)
                .collect(Collectors.toList());
    }
}
