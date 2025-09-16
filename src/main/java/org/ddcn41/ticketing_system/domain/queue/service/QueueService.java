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
import java.util.UUID;
import java.util.Set;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class QueueService {

    private final QueueTokenRepository queueTokenRepository;
    private final PerformanceRepository performanceRepository;
    private final UserRepository userRepository;
    private final RedisTemplate<String, String> redisTemplate;

    private static final String SESSION_KEY_PREFIX = "active_sessions:";
    private static final String LOCK_KEY_PREFIX = "heartbeat:";

    @Value("${queue.max-active-tokens:100}")
    private int maxActiveTokens;

    @Value("${queue.overbooking-ratio:1.5}")
    private double overbookingRatio;

    @Value("${queue.token-valid-hours:24}")
    private int tokenValidHours;

    @Value("${queue.booking-time-minutes:10}")
    private int bookingTimeMinutes;

    @Value("${queue.session-timeout-minutes:5}")
    private int sessionTimeoutMinutes;

    @Value("${queue.max-inactive-minutes:2}")
    private int maxInactiveMinutes;

    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * 오버부킹이 적용된 최대 동시 세션 수 계산
     */
    private int getMaxConcurrentSessions() {
        return (int) (maxActiveTokens * overbookingRatio);
    }

    /**
     * 대기열 필요성 확인 (오버부킹 적용)
     */
    public QueueCheckResponse checkQueueRequirement(Long performanceId, Long scheduleId, Long userId) {
        String sessionKey = "active_sessions:" + performanceId + ":" + scheduleId;

        try {
            // 현재 활성 세션 수 확인
            String currentSessions = redisTemplate.opsForValue().get(sessionKey);
            int activeSessions = currentSessions != null ? Integer.parseInt(currentSessions) : 0;

            int maxConcurrentSessions = getMaxConcurrentSessions(); // 오버부킹 적용된 값

            if (activeSessions < maxConcurrentSessions) {
                // 바로 진입 허용 (오버부킹 범위 내)
                redisTemplate.opsForValue().increment(sessionKey);
                redisTemplate.expire(sessionKey, Duration.ofMinutes(sessionTimeoutMinutes));

                // 사용자 heartbeat 추적 시작
                startHeartbeatTracking(userId, performanceId, scheduleId);

                return QueueCheckResponse.builder()
                        .requiresQueue(false)
                        .canProceedDirectly(true)
                        .sessionId(UUID.randomUUID().toString())
                        .message("좌석 선택으로 이동합니다")
                        .currentActiveSessions(activeSessions + 1)
                        .maxConcurrentSessions(maxConcurrentSessions)
                        .reason("서버 여유 있음 (오버부킹 범위)")
                        .build();
            } else {
                // 대기열 필요
                Performance performance = performanceRepository.findById(performanceId).orElse(null);
                int waitingCount = performance != null ? getCurrentWaitingCount(performanceId) : 0;
                int estimatedWait = waitingCount * 30; // 1인당 30초 예상

                return QueueCheckResponse.builder()
                        .requiresQueue(true)
                        .canProceedDirectly(false)
                        .message("현재 많은 사용자가 접속중입니다. 대기열에 참여합니다.")
                        .currentActiveSessions(activeSessions)
                        .maxConcurrentSessions(maxConcurrentSessions)
                        .estimatedWaitTime(estimatedWait)
                        .currentWaitingCount(waitingCount)
                        .reason("서버 용량 초과 (오버부킹 한계)")
                        .build();
            }
        } catch (Exception e) {
            log.error("대기열 확인 중 오류 발생", e);
            // 오류 시 안전하게 대기열로 보냄
            return QueueCheckResponse.builder()
                    .requiresQueue(true)
                    .canProceedDirectly(false)
                    .message("시스템 오류로 대기열에 참여합니다.")
                    .reason("시스템 오류")
                    .build();
        }
    }

    /**
     * 사용자 heartbeat 추적 시작
     */
    private void startHeartbeatTracking(Long userId, Long performanceId, Long scheduleId) {
        String heartbeatKey = "heartbeat:" + userId + ":" + performanceId + ":" + scheduleId;
        redisTemplate.opsForValue().set(heartbeatKey,
                LocalDateTime.now().toString(),
                Duration.ofMinutes(maxInactiveMinutes));
    }

    /**
     * Heartbeat 업데이트
     */
    public void updateHeartbeat(Long userId, Long performanceId, Long scheduleId) {
        String heartbeatKey = "heartbeat:" + userId + ":" + performanceId + ":" + scheduleId;
        redisTemplate.opsForValue().set(heartbeatKey,
                LocalDateTime.now().toString(),
                Duration.ofMinutes(maxInactiveMinutes));

        log.debug("Heartbeat updated for user {} in performance {}", userId, performanceId);
    }

    /**
     * 세션 명시적 해제 (사용자가 페이지를 떠날 때)
     */
    public void releaseSession(Long userId, Long performanceId, Long scheduleId) {
        String sessionKey = "active_sessions:" + performanceId + ":" + scheduleId;
        String heartbeatKey = "heartbeat:" + userId + ":" + performanceId + ":" + scheduleId;

        // 세션 카운트 감소
        Long currentCount = redisTemplate.opsForValue().decrement(sessionKey);
        if (currentCount < 0) {
            redisTemplate.opsForValue().set(sessionKey, "0");
        }

        // heartbeat 추적 중단
        redisTemplate.delete(heartbeatKey);

        log.info("Session released for user {} in performance {}", userId, performanceId);

        // 다음 대기자 활성화
        Performance performance = performanceRepository.findById(performanceId).orElse(null);
        if (performance != null) {
            activateNextTokens(performance);
        }
    }

    /**
     * 비활성 세션 정리 (스케줄러에서 호출)
     */
    @Transactional
    public void cleanupInactiveSessions() {
        try {
            log.debug("비활성 세션 정리 작업 시작");

            // 만료된 heartbeat 키들을 찾아서 정리
            // 실제로는 Redis의 expired event나 별도 추적 구조가 필요하지만
            // 여기서는 간단히 만료된 토큰들을 통해 처리

            LocalDateTime cutoff = LocalDateTime.now().minusMinutes(maxInactiveMinutes);
            List<QueueToken> potentiallyInactive = queueTokenRepository
                    .findTokensLastAccessedBefore(cutoff); // 이 메서드는 추가 구현 필요

            for (QueueToken token : potentiallyInactive) {
                String heartbeatKey = "heartbeat:" + token.getUser().getUserId() +
                        ":" + token.getPerformance().getPerformanceId() + ":*";

                // heartbeat 확인
                if (!redisTemplate.hasKey(heartbeatKey)) {
                    log.info("비활성 세션 정리: 토큰 {}", token.getToken());
                    // 세션 해제 처리
                    releaseSessionByToken(token);
                }
            }

            log.debug("비활성 세션 정리 작업 완료");
        } catch (Exception e) {
            log.error("비활성 세션 정리 중 오류 발생", e);
        }
    }

    /**
     * 토큰으로 세션 해제
     */
    private void releaseSessionByToken(QueueToken token) {
        if (token.getStatus() == QueueToken.TokenStatus.ACTIVE) {
            token.markAsExpired();
            queueTokenRepository.save(token);

            // 다음 대기자 활성화
            activateNextTokens(token.getPerformance());
        }
    }

    /**
     * 현재 대기 중인 사용자 수 조회
     */
    private int getCurrentWaitingCount(Long performanceId) {
        Performance performance = performanceRepository.findById(performanceId).orElse(null);
        if (performance == null) return 0;

        return queueTokenRepository.countWaitingTokensByPerformance(performance).intValue();
    }

    /**
     * 다음 대기자들을 활성화 (수정됨)
     */
    private void activateNextTokens(Performance performance) {
        Long activeCount = queueTokenRepository.countActiveTokensByPerformance(performance);
        int maxConcurrentSessions = getMaxConcurrentSessions();

        if (activeCount < maxConcurrentSessions) {
            int tokensToActivate = (int) (maxConcurrentSessions - activeCount);

            List<QueueToken> waitingTokens = queueTokenRepository
                    .findTokensToActivate(performance);

            List<QueueToken> tokensToUpdate = waitingTokens.stream()
                    .limit(tokensToActivate)
                    .peek(token -> {
                        token.activate();
                        // 활성화된 토큰은 위치를 0으로 설정
                        token.setPositionInQueue(0);
                        token.setEstimatedWaitTimeMinutes(0);
                        log.info("토큰 활성화: {} (오버부킹 적용)", token.getToken());
                    })
                    .toList();

            if (!tokensToUpdate.isEmpty()) {
                queueTokenRepository.saveAll(tokensToUpdate);

                // 나머지 대기 중인 토큰들의 위치 재계산
                updateWaitingQueuePositions(performance);
            }
        }
    }

    /**
     * 대기열 토큰 발급 (null 값 처리 강화)
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
                // 기존 토큰 위치 정보 강제 업데이트
                forceUpdateTokenPosition(token, performance);
                return createTokenResponse(token, "기존 토큰이 존재합니다");
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
                .expiresAt(LocalDateTime.now().plusHours(tokenValidHours))
                .positionInQueue(1) //  기본값 설정
                .estimatedWaitTimeMinutes(60) //  기본값 설정
                .build();

        // 현재 활성 토큰 수 확인
        Long activeCount = queueTokenRepository.countActiveTokensByPerformance(performance);
        int maxConcurrentSessions = getMaxConcurrentSessions();

        if (activeCount < maxConcurrentSessions) {
            newToken.activate(); // 즉시 활성화
        }

        QueueToken savedToken = queueTokenRepository.save(newToken);

        //  위치 정보 강제 업데이트 (null 방지)
        forceUpdateTokenPosition(savedToken, performance);

        return createTokenResponse(savedToken, "토큰이 발급되었습니다");
    }
    /**
     * 토큰 위치 정보 강제 업데이트 (null 방지용)
     */
    private void forceUpdateTokenPosition(QueueToken token, Performance performance) {
        if (token.getStatus() == QueueToken.TokenStatus.WAITING) {
            // 현재 위치 계산
            Long position = queueTokenRepository.findPositionInQueue(
                    performance, token.getIssuedAt());

            int queuePosition = Math.max(1, position.intValue() + 1);
            int estimatedMinutes = Math.max(60, queuePosition * 12); // 최소 1분

            token.setPositionInQueue(queuePosition);
            token.setEstimatedWaitTimeMinutes(estimatedMinutes);

        } else if (token.getStatus() == QueueToken.TokenStatus.ACTIVE) {
            token.setPositionInQueue(0);
            token.setEstimatedWaitTimeMinutes(0);
        } else {
            // 기타 상태도 안전한 값 설정
            token.setPositionInQueue(token.getPositionInQueue() != null ? token.getPositionInQueue() : 0);
            token.setEstimatedWaitTimeMinutes(token.getEstimatedWaitTimeMinutes() != null ? token.getEstimatedWaitTimeMinutes() : 0);
        }

        queueTokenRepository.save(token);
    }

    /**
     * 토큰 위치 정보 업데이트 (핵심 메서드)
     */
    private void updateTokenPosition(QueueToken token, Performance performance) {
        if (token.getStatus() == QueueToken.TokenStatus.WAITING) {
            // 현재 토큰보다 앞에 있는 WAITING 토큰 개수 계산
            Long position = queueTokenRepository.findPositionInQueue(
                    performance, token.getIssuedAt());

            // 위치가 0이면 1로 설정 (최소 1번)
            int queuePosition = Math.max(1, position.intValue() + 1);

            // 예상 대기 시간 계산 (1명당 12초)
            int estimatedMinutes = Math.max(60, queuePosition * 12);

            // 토큰에 위치 정보 설정
            token.setPositionInQueue(queuePosition);
            token.setEstimatedWaitTimeMinutes(estimatedMinutes);
        } else if (token.getStatus() == QueueToken.TokenStatus.ACTIVE) {
            // 활성 상태면 위치는 0
            token.setPositionInQueue(0);
            token.setEstimatedWaitTimeMinutes(0);
        }

        queueTokenRepository.save(token);
    }

    /**
     * 모든 대기 중인 토큰의 위치 정보 업데이트
     */
    private void updateWaitingQueuePositions(Performance performance) {
        List<QueueToken> waitingTokens = queueTokenRepository
                .findWaitingTokensByPerformance(performance);

        for (int i = 0; i < waitingTokens.size(); i++) {
            QueueToken token = waitingTokens.get(i);
            int position = i + 1;
            int estimatedMinutes = Math.max(1, (int) Math.ceil(position * 0.2)); // 최소 1분

            token.setPositionInQueue(position);
            token.setEstimatedWaitTimeMinutes(estimatedMinutes);

            log.debug("대기열 위치 업데이트: {} - 순서: {}, 예상대기: {}분",
                    token.getToken(), position, estimatedMinutes);
        }

        if (!waitingTokens.isEmpty()) {
            queueTokenRepository.saveAll(waitingTokens);
        }
    }

    /**
     * 토큰 상태 조회 (null 값 처리 강화)
     */

    @Transactional(readOnly = true)
    public QueueStatusResponse getTokenStatus(String token) {
        QueueToken queueToken = queueTokenRepository.findByToken(token)
                .orElseThrow(() -> new IllegalArgumentException("토큰을 찾을 수 없습니다"));

        if (queueToken.isExpired()) {
            queueToken.markAsExpired();
            queueTokenRepository.save(queueToken);
        } else if (queueToken.getStatus() == QueueToken.TokenStatus.WAITING) {
            // WAITING 상태면 최신 위치 정보로 업데이트
            updateTokenPosition(queueToken, queueToken.getPerformance());
        }

        // ⭐ null 체크 및 기본값 설정 강화
        Integer position = queueToken.getPositionInQueue();
        Integer waitTime = queueToken.getEstimatedWaitTimeMinutes();

        // WAITING 상태일 때 null이면 기본값 설정
        if (queueToken.getStatus() == QueueToken.TokenStatus.WAITING) {
            if (position == null || position <= 0) {
                // 실시간으로 위치 계산
                Long calculatedPosition = queueTokenRepository.findPositionInQueue(
                        queueToken.getPerformance(), queueToken.getIssuedAt());
                position = Math.max(1, calculatedPosition.intValue() + 1);
            }

            if (waitTime == null || waitTime <= 0) {
                // 위치 기반으로 예상 시간 계산
                waitTime = Math.max(60, position * 12); // 최소 1분, 1명당 12초
            }

            // ⭐ 계산된 값을 DB에 저장
            queueToken.setPositionInQueue(position);
            queueToken.setEstimatedWaitTimeMinutes(waitTime);
            queueTokenRepository.save(queueToken);

        } else if (queueToken.getStatus() == QueueToken.TokenStatus.ACTIVE) {
            // ACTIVE 상태는 0으로 설정
            position = 0;
            waitTime = 0;
        } else {
            // 기타 상태도 안전한 기본값
            position = position != null ? position : 1;
            waitTime = waitTime != null ? waitTime : 60;
        }

        return QueueStatusResponse.builder()
                .token(queueToken.getToken())
                .status(queueToken.getStatus())
                .positionInQueue(queueToken.getStatus() == QueueToken.TokenStatus.WAITING ? 5 : 0) // ⭐ 임시 하드코딩
                .estimatedWaitTime(queueToken.getStatus() == QueueToken.TokenStatus.WAITING ? 300 : 0) // ⭐ 임시 5분
                .isActiveForBooking(queueToken.isActiveForBooking())
                .bookingExpiresAt(queueToken.getBookingExpiresAt())
                .build();
    }


    /**
     * 토큰 검증 (예매 시 호출)
     */
    @Transactional //  readOnly = true 제거 (만료 처리 때문에)
    public boolean validateTokenForBooking(String token, Long userId) {
        if (token == null || token.trim().isEmpty()) {
            log.debug("토큰이 null이거나 빈 문자열입니다");
            return false;
        }

        Optional<QueueToken> optionalToken = queueTokenRepository.findByToken(token);
        if (optionalToken.isEmpty()) {
            log.debug("토큰을 찾을 수 없습니다: {}", token);
            return false;
        }

        QueueToken queueToken = optionalToken.get();

        //  토큰 만료 확인 및 처리 (다른 메서드들과 일관성)
        if (queueToken.isExpired()) {
            log.info("만료된 토큰 처리: {}", token);
            queueToken.markAsExpired();
            queueTokenRepository.save(queueToken);

            // 다음 대기자 활성화
            activateNextTokens(queueToken.getPerformance());
            return false;
        }

        // 토큰 소유자 확인
        if (!queueToken.getUser().getUserId().equals(userId)) {
            log.warn("토큰 소유자 불일치 - 토큰: {}, 요청사용자: {}, 토큰소유자: {}",
                    token, userId, queueToken.getUser().getUserId());
            return false;
        }

        //  토큰 상태 확인 (더 상세한 로깅)
        boolean isActive = queueToken.isActiveForBooking();
        if (!isActive) {
            log.debug("토큰이 예매 가능 상태가 아닙니다 - 토큰: {}, 상태: {}, bookingExpiresAt: {}",
                    token, queueToken.getStatus(), queueToken.getBookingExpiresAt());
        }

        return isActive;
    }

    /**
     * 대기열 처리 (스케줄러에서 호출)
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

    /**
     * 토큰 응답 생성 (null 방지)
     */
    private TokenIssueResponse createTokenResponse(QueueToken token, String message) {
        Integer position = token.getPositionInQueue() != null ? token.getPositionInQueue() :
                (token.getStatus() == QueueToken.TokenStatus.WAITING ? 1 : 0);
        Integer waitTime = token.getEstimatedWaitTimeMinutes() != null ? token.getEstimatedWaitTimeMinutes() :
                (token.getStatus() == QueueToken.TokenStatus.WAITING ? 60 : 0);

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

    /**
     * 토큰 문자열로 QueueToken 객체 조회 (BookingService에서 사용)
     */
    @Transactional(readOnly = true)
    public QueueToken getTokenByString(String token) {
        return queueTokenRepository.findByToken(token)
                .orElseThrow(() -> new IllegalArgumentException("토큰을 찾을 수 없습니다: " + token));
    }

    /**
     * 토큰 상태 간단 조회 (로깅용)
     */
    @Transactional(readOnly = true)
    public String getTokenStatusSummary(String token) {
        try {
            QueueToken queueToken = getTokenByString(token);
            return String.format("상태: %s, 만료시간: %s, 예매가능: %s",
                    queueToken.getStatus(),
                    queueToken.getBookingExpiresAt(),
                    queueToken.isActiveForBooking());
        } catch (Exception e) {
            return "토큰 조회 실패: " + e.getMessage();
        }
    }

    /**
     * 토큰 사용 처리 (예매 완료 시 호출) - 로깅 강화
     */
    public void useToken(String token) {
        QueueToken queueToken = queueTokenRepository.findByToken(token)
                .orElseThrow(() -> new IllegalArgumentException("토큰을 찾을 수 없습니다"));

        if (!queueToken.isActiveForBooking()) {
            log.warn("비활성 토큰 사용 시도 - 토큰: {}, 상태: {}", token, queueToken.getStatus());
            throw new IllegalStateException("예매 가능한 상태가 아닙니다");
        }

        queueToken.markAsUsed();
        queueTokenRepository.save(queueToken);

        log.info("토큰 사용 완료 - 토큰: {}, 사용자: {}, 공연: {}",
                token, queueToken.getUser().getUsername(), queueToken.getPerformance().getTitle());

        // 다음 대기자 활성화
        activateNextTokens(queueToken.getPerformance());
    }


}