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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class QueueService {

    private final QueueTokenRepository queueTokenRepository;
    private final PerformanceRepository performanceRepository;
    private final UserRepository userRepository;
    private final RedisTemplate<String, String> redisTemplate;
    private final SecureRandom secureRandom = new SecureRandom();

    @Value("${queue.max-active-tokens:3}")
    private int maxActiveTokens; // 최대 3명

    @Value("${queue.max-inactive-seconds:120}")
    private int maxInactiveSeconds;

    @Value("${queue.wait-time-per-person:10}")
    private int waitTimePerPerson; // 1명당 10초

    private static final String SESSION_KEY_PREFIX = "active_sessions:";
    private static final String HEARTBEAT_KEY_PREFIX = "heartbeat:";
    private static final String ACTIVE_TOKENS_KEY_PREFIX = "active_tokens:"; // 새로 추가

    /**
     * 대기열 필요성 확인 - Redis 기반으로 통일
     */
    public QueueCheckResponse checkQueueRequirement(Long performanceId, Long scheduleId, Long userId) {
        String sessionKey = SESSION_KEY_PREFIX + performanceId + ":" + scheduleId;
        String activeTokensKey = ACTIVE_TOKENS_KEY_PREFIX + performanceId;

        try {
            // Redis에서 현재 활성 토큰 수 확인 (DB 대신 Redis 사용)
            String activeTokensStr = redisTemplate.opsForValue().get(activeTokensKey);
            int activeTokens = activeTokensStr != null ? Integer.parseInt(activeTokensStr) : 0;

            log.info("=== 대기열 확인 ===");
            log.info("사용자: {}, 현재 활성 토큰: {}/{}", userId, activeTokens, maxActiveTokens);
            log.info("Redis activeTokensKey: {}, 값: {}", activeTokensKey, activeTokensStr);

            if (activeTokens < maxActiveTokens) {
                // Redis에서 활성 토큰 수 증가
                redisTemplate.opsForValue().increment(activeTokensKey);
                redisTemplate.expire(activeTokensKey, Duration.ofMinutes(10));

                // 세션도 증가 (기존 로직 유지)
                redisTemplate.opsForValue().increment(sessionKey);
                redisTemplate.expire(sessionKey, Duration.ofMinutes(10));

                // heartbeat 시작
                startHeartbeat(userId, performanceId, scheduleId);

                log.info(">>> 바로 입장 허용 - 새로운 활성 토큰 수: {}", activeTokens + 1);

                return QueueCheckResponse.builder()
                        .requiresQueue(false)
                        .canProceedDirectly(true)
                        .sessionId(UUID.randomUUID().toString())
                        .message("좌석 선택으로 이동합니다")
                        .currentActiveSessions(activeTokens + 1)
                        .maxConcurrentSessions(maxActiveTokens)
                        .reason("서버 여유 있음")
                        .build();
            } else {
                // 대기열 필요
                int waitingCount = getRedisWaitingCount(performanceId);
                int estimatedWait = (waitingCount + 1) * waitTimePerPerson;

                log.info(">>> 대기열 필요 - 대기자: {}명, 예상 대기: {}초", waitingCount + 1, estimatedWait);

                return QueueCheckResponse.builder()
                        .requiresQueue(true)
                        .canProceedDirectly(false)
                        .message("현재 많은 사용자가 접속중입니다. 대기열에 참여합니다.")
                        .currentActiveSessions(activeTokens)
                        .maxConcurrentSessions(maxActiveTokens)
                        .estimatedWaitTime(estimatedWait)
                        .currentWaitingCount(waitingCount + 1)
                        .reason("서버 용량 초과 (3명 초과)")
                        .build();
            }
        } catch (Exception e) {
            log.error("대기열 확인 중 오류 발생", e);
            return QueueCheckResponse.builder()
                    .requiresQueue(true)
                    .canProceedDirectly(false)
                    .message("시스템 오류로 대기열에 참여합니다.")
                    .reason("시스템 오류")
                    .build();
        }
    }

    /**
     * 대기열 토큰 발급 - Redis 기반
     */
    public TokenIssueResponse issueQueueToken(Long userId, Long performanceId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다"));

        Performance performance = performanceRepository.findById(performanceId)
                .orElseThrow(() -> new IllegalArgumentException("공연을 찾을 수 없습니다"));

        // 기존 토큰 확인
        Optional<QueueToken> existingToken = queueTokenRepository
                .findActiveTokenByUserAndPerformance(user, performance);

        if (existingToken.isPresent()) {
            QueueToken token = existingToken.get();
            if (!token.isExpired()) {
                updateQueuePosition(token);
                log.info("기존 토큰 반환: {}", token.getToken());
                return createTokenResponse(token, "기존 토큰을 반환합니다.");
            } else {
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
                .expiresAt(LocalDateTime.now().plusHours(2))
                .build();

        QueueToken savedToken = queueTokenRepository.save(newToken);
        updateQueuePosition(savedToken);

        // Redis에서 즉시 활성화 가능한지 확인
        String activeTokensKey = ACTIVE_TOKENS_KEY_PREFIX + performanceId;
        String activeTokensStr = redisTemplate.opsForValue().get(activeTokensKey);
        int currentActive = activeTokensStr != null ? Integer.parseInt(activeTokensStr) : 0;

        log.info("토큰 발급 후 활성화 체크 - 현재 활성: {}/{}", currentActive, maxActiveTokens);

        if (currentActive < maxActiveTokens) {
            // Redis에서 활성 토큰 수 증가
            redisTemplate.opsForValue().increment(activeTokensKey);
            redisTemplate.expire(activeTokensKey, Duration.ofMinutes(10));

            // DB에서 토큰 활성화
            savedToken.activate();
            savedToken.setPositionInQueue(0);
            savedToken.setEstimatedWaitTimeMinutes(0);
            savedToken = queueTokenRepository.save(savedToken);

            log.info(">>> 즉시 활성화: {}", savedToken.getToken());
            return createTokenResponse(savedToken, "예매 세션이 활성화되었습니다.");
        }

        log.info(">>> 대기열 추가: {}", savedToken.getToken());
        return createTokenResponse(savedToken, "대기열에 추가되었습니다.");
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
        } else if (queueToken.getStatus() == QueueToken.TokenStatus.WAITING) {
            updateQueuePosition(queueToken);
        }

        Integer position = queueToken.getPositionInQueue() != null ? queueToken.getPositionInQueue() : 1;
        Integer waitTime = queueToken.getEstimatedWaitTimeMinutes() != null ?
                queueToken.getEstimatedWaitTimeMinutes() : position * waitTimePerPerson / 60;

        return QueueStatusResponse.builder()
                .token(queueToken.getToken())
                .status(queueToken.getStatus())
                .positionInQueue(position)
                .estimatedWaitTime(waitTime)
                .isActiveForBooking(queueToken.isActiveForBooking())
                .bookingExpiresAt(queueToken.getBookingExpiresAt())
                .build();
    }

    /**
     * 토큰 검증 - 만료 시 Redis 동기화
     */
    @Transactional
    public boolean validateTokenForBooking(String token, Long userId) {
        if (token == null || token.trim().isEmpty()) {
            return false;
        }

        Optional<QueueToken> optionalToken = queueTokenRepository.findByToken(token);
        if (optionalToken.isEmpty()) {
            return false;
        }

        QueueToken queueToken = optionalToken.get();

        if (queueToken.isExpired()) {
            queueToken.markAsExpired();
            queueTokenRepository.save(queueToken);

            // Redis에서도 활성 토큰 수 감소
            if (queueToken.getStatus() == QueueToken.TokenStatus.ACTIVE) {
                releaseTokenFromRedis(queueToken.getPerformance().getPerformanceId());
                activateNextTokens(queueToken.getPerformance());
            }

            return false;
        }

        if (!queueToken.getUser().getUserId().equals(userId)) {
            return false;
        }

        return queueToken.isActiveForBooking();
    }

    /**
     * 토큰 사용 완료 - Redis와 DB 동기화
     */
    public void useToken(String token) {
        QueueToken queueToken = queueTokenRepository.findByToken(token)
                .orElseThrow(() -> new IllegalArgumentException("토큰을 찾을 수 없습니다"));

        if (!queueToken.isActiveForBooking()) {
            throw new IllegalStateException("예매 가능한 상태가 아닙니다");
        }

        // DB에서 토큰 사용 완료 처리
        queueToken.markAsUsed();
        queueTokenRepository.save(queueToken);

        // Redis에서 활성 토큰 수 감소
        releaseTokenFromRedis(queueToken.getPerformance().getPerformanceId());

        log.info(">>> 토큰 사용 완료: {}", token);

        // 다음 대기자 활성화
        activateNextTokens(queueToken.getPerformance());
    }

    /**
     * 세션 해제 - Redis와 DB 동기화
     */
    public void releaseSession(Long userId, Long performanceId, Long scheduleId) {
        String sessionKey = SESSION_KEY_PREFIX + performanceId + ":" + scheduleId;
        String heartbeatKey = HEARTBEAT_KEY_PREFIX + userId + ":" + performanceId + ":" + scheduleId;

        log.info("=== 세션 해제 시작 ===");
        log.info("사용자: {}, 공연: {}", userId, performanceId);

        // 1. 세션 카운트 감소
        Long currentCount = redisTemplate.opsForValue().decrement(sessionKey);
        if (currentCount < 0) {
            redisTemplate.opsForValue().set(sessionKey, "0");
        }

        // 2. heartbeat 제거
        redisTemplate.delete(heartbeatKey);

        // 3. **핵심**: DB에서 해당 사용자의 활성 토큰을 만료 처리
        try {
            User user = userRepository.findById(userId).orElse(null);
            Performance performance = performanceRepository.findById(performanceId).orElse(null);

            if (user != null && performance != null) {
                Optional<QueueToken> activeToken = queueTokenRepository
                        .findActiveTokenByUserAndPerformance(user, performance);

                if (activeToken.isPresent() &&
                        activeToken.get().getStatus() == QueueToken.TokenStatus.ACTIVE) {

                    QueueToken token = activeToken.get();

                    // DB에서 토큰 만료 처리
                    token.markAsExpired();
                    queueTokenRepository.save(token);

                    // Redis에서 활성 토큰 수 감소
                    releaseTokenFromRedis(performanceId);

                    log.info(">>> DB 토큰 만료 처리: {}", token.getToken());

                    // 즉시 다음 대기자 활성화
                    activateNextTokens(performance);
                } else {
                    log.info(">>> 해당 사용자의 활성 토큰 없음");
                }
            }
        } catch (Exception e) {
            log.error("토큰 만료 처리 중 오류", e);
        }

        log.info(">>> 세션 해제 완료 - 현재 세션: {}", currentCount);
    }

    /**
     * Redis에서 활성 토큰 수 감소
     */
    private void releaseTokenFromRedis(Long performanceId) {
        String activeTokensKey = ACTIVE_TOKENS_KEY_PREFIX + performanceId;
        Long activeCount = redisTemplate.opsForValue().decrement(activeTokensKey);
        if (activeCount < 0) {
            redisTemplate.opsForValue().set(activeTokensKey, "0");
        }
        log.info("Redis 활성 토큰 수 감소: {}", activeCount);
    }

    /**
     * 다음 대기자 활성화 - Redis 기반
     */
    private void activateNextTokens(Performance performance) {
        String activeTokensKey = ACTIVE_TOKENS_KEY_PREFIX + performance.getPerformanceId();
        String activeTokensStr = redisTemplate.opsForValue().get(activeTokensKey);
        int currentActive = activeTokensStr != null ? Integer.parseInt(activeTokensStr) : 0;

        log.info("=== 다음 대기자 활성화 체크 ===");
        log.info("공연: {}, 현재 활성: {}/{}", performance.getTitle(), currentActive, maxActiveTokens);

        if (currentActive < maxActiveTokens) {
            int tokensToActivate = maxActiveTokens - currentActive;
            List<QueueToken> waitingTokens = queueTokenRepository.findTokensToActivate(performance);

            log.info("활성화 가능한 슬롯: {}, 대기중인 토큰: {}개", tokensToActivate, waitingTokens.size());

            List<QueueToken> tokensToUpdate = waitingTokens.stream()
                    .limit(tokensToActivate)
                    .peek(token -> {
                        // Redis에서 활성 토큰 수 증가
                        redisTemplate.opsForValue().increment(activeTokensKey);
                        redisTemplate.expire(activeTokensKey, Duration.ofMinutes(10));

                        // DB에서 토큰 활성화
                        token.activate();
                        token.setPositionInQueue(0);
                        token.setEstimatedWaitTimeMinutes(0);
                        log.info(">>> 토큰 활성화: {}", token.getToken());
                    })
                    .toList();

            if (!tokensToUpdate.isEmpty()) {
                queueTokenRepository.saveAll(tokensToUpdate);
                // 나머지 대기자 위치 재계산
                updateWaitingPositions(performance);
            }
        }
    }

    // ========== Helper Methods ==========

    /**
     * Redis에서 대기자 수 조회
     */
    private int getRedisWaitingCount(Long performanceId) {
        Performance performance = performanceRepository.findById(performanceId).orElse(null);
        return performance != null ? queueTokenRepository.countWaitingTokensByPerformance(performance).intValue() : 0;
    }

    private void startHeartbeat(Long userId, Long performanceId, Long scheduleId) {
        String heartbeatKey = HEARTBEAT_KEY_PREFIX + userId + ":" + performanceId + ":" + scheduleId;
        redisTemplate.opsForValue().set(heartbeatKey, LocalDateTime.now().toString(),
                Duration.ofSeconds(maxInactiveSeconds));
        log.info("Heartbeat 시작: {}", heartbeatKey);
    }

    public void updateHeartbeat(Long userId, Long performanceId, Long scheduleId) {
        String heartbeatKey = HEARTBEAT_KEY_PREFIX + userId + ":" + performanceId + ":" + scheduleId;
        redisTemplate.opsForValue().set(heartbeatKey, LocalDateTime.now().toString(),
                Duration.ofSeconds(maxInactiveSeconds));
    }

    public void cleanupInactiveSessions() {
        try {
            LocalDateTime cutoff = LocalDateTime.now().minusSeconds(maxInactiveSeconds);
            Set<String> heartbeatKeys = redisTemplate.keys(HEARTBEAT_KEY_PREFIX + "*");

            if (heartbeatKeys != null) {
                for (String heartbeatKey : heartbeatKeys) {
                    String lastHeartbeat = redisTemplate.opsForValue().get(heartbeatKey);
                    if (lastHeartbeat != null) {
                        LocalDateTime lastTime = LocalDateTime.parse(lastHeartbeat);
                        if (lastTime.isBefore(cutoff)) {
                            processTimeout(heartbeatKey);
                        }
                    }
                }
            }

            // 만료된 토큰들 처리
            List<QueueToken> expiredTokens = queueTokenRepository.findExpiredTokens(LocalDateTime.now());
            for (QueueToken token : expiredTokens) {
                if (token.getStatus() == QueueToken.TokenStatus.ACTIVE) {
                    token.markAsExpired();
                    releaseTokenFromRedis(token.getPerformance().getPerformanceId());
                    activateNextTokens(token.getPerformance());
                }
            }
            if (!expiredTokens.isEmpty()) {
                queueTokenRepository.saveAll(expiredTokens);
            }

        } catch (Exception e) {
            log.error("비활성 세션 정리 중 오류", e);
        }
    }

    private void processTimeout(String heartbeatKey) {
        try {
            String[] parts = heartbeatKey.replace(HEARTBEAT_KEY_PREFIX, "").split(":");
            if (parts.length >= 3) {
                Long userId = Long.parseLong(parts[0]);
                Long performanceId = Long.parseLong(parts[1]);
                Long scheduleId = Long.parseLong(parts[2]);

                log.warn("세션 타임아웃 - 사용자: {}", userId);
                releaseSession(userId, performanceId, scheduleId);
            }
        } catch (Exception e) {
            log.error("타임아웃 처리 중 오류", e);
        }
    }

    private void updateWaitingPositions(Performance performance) {
        List<QueueToken> waitingTokens = queueTokenRepository
                .findWaitingTokensByPerformance(performance);

        for (int i = 0; i < waitingTokens.size(); i++) {
            QueueToken token = waitingTokens.get(i);
            int position = i + 1;
            int estimatedSeconds = position * waitTimePerPerson;
            int estimatedMinutes = Math.max(1, estimatedSeconds / 60);

            token.setPositionInQueue(position);
            token.setEstimatedWaitTimeMinutes(estimatedMinutes);
        }

        if (!waitingTokens.isEmpty()) {
            queueTokenRepository.saveAll(waitingTokens);
        }
    }

    private void updateQueuePosition(QueueToken token) {
        if (token.getStatus() == QueueToken.TokenStatus.WAITING) {
            Long position = queueTokenRepository.findPositionInQueue(
                    token.getPerformance(), token.getIssuedAt()) + 1;
            int estimatedSeconds = position.intValue() * waitTimePerPerson;
            int estimatedMinutes = Math.max(1, estimatedSeconds / 60);

            token.setPositionInQueue(position.intValue());
            token.setEstimatedWaitTimeMinutes(estimatedMinutes);
            queueTokenRepository.save(token);
        }
    }

    private String generateToken() {
        byte[] bytes = new byte[16];
        secureRandom.nextBytes(bytes);
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    private TokenIssueResponse createTokenResponse(QueueToken token, String message) {
        Integer position = token.getPositionInQueue() != null ? token.getPositionInQueue() : 1;
        Integer waitTime = token.getEstimatedWaitTimeMinutes() != null ?
                token.getEstimatedWaitTimeMinutes() : position * waitTimePerPerson / 60;

        return TokenIssueResponse.builder()
                .token(token.getToken())
                .status(token.getStatus())
                .positionInQueue(position)
                .estimatedWaitTime(waitTime)
                .message(message)
                .expiresAt(token.getExpiresAt())
                .bookingExpiresAt(token.getBookingExpiresAt())
                .build();
    }

    // ========== API Methods (기존 호환성) ==========

    @Transactional(readOnly = true)
    public QueueToken getTokenByString(String token) {
        return queueTokenRepository.findByToken(token)
                .orElseThrow(() -> new IllegalArgumentException("토큰을 찾을 수 없습니다: " + token));
    }

    public void cancelToken(String token, Long userId) {
        QueueToken queueToken = queueTokenRepository.findByToken(token)
                .orElseThrow(() -> new IllegalArgumentException("토큰을 찾을 수 없습니다"));

        if (!queueToken.getUser().getUserId().equals(userId)) {
            throw new IllegalArgumentException("토큰을 취소할 권한이 없습니다");
        }

        queueToken.setStatus(QueueToken.TokenStatus.CANCELLED);
        queueTokenRepository.save(queueToken);

        // 활성 상태였다면 Redis에서도 해제
        if (queueToken.getStatus() == QueueToken.TokenStatus.ACTIVE) {
            releaseTokenFromRedis(queueToken.getPerformance().getPerformanceId());
        }

        activateNextTokens(queueToken.getPerformance());
    }

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

    public void clearAllSessions() {
        try {
            Set<String> sessionKeys = redisTemplate.keys(SESSION_KEY_PREFIX + "*");
            Set<String> heartbeatKeys = redisTemplate.keys(HEARTBEAT_KEY_PREFIX + "*");
            Set<String> activeTokenKeys = redisTemplate.keys(ACTIVE_TOKENS_KEY_PREFIX + "*");

            if (sessionKeys != null && !sessionKeys.isEmpty()) {
                redisTemplate.delete(sessionKeys);
            }
            if (heartbeatKeys != null && !heartbeatKeys.isEmpty()) {
                redisTemplate.delete(heartbeatKeys);
            }
            if (activeTokenKeys != null && !activeTokenKeys.isEmpty()) {
                redisTemplate.delete(activeTokenKeys);
            }
            log.info("모든 세션 초기화 완료");
        } catch (Exception e) {
            log.error("세션 초기화 중 오류", e);
        }
    }

    public void cleanupOldTokens() {
        LocalDateTime cutoffTime = LocalDateTime.now().minusDays(1);
        List<QueueToken> oldTokens = queueTokenRepository.findOldUsedTokens(cutoffTime);
        if (!oldTokens.isEmpty()) {
            queueTokenRepository.deleteAll(oldTokens);
            log.info("오래된 토큰 {} 개 정리 완료", oldTokens.size());
        }
    }

    // ========== 기존 호환성을 위한 스텁 메서드들 ==========

    public void processQueue() {
        cleanupInactiveSessions();
    }

    @Transactional(readOnly = true)
    public List<QueueStatsResponse> getQueueStatsByPerformance() {
        List<Performance> performances = performanceRepository.findAll();

        return performances.stream()
                .map(this::createQueueStats)
                .filter(stats -> stats.getWaitingCount() > 0 || stats.getActiveCount() > 0)
                .toList();
    }

    public void forceProcessQueue(Long performanceId) {
        Performance performance = performanceRepository.findById(performanceId)
                .orElseThrow(() -> new IllegalArgumentException("공연을 찾을 수 없습니다"));

        activateNextTokens(performance);
        updateWaitingPositions(performance);
        log.info("공연 {} 대기열 강제 처리 완료", performance.getTitle());
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
}