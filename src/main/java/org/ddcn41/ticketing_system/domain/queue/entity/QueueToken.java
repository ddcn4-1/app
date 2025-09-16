package org.ddcn41.ticketing_system.domain.queue.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.ddcn41.ticketing_system.domain.performance.entity.Performance;
import org.ddcn41.ticketing_system.domain.user.entity.User;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "queue_tokens",
        indexes = {
                @Index(name = "idx_queue_token_user_performance", columnList = "user_id, performance_id"),
                @Index(name = "idx_queue_token_status_expires", columnList = "status, expires_at"),
                @Index(name = "idx_queue_token_performance_status", columnList = "performance_id, status"),
                @Index(name = "idx_queue_token_issued_at", columnList = "issued_at")
        })
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class QueueToken {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "token_id")
    private Long tokenId;

    @Column(name = "token", unique = true, nullable = false, length = 64)
    private String token;

    // 외래키 관계 설정
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "performance_id", nullable = false)
    private Performance performance;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", length = 20, nullable = false)
    @Builder.Default
    private TokenStatus status = TokenStatus.WAITING;

    // 대기열 관련 필드 추가
    @Column(name = "position_in_queue")
    private Integer positionInQueue;

    @Column(name = "estimated_wait_time")
    private Integer estimatedWaitTimeMinutes;

    @Column(name = "issued_at", nullable = false)
    @Builder.Default
    private LocalDateTime issuedAt = LocalDateTime.now();

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "activated_at")
    private LocalDateTime activatedAt;

    @Column(name = "used_at")
    private LocalDateTime usedAt;

    // 토큰이 활성화된 후 예매 가능한 시간 (기본 10분)
    @Column(name = "booking_expires_at")
    private LocalDateTime bookingExpiresAt;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public enum TokenStatus {
        WAITING,    // 대기열에서 대기 중
        ACTIVE,     // 예매 가능 상태
        USED,       // 예매 완료로 사용됨
        EXPIRED,    // 시간 만료
        CANCELLED   // 사용자가 취소
    }

    // === 비즈니스 로직 메서드 ===

    /**
     * 토큰을 활성화 상태로 변경
     */
    public void activate() {
        this.status = TokenStatus.ACTIVE;
        this.activatedAt = LocalDateTime.now();
        this.bookingExpiresAt = LocalDateTime.now().plusMinutes(10); // 10분간 예매 가능
    }

    /**
     * 토큰 사용 완료 처리
     */
    public void markAsUsed() {
        this.status = TokenStatus.USED;
        this.usedAt = LocalDateTime.now();
    }

    /**
     * 토큰 만료 처리
     */
    public void markAsExpired() {
        this.status = TokenStatus.EXPIRED;
    }

    /**
     * 토큰이 예매 가능한 상태인지 확인
     */
    public boolean isActiveForBooking() {
        return status == TokenStatus.ACTIVE &&
                bookingExpiresAt != null &&
                bookingExpiresAt.isAfter(LocalDateTime.now());
    }

    /**
     * 토큰이 만료되었는지 확인
     */
    public boolean isExpired() {
        return LocalDateTime.now().isAfter(expiresAt) ||
                (bookingExpiresAt != null && LocalDateTime.now().isAfter(bookingExpiresAt));
    }

    /**
     * 대기 시간 업데이트
     */
    public void updateWaitInfo(int position, int estimatedMinutes) {
        this.positionInQueue = position;
        this.estimatedWaitTimeMinutes = estimatedMinutes;
    }
}