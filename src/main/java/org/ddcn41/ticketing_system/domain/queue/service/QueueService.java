package org.ddcn41.ticketing_system.domain.queue.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.ddcn41.ticketing_system.domain.queue.dto.response.QueueCheckResponse;
import org.ddcn41.ticketing_system.domain.queue.dto.response.QueueStatsResponse;
import org.ddcn41.ticketing_system.domain.queue.dto.response.QueueStatusResponse;
import org.ddcn41.ticketing_system.domain.queue.dto.response.TokenIssueResponse;
import org.ddcn41.ticketing_system.domain.queue.entity.QueueToken;
import org.ddcn41.ticketing_system.domain.queue.repository.QueueTokenRepository;
import org.ddcn41.ticketing_system.domain.performance.entity.Performance;
import org.ddcn41.ticketing_system.domain.performance.repository.PerformanceRepository;
import org.ddcn41.ticketing_system.domain.user.entity.User;
import org.ddcn41.ticketing_system.domain.user.repository.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class QueueService {

    private final QueueTokenRepository queueTokenRepository;
    private final PerformanceRepository performanceRepository;
    private final UserRepository userRepository;
    private final RedisTemplate<String, String> redisTemplate;

    @Value("${queue.max-active-tokens:100}")
    private int maxActiveTokens;

    @Value("${queue.token-valid-hours:24}")
    private int tokenValidHours;

    @Value("${queue.booking-time-minutes:10}")
    private int bookingTimeMinutes;

    private final SecureRandom secureRandom = new SecureRandom();
    private static final int MAX_CONCURRENT_SESSIONS = 2; // 동시 처리 한계
    private static final String LOCK_KEY_PREFIX = "session_lock:";
    private static final String SESSION_KEY_PREFIX = "active_sessions:";

    // Lua 스크립트로 원자적 락 해제
    private static final String RELEASE_LOCK_SCRIPT =
            "if redis.call('GET', KEYS[1]) == ARGV[1] then " +
                    "    return redis.call('DEL', KEYS[1]) " +
                    "else " +
                    "    return 0 " +
                    "end";

    /**
     * 대기열 필요성 확인 - 동시성 제어 추가
     */
    public QueueCheckResponse checkQueueRequirement(Long performanceId, Long scheduleId, Long userId) {
        String sessionKey = SESSION_KEY_PREFIX + performanceId + ":" + scheduleId;
        String lockKey = LOCK_KEY_PREFIX + performanceId + ":" + scheduleId;
        String lockValue = UUID.randomUUID().toString();

        try {
            // 1. 분산 락 획득 시도 (최대 3초 대기)
            boolean lockAcquired = acquireDistributedLock(lockKey, lockValue, 3000);

            if (!lockAcquired) {
                log.warn("Failed to acquire lock for performance {}, schedule {} - directing to queue",
                        performanceId, scheduleId);
                return createQueueRequiredResponse(performanceId, "시스템 처리 중입니다. 대기열에 참여해주세요.");
            }

            try {
                // 2. 현재 활성 세션 수 확인
                String currentSessionsStr = redisTemplate.opsForValue().get(sessionKey);
                int currentSessions = currentSessionsStr != null ? Integer.parseInt(currentSessionsStr) : 0;

                log.info("Current sessions for performance {}, schedule {}: {}/{}",
                        performanceId, scheduleId, currentSessions, MAX_CONCURRENT_SESSIONS);

                // 3. 세션 한도 체크
                if (currentSessions < MAX_CONCURRENT_SESSIONS) {
                    // 세션 카운트 증가
                    Long newCount = redisTemplate.opsForValue().increment(sessionKey);

                    // 세션 만료 시간 설정 (10분)
                    redisTemplate.expire(sessionKey, Duration.ofMinutes(10));

                    log.info("User {} granted direct access. New session count: {}", userId, newCount);

                    return QueueCheckResponse.builder()
                            .requiresQueue(false)
                            .canProceedDirectly(true)
                            .sessionId(UUID.randomUUID().toString())
                            .message("좌석 선택으로 이동합니다")
                            .currentActiveSessions(newCount.intValue())
                            .maxConcurrentSessions(MAX_CONCURRENT_SESSIONS)
                            .reason("서버 여유 있음")
                            .build();
                } else {
                    log.info("Session limit reached for performance {}, schedule {}. Directing user {} to queue",
                            performanceId, scheduleId, userId);
                    return createQueueRequiredResponse(performanceId,
                            "현재 많은 사용자가 접속중입니다. 대기열에 참여합니다.");
                }

            } finally {
                // 4. 분산 락 해제
                releaseDistributedLock(lockKey, lockValue);
            }

        } catch (Exception e) {
            log.error("Error in checkQueueRequirement for performance {}, schedule {}: {}",
                    performanceId, scheduleId, e.getMessage(), e);

            // 오류 시 안전하게 대기열로 유도
            return createQueueRequiredResponse(performanceId, "시스템 안정성을 위해 대기열에 참여합니다.");
        }
    }

    /**
     * 분산 락 획득
     */
    private boolean acquireDistributedLock(String lockKey, String lockValue, long timeoutMs) {
        try {
            long startTime = System.currentTimeMillis();
            long endTime = startTime + timeoutMs;

            while (System.currentTimeMillis() < endTime) {
                Boolean acquired = redisTemplate.opsForValue()
                        .setIfAbsent(lockKey, lockValue, Duration.ofSeconds(10));

                if (Boolean.TRUE.equals(acquired)) {
                    log.debug("Lock acquired: {}", lockKey);
                    return true;
                }

                // 짧은 대기 후 재시도
                Thread.sleep(50);
            }

            log.warn("Failed to acquire lock within timeout: {}", lockKey);
            return false;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Lock acquisition interrupted: {}", lockKey);
            return false;
        } catch (Exception e) {
            log.error("Error acquiring lock {}: {}", lockKey, e.getMessage());
            return false;
        }
    }

    /**
     * 분산 락 해제 (Lettuce 기반)
     */
    private void releaseDistributedLock(String lockKey, String lockValue) {
        try {
            DefaultRedisScript<Long> script = new DefaultRedisScript<>();
            script.setScriptText(RELEASE_LOCK_SCRIPT);
            script.setResultType(Long.class);

            Long result = redisTemplate.execute(script,
                    Collections.singletonList(lockKey), lockValue);

            if (result != null && result > 0) {
                log.debug("Lock released: {}", lockKey);
            } else {
                log.warn("Failed to release lock or lock not owned: {}", lockKey);
            }

        } catch (Exception e) {
            log.error("Error releasing lock {}: {}", lockKey, e.getMessage());
        }
    }

    /**
     * 대기열 필요 응답 생성
     */
    private QueueCheckResponse createQueueRequiredResponse(Long performanceId, String message) {
        int waitingCount = getCurrentWaitingCount(performanceId);
        int estimatedWait = Math.max(waitingCount * 30, 60); // 최소 1분 대기

        return QueueCheckResponse.builder()
                .requiresQueue(true)
                .canProceedDirectly(false)
                .message(message)
                .currentActiveSessions(MAX_CONCURRENT_SESSIONS)
                .maxConcurrentSessions(MAX_CONCURRENT_SESSIONS)
                .estimatedWaitTime(estimatedWait)
                .currentWaitingCount(waitingCount)
                .reason("서버 용량 초과")
                .build();
    }

    /**
     * 세션 해제 (사용자가 좌석 선택을 완료하거나 이탈할 때 호출)
     */
    public void releaseSession(Long performanceId, Long scheduleId, String sessionId) {
        String sessionKey = SESSION_KEY_PREFIX + performanceId + ":" + scheduleId;

        try {
            Long currentCount = redisTemplate.opsForValue().decrement(sessionKey);
            log.info("Session released for performance {}, schedule {}. Remaining: {}",
                    performanceId, scheduleId, Math.max(currentCount != null ? currentCount : 0, 0));

            // 카운트가 0 이하가 되면 키 삭제
            if (currentCount != null && currentCount <= 0) {
                redisTemplate.delete(sessionKey);
            }

        } catch (Exception e) {
            log.error("Error releasing session for performance {}, schedule {}: {}",
                    performanceId, scheduleId, e.getMessage());
        }
    }

    private int getCurrentWaitingCount(Long performanceId) {
        try {
            Performance performance = performanceRepository.findById(performanceId).orElse(null);
            if (performance == null) return 0;

            Long waitingCount = queueTokenRepository.countWaitingTokensByPerformance(performance);
            return waitingCount != null ? waitingCount.intValue() : 0;
        } catch (Exception e) {
            log.error("Error getting waiting count for performance {}: {}", performanceId, e.getMessage());
            return 5; // 기본값
        }
    }

    // ========== 기존 메서드들 (수정 없음) ==========

    /**
     * 대기열 토큰 발급
     */
    public TokenIssueResponse issueQueueToken(Long userId, Long performanceId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다"));

        Performance performance = performanceRepository.findById(performanceId)
                .orElseThrow(() -> new IllegalArgumentException("공연을 찾을 수 없습니다"));

        // 이미 활성 토큰이 있는지 확인
        Optional<QueueToken> existingToken = queueTokenRepository
                .findActiveTokenByUserAndPerformance(user, performance);

        if (existingToken.isPresent()) {
            QueueToken token = existingToken.get();
            if (!token.isExpired()) {
                return createTokenResponse(token, "기존 토큰이 존재합니다");
            } else {
                // 만료된 토큰은 상태 업데이트
                token.markAsExpired();
                queueTokenRepository.save(token);
            }
        }

        // 새 토큰 생성
        String tokenString = generateToken();

        QueueToken newToken = QueueToken.builder()
                .token(tokenString)
                .user(user)
                .performance(performance)
                .status(QueueToken.TokenStatus.WAITING)
                .issuedAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusHours(tokenValidHours))
                .build();

        // 현재 활성 토큰 수 확인하여 즉시 활성화 여부 결정
        Long activeCount = queueTokenRepository.countActiveTokensByPerformance(performance);

        if (activeCount < maxActiveTokens) {
            newToken.activate();
        }

        QueueToken savedToken = queueTokenRepository.save(newToken);

        // 대기열 순서 계산
        updateQueuePosition(savedToken);

        return createTokenResponse(savedToken, "토큰이 발급되었습니다");
    }

    /**
     * 토큰 상태 조회
     */
    @Transactional(readOnly = true)
    public QueueStatusResponse getTokenStatus(String token) {
        QueueToken queueToken = queueTokenRepository.findByToken(token)
                .orElseThrow(() -> new IllegalArgumentException("토큰을 찾을 수 없습니다"));

        if (queueToken.isExpired()) {
            queueToken.markAsExpired();
            queueTokenRepository.save(queueToken);
        }

        return QueueStatusResponse.builder()
                .token(queueToken.getToken())
                .status(queueToken.getStatus())
                .positionInQueue(queueToken.getPositionInQueue())
                .estimatedWaitTime(queueToken.getEstimatedWaitTimeMinutes())
                .isActiveForBooking(queueToken.isActiveForBooking())
                .bookingExpiresAt(queueToken.getBookingExpiresAt())
                .build();
    }

    /**
     * 토큰 사용 처리 (예매 완료 시 호출)
     */
    public void useToken(String token) {
        QueueToken queueToken = queueTokenRepository.findByToken(token)
                .orElseThrow(() -> new IllegalArgumentException("토큰을 찾을 수 없습니다"));

        if (!queueToken.isActiveForBooking()) {
            throw new IllegalStateException("예매 가능한 상태가 아닙니다");
        }

        queueToken.markAsUsed();
        queueTokenRepository.save(queueToken);

        // 다음 대기자 활성화
        activateNextTokens(queueToken.getPerformance());
    }

    /**
     * 토큰 검증 (예매 시 호출)
     */
    @Transactional(readOnly = true)
    public boolean validateTokenForBooking(String token, Long userId) {
        if (token == null || token.trim().isEmpty()) {
            return false;
        }

        Optional<QueueToken> optionalToken = queueTokenRepository.findByToken(token);
        if (optionalToken.isEmpty()) {
            return false;
        }

        QueueToken queueToken = optionalToken.get();

        // 토큰 소유자 확인
        if (!queueToken.getUser().getUserId().equals(userId)) {
            return false;
        }

        // 토큰 상태 확인
        return queueToken.isActiveForBooking();
    }

    /**
     * 만료된 토큰 정리 및 대기열 진행
     */
    public void processQueue() {
        LocalDateTime now = LocalDateTime.now();

        // 만료된 토큰들 처리
        List<QueueToken> expiredTokens = queueTokenRepository.findExpiredTokens(now);
        for (QueueToken token : expiredTokens) {
            token.markAsExpired();
            log.info("토큰 만료 처리: {}", token.getToken());
        }
        queueTokenRepository.saveAll(expiredTokens);

        // 각 공연별로 대기열 처리
        List<Performance> performances = performanceRepository.findAll();
        for (Performance performance : performances) {
            activateNextTokens(performance);
            updateWaitingQueuePositions(performance);
        }
    }

    /**
     * 사용자의 모든 활성 토큰 조회
     */
    @Transactional(readOnly = true)
    public List<QueueStatusResponse> getUserActiveTokens(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다"));

        List<QueueToken> tokens = queueTokenRepository.findActiveTokensByUser(user);

        return tokens.stream()
                .map(token -> QueueStatusResponse.builder()
                        .token(token.getToken())
                        .status(token.getStatus())
                        .positionInQueue(token.getPositionInQueue())
                        .estimatedWaitTime(token.getEstimatedWaitTimeMinutes())
                        .isActiveForBooking(token.isActiveForBooking())
                        .bookingExpiresAt(token.getBookingExpiresAt())
                        .performanceTitle(token.getPerformance().getTitle())
                        .build())
                .toList();
    }

    public void cancelToken(String token, Long userId) {
        QueueToken queueToken = queueTokenRepository.findByToken(token)
                .orElseThrow(() -> new IllegalArgumentException("토큰을 찾을 수 없습니다"));

        // 토큰 소유자 확인
        if (!queueToken.getUser().getUserId().equals(userId)) {
            throw new IllegalArgumentException("토큰을 취소할 권한이 없습니다");
        }

        // 이미 사용된 토큰은 취소 불가
        if (queueToken.getStatus() == QueueToken.TokenStatus.USED) {
            throw new IllegalStateException("이미 사용된 토큰은 취소할 수 없습니다");
        }

        queueToken.setStatus(QueueToken.TokenStatus.CANCELLED);
        queueTokenRepository.save(queueToken);

        log.info("토큰 취소: {} (사용자: {})", token, userId);

        // 다음 대기자 활성화
        activateNextTokens(queueToken.getPerformance());
    }

    /**
     * 오래된 사용 완료 토큰 정리 (7일 이상 된 것)
     */
    public void cleanupOldTokens() {
        LocalDateTime cutoffTime = LocalDateTime.now().minusDays(7);
        List<QueueToken> oldTokens = queueTokenRepository.findOldUsedTokens(cutoffTime);

        if (!oldTokens.isEmpty()) {
            queueTokenRepository.deleteAll(oldTokens);
            log.info("오래된 토큰 {} 개 정리 완료", oldTokens.size());
        }
    }
    /**
     * 모든 세션 초기화 (테스트용)
     */
    public void clearAllSessions() {
        try {
            // Redis에서 모든 세션 키 삭제
            String sessionPattern = SESSION_KEY_PREFIX + "*";
            Set<String> keys = redisTemplate.keys(sessionPattern);

            if (keys != null && !keys.isEmpty()) {
                redisTemplate.delete(keys);
                log.info("세션 {} 개 초기화 완료", keys.size());
            } else {
                log.info("초기화할 세션이 없습니다");
            }

            // 락 키도 초기화
            String lockPattern = LOCK_KEY_PREFIX + "*";
            Set<String> lockKeys = redisTemplate.keys(lockPattern);

            if (lockKeys != null && !lockKeys.isEmpty()) {
                redisTemplate.delete(lockKeys);
                log.info("락 {} 개 초기화 완료", lockKeys.size());
            }

        } catch (Exception e) {
            log.error("세션 초기화 중 오류 발생: {}", e.getMessage(), e);
            throw new RuntimeException("세션 초기화 실패", e);
        }
    }

    /**
     * 관리자용 - 공연별 대기열 통계 조회
     */
    @Transactional(readOnly = true)
    public List<QueueStatsResponse> getQueueStatsByPerformance() {
        List<Performance> performances = performanceRepository.findAll();

        return performances.stream()
                .map(this::createQueueStats)
                .filter(stats -> stats.getWaitingCount() > 0 || stats.getActiveCount() > 0)
                .toList();
    }

    private QueueStatsResponse createQueueStats(Performance performance) {
        List<Object[]> stats = queueTokenRepository.getTokenStatsByPerformance(performance);

        long waitingCount = 0, activeCount = 0, usedCount = 0, expiredCount = 0;

        for (Object[] stat : stats) {
            QueueToken.TokenStatus status = (QueueToken.TokenStatus) stat[0];
            Long count = (Long) stat[1];

            switch (status) {
                case WAITING -> waitingCount = count;
                case ACTIVE -> activeCount = count;
                case USED -> usedCount = count;
                case EXPIRED, CANCELLED -> expiredCount += count;
            }
        }

        // 평균 대기 시간 계산 (간단한 추정)
        int avgWaitTime = waitingCount > 0 ? (int) (waitingCount * 2) : 0;

        return QueueStatsResponse.builder()
                .performanceId(performance.getPerformanceId())
                .performanceTitle(performance.getTitle())
                .waitingCount(waitingCount)
                .activeCount(activeCount)
                .usedCount(usedCount)
                .expiredCount(expiredCount)
                .averageWaitTimeMinutes(avgWaitTime)
                .build();
    }

    /**
     * 관리자용 - 특정 공연의 대기열 강제 진행
     */
    public void forceProcessQueue(Long performanceId) {
        Performance performance = performanceRepository.findById(performanceId)
                .orElseThrow(() -> new IllegalArgumentException("공연을 찾을 수 없습니다"));

        activateNextTokens(performance);
        updateWaitingQueuePositions(performance);

        log.info("공연 {} 대기열 강제 처리 완료", performance.getTitle());
    }

    // === Private Helper Methods ===

    private String generateToken() {
        byte[] bytes = new byte[32];
        secureRandom.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private TokenIssueResponse createTokenResponse(QueueToken token, String message) {
        return TokenIssueResponse.builder()
                .token(token.getToken())
                .status(token.getStatus())
                .positionInQueue(token.getPositionInQueue())
                .estimatedWaitTime(token.getEstimatedWaitTimeMinutes())
                .message(message)
                .expiresAt(token.getExpiresAt())
                .bookingExpiresAt(token.getBookingExpiresAt())
                .build();
    }

    private void updateQueuePosition(QueueToken token) {
        if (token.getStatus() == QueueToken.TokenStatus.WAITING) {
            Long position = queueTokenRepository.findPositionInQueue(
                    token.getPerformance(), token.getIssuedAt()) + 1;

            // 예상 대기 시간 계산 (1분에 5명씩 처리 가정)
            int estimatedMinutes = (int) (position * 0.2);

            token.updateWaitInfo(position.intValue(), estimatedMinutes);
            queueTokenRepository.save(token);
        }
    }

    private void activateNextTokens(Performance performance) {
        Long activeCount = queueTokenRepository.countActiveTokensByPerformance(performance);

        if (activeCount < maxActiveTokens) {
            int tokensToActivate = (int) (maxActiveTokens - activeCount);

            List<QueueToken> waitingTokens = queueTokenRepository
                    .findTokensToActivate(performance);

            waitingTokens.stream()
                    .limit(tokensToActivate)
                    .forEach(token -> {
                        token.activate();
                        log.info("토큰 활성화: {}", token.getToken());
                    });

            if (!waitingTokens.isEmpty()) {
                queueTokenRepository.saveAll(waitingTokens.stream()
                        .limit(tokensToActivate)
                        .toList());
            }
        }
    }

    private void updateWaitingQueuePositions(Performance performance) {
        List<QueueToken> waitingTokens = queueTokenRepository
                .findWaitingTokensByPerformance(performance);

        for (int i = 0; i < waitingTokens.size(); i++) {
            QueueToken token = waitingTokens.get(i);
            int position = i + 1;
            int estimatedMinutes = position * 2; // 2분에 1명씩 처리 가정

            token.updateWaitInfo(position, estimatedMinutes);
        }

        if (!waitingTokens.isEmpty()) {
            queueTokenRepository.saveAll(waitingTokens);
        }
    }
}