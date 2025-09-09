package org.ddcn41.ticketing_system.global.util;

import org.springframework.boot.actuate.audit.AuditEvent;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

// 글로벌 감사 로그 빌더
public class AuditEventBuilder {
    private String principal;
    private String type;
    private Instant timestamp;
    private final Map<String, Object> data = new HashMap<>();

    public static AuditEventBuilder builder() {
        return new AuditEventBuilder();
    }

    public AuditEventBuilder principal(String principal) {
        this.principal = principal;
        return this;
    }

    public AuditEventBuilder type(String type) {
        this.type = type;
        return this;
    }

    public AuditEventBuilder timestamp(Instant timestamp) {
        this.timestamp = timestamp;
        return this;
    }

    public AuditEventBuilder data(String key, Object value) {
        this.data.put(key, value);
        return this;
    }

    public AuditEventBuilder data(Map<String, Object> data) {
        this.data.putAll(data);
        return this;
    }

    public AuditEventBuilder details(String details) {
        this.data.put("details", details);
        return this;
    }

    public AuditEvent build() {
        if(timestamp != null) {
            return new AuditEvent(timestamp, principal, type, data);
        }
        else {
            return new AuditEvent(principal, type, data);
        }
    }
}
