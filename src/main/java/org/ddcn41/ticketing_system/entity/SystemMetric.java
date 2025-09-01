package org.ddcn41.ticketing_system.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Entity
@Table(name = "system_metrics")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SystemMetric {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "metric_id")
    private Long metricId;

    @Column
    @CreationTimestamp
    private LocalDateTime timestamp;

    @Column(name = "active_users")
    @Builder.Default
    private Integer activeUsers = 0;

    @Column(name = "queue_length")
    @Builder.Default
    private Integer queueLength = 0;

    @Column(name = "cpu_usage", precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal cpuUsage = BigDecimal.ZERO;

    @Column(name = "memory_usage", precision = 5, scale = 2)
    @Builder.Default
    private BigDecimal memoryUsage = BigDecimal.ZERO;

    @Column(name = "request_count")
    @Builder.Default
    private Integer requestCount = 0;

    @Column(name = "avg_response_time", precision = 10, scale = 3)
    @Builder.Default
    private BigDecimal avgResponseTime = BigDecimal.ZERO;

    @Column(name = "server_id", length = 100)
    private String serverId;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;
}