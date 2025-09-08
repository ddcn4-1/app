package org.ddcn41.ticketing_system.domain.auth.service;

import lombok.RequiredArgsConstructor;
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
        Map<String, Object> data = createAuditData(username, "Successful login");
        AuditEvent auditEvent = new AuditEvent(username, "LOGIN_SUCCESS", data);
        auditEventRepository.add(auditEvent);
    }

    // 로그인 실패 로그
    public void logLoginFailure(String username, String errorMessage) {
        Map<String, Object> data = createAuditData(username, "Login failed: " + errorMessage);
        AuditEvent auditEvent = new AuditEvent(username, "LOGIN_FAILURE", data);
        auditEventRepository.add(auditEvent);
    }

    // 로그아웃 로그
    public void logLogout(String username) {
        Map<String, Object> data = createAuditData(username, "User logged out");
        AuditEvent auditEvent = new AuditEvent(username, "LOGOUT", data);
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

    // Audit Data 생성
    private Map<String, Object> createAuditData(String username, String details) {
        Map<String, Object> data = new HashMap<>();
        data.put("details", details);
        return data;
    }
}
