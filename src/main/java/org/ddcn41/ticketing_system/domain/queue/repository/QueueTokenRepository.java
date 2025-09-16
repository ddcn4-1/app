package org.ddcn41.ticketing_system.domain.queue.repository;

import org.ddcn41.ticketing_system.domain.queue.entity.QueueToken;
import org.ddcn41.ticketing_system.domain.performance.entity.Performance;
import org.ddcn41.ticketing_system.domain.user.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface QueueTokenRepository extends JpaRepository<QueueToken, Long> {

    /**
     * 토큰 문자열로 조회
     */
    Optional<QueueToken> findByToken(String token);

    /**
     * 사용자와 공연으로 활성 토큰 조회
     */
    @Query("SELECT qt FROM QueueToken qt WHERE qt.user = :user AND qt.performance = :performance " +
            "AND qt.status IN ('WAITING', 'ACTIVE') ORDER BY qt.createdAt DESC")
    Optional<QueueToken> findActiveTokenByUserAndPerformance(@Param("user") User user,
                                                             @Param("performance") Performance performance);

    /**
     * 특정 공연의 대기열 순서 조회 (WAITING 상태만)
     */
    @Query("SELECT qt FROM QueueToken qt WHERE qt.performance = :performance AND qt.status = 'WAITING' " +
            "ORDER BY qt.issuedAt ASC")
    List<QueueToken> findWaitingTokensByPerformance(@Param("performance") Performance performance);

    /**
     * 특정 공연의 대기열에서 사용자의 순서 조회
     */
    @Query("SELECT COUNT(qt) FROM QueueToken qt WHERE qt.performance = :performance " +
            "AND qt.status = 'WAITING' AND qt.issuedAt < :issuedAt")
    Long findPositionInQueue(@Param("performance") Performance performance, @Param("issuedAt") LocalDateTime issuedAt);

    /**
     * 활성화 가능한 토큰들 조회 (대기열에서 앞순서부터)
     */
    @Query("SELECT qt FROM QueueToken qt WHERE qt.performance = :performance AND qt.status = 'WAITING' " +
            "ORDER BY qt.issuedAt ASC")
    List<QueueToken> findTokensToActivate(@Param("performance") Performance performance);

    /**
     * 만료된 토큰들 조회
     */
    @Query("SELECT qt FROM QueueToken qt WHERE (qt.expiresAt < :now OR qt.bookingExpiresAt < :now) " +
            "AND qt.status IN ('WAITING', 'ACTIVE')")
    List<QueueToken> findExpiredTokens(@Param("now") LocalDateTime now);

    /**
     * 특정 공연의 활성 토큰 수 조회
     */
    @Query("SELECT COUNT(qt) FROM QueueToken qt WHERE qt.performance = :performance AND qt.status = 'ACTIVE'")
    Long countActiveTokensByPerformance(@Param("performance") Performance performance);

    /**
     * 특정 공연의 대기 중인 토큰 수 조회
     */
    @Query("SELECT COUNT(qt) FROM QueueToken qt WHERE qt.performance = :performance AND qt.status = 'WAITING'")
    Long countWaitingTokensByPerformance(@Param("performance") Performance performance);

    /**
     * 사용자의 모든 활성 토큰 조회
     */
    @Query("SELECT qt FROM QueueToken qt WHERE qt.user = :user AND qt.status IN ('WAITING', 'ACTIVE') " +
            "ORDER BY qt.createdAt DESC")
    List<QueueToken> findActiveTokensByUser(@Param("user") User user);

    /**
     * 특정 공연의 토큰 상태별 통계
     */
    @Query("SELECT qt.status, COUNT(qt) FROM QueueToken qt WHERE qt.performance = :performance " +
            "GROUP BY qt.status")
    List<Object[]> getTokenStatsByPerformance(@Param("performance") Performance performance);

    /**
     * 일정 시간 이전에 생성된 사용된 토큰들 조회 (정리용)
     */
    @Query("SELECT qt FROM QueueToken qt WHERE qt.status = 'USED' AND qt.usedAt < :beforeTime")
    List<QueueToken> findOldUsedTokens(@Param("beforeTime") LocalDateTime beforeTime);
}