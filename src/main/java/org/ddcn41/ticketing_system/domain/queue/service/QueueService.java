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

    @Value("${queue.overbooking-ratio:1.0}")
    private double overbookingRatio;


    @Value("${queue.session-timeout-minutes:5}")
    private int sessionTimeoutMinutes;

    @Value("${queue.max-inactive-seconds:120}")
    private int maxInactiveSeconds;


    private final SecureRandom secureRandom = new SecureRandom();


    /**
     * 비활성 세션 정리 (10초마다 실행) - 타임아웃 처리
     */
    @Transactional
    public void cleanupInactiveSessions() {
        try {
            log.debug("비활성 세션 정리 시작");

            LocalDateTime cutoff = LocalDateTime.now().minusSeconds(maxInactiveSeconds);

            // Redis heartbeat 키들 검사
            Set<String> heartbeatKeys = redisTemplate.keys("heartbeat:*");

            if (heartbeatKeys != null && !heartbeatKeys.isEmpty()) {
                log.debug("검사할 heartbeat 키: {}개", heartbeatKeys.size());

                for (String heartbeatKey : heartbeatKeys) {
                    try {
                        String lastHeartbeat = redisTemplate.opsForValue().get(heartbeatKey);

                        if (lastHeartbeat != null) {
                            LocalDateTime lastTime = LocalDateTime.parse(lastHeartbeat);

                            if (lastTime.isBefore(cutoff)) {
                                // 타임아웃된 세션 처리
                                String[] parts = heartbeatKey.split(":");
                                if (parts.length >= 4) {
                                    Long userId = Long.parseLong(parts[1]);
                                    Long performanceId = Long.parseLong(parts[2]);
                                    Long scheduleId = Long.parseLong(parts[3]);

                                    log.warn("세션 타임아웃 감지 - 사용자: {}, 공연: {}, 마지막활동: {}",
                                            userId, performanceId, lastTime);

                                    processSessionTimeout(userId, performanceId, scheduleId);
                                }
                            }
                        } else {
                            // heartbeat 값이 없는 경우도 정리
                            log.debug("빈 heartbeat 키 제거: {}", heartbeatKey);
                            redisTemplate.delete(heartbeatKey);
                        }
                    } catch (Exception e) {
                        log.warn("heartbeat 처리 중 오류: {} - {}", heartbeatKey, e.getMessage());
                    }
                }
            }

            // DB에서 만료된 활성 토큰들도 정리
            cleanupExpiredActiveTokens();

            log.debug("비활성 세션 정리 완료");

        } catch (Exception e) {
            log.error("비활성 세션 정리 중 오류", e);
        }
    }

    /**
     * 세션 타임아웃 처리
     */
    private void processSessionTimeout(Long userId, Long performanceId, Long scheduleId) {
        try {
            // 1. Redis 세션 정리
            String sessionKey = "active_sessions:" + performanceId + ":" + scheduleId;
            String heartbeatKey = "heartbeat:" + userId + ":" + performanceId + ":" + scheduleId;

            Long currentCount = redisTemplate.opsForValue().decrement(sessionKey);
            if (currentCount < 0) {
                redisTemplate.opsForValue().set(sessionKey, "0");
            }
            redisTemplate.delete(heartbeatKey);

            log.info("Redis 세션 정리 - 활성세션: {}, heartbeat 제거완료", currentCount);

            // 2. 사용자 토큰 만료 처리
            Performance performance = performanceRepository.findById(performanceId).orElse(null);
            User user = userRepository.findById(userId).orElse(null);

            if (performance != null && user != null) {
                List<QueueToken> userActiveTokens = queueTokenRepository
                        .findAllActiveTokensByUserAndPerformance(user, performance);

                int expiredCount = 0;
                for (QueueToken token : userActiveTokens) {
                    if (token.getStatus() == QueueToken.TokenStatus.ACTIVE) {
                        token.markAsExpired();
                        expiredCount++;
                        log.info("토큰 만료 처리: {} (사용자: {})",
                                token.getToken(), user.getUsername());
                    }
                }

                if (expiredCount > 0) {
                    queueTokenRepository.saveAll(userActiveTokens);
                    log.info("만료 토큰 저장 완료: {}개", expiredCount);
                }

                // 3. 즉시 다음 대기자 활성화
                activateNextTokens(performance);

                log.info("타임아웃 처리 완료 - 사용자: {}, 공연: {}, 다음 대기자 활성화됨",
                        user.getUsername(), performance.getTitle());
            }

        } catch (Exception e) {
            log.error("세션 타임아웃 처리 중 오류", e);
        }
    }

    /**
     * DB에서 만료된 활성 토큰 정리
     */
    private void cleanupExpiredActiveTokens() {
        try {
            List<QueueToken> expiredTokens = queueTokenRepository.findExpiredTokens(LocalDateTime.now());

            int activeExpiredCount = 0;
            for (QueueToken token : expiredTokens) {
                if (token.getStatus() == QueueToken.TokenStatus.ACTIVE) {
                    token.markAsExpired();
                    activeExpiredCount++;

                    log.info("DB 만료 토큰 처리: {} (만료시간: {})",
                            token.getToken(), token.getBookingExpiresAt());

                    // 해당 공연의 다음 대기자 활성화
                    activateNextTokens(token.getPerformance());
                }
            }

            if (activeExpiredCount > 0) {
                queueTokenRepository.saveAll(expiredTokens);
                log.info("DB 만료 토큰 저장: {}개", activeExpiredCount);
            }

        } catch (Exception e) {
            log.error("DB 만료 토큰 정리 중 오류", e);
        }
    }



    /**
     * 빠른 정리 및 다음 대기자 활성화 (5초마다 실행)
     */
    public void quickCleanupAndActivate() {
        try {
            List<Performance> performances = performanceRepository.findAll();

            for (Performance performance : performances) {
                Long activeCount = queueTokenRepository.countActiveTokensByPerformance(performance);
                int maxConcurrentSessions = getMaxConcurrentSessions();

                log.debug("공연 {} - 활성: {}/{}, 여유있음: {}",
                        performance.getTitle(), activeCount, maxConcurrentSessions,
                        activeCount < maxConcurrentSessions);

                if (activeCount < maxConcurrentSessions) {
                    int beforeActivation = queueTokenRepository.countWaitingTokensByPerformance(performance).intValue();

                    // 대기자 활성화
                    activateNextTokens(performance);

                    int afterActivation = queueTokenRepository.countWaitingTokensByPerformance(performance).intValue();

                    if (beforeActivation > afterActivation) {
                        log.info("대기자 활성화 완료 - 공연: {}, 대기자: {} → {}",
                                performance.getTitle(), beforeActivation, afterActivation);
                    }
                }
            }
        } catch (Exception e) {
            log.error("빠른 정리 작업 중 오류", e);
        }
    }
//    -------------------------------------------------------------

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


                //todo. 디버그용 삭제예정
                System.out.println(" 대기열 확인 - 사용자: " + userId +
                        ", 현재세션: " + activeSessions + "/" + maxConcurrentSessions +
                        ", 대기열필요: " + (activeSessions >= maxConcurrentSessions));

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
        String currentTime = LocalDateTime.now().toString();

        // 🔍 로그 추가
        log.info("startHeartbeatTracking 호출 - 키: {}, 시간: {}, TTL: {}초",
                heartbeatKey, currentTime, maxInactiveSeconds);

        redisTemplate.opsForValue().set(heartbeatKey,
                currentTime,
                Duration.ofSeconds(maxInactiveSeconds));

        // 저장 후 확인
        String saved = redisTemplate.opsForValue().get(heartbeatKey);
        log.info(" Redis 저장 확인 - 키: {}, 저장된 값: {}", heartbeatKey, saved);
    }

    /**
     * Heartbeat 업데이트
     */
    public void updateHeartbeat(Long userId, Long performanceId, Long scheduleId) {
        String heartbeatKey = "heartbeat:" + userId + ":" + performanceId + ":" + scheduleId;
        redisTemplate.opsForValue().set(heartbeatKey,
                LocalDateTime.now().toString(),
                Duration.ofSeconds(maxInactiveSeconds));

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
     * 현재 대기 중인 사용자 수 조회
     */
    private int getCurrentWaitingCount(Long performanceId) {
        Performance performance = performanceRepository.findById(performanceId).orElse(null);
        if (performance == null) return 0;

        return queueTokenRepository.countWaitingTokensByPerformance(performance).intValue();
    }

    /**
     * 다음 대기자들을 활성화
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
     * 대기열 토큰 발급 (중복 토큰 처리 강화)
     */
    public TokenIssueResponse issueQueueToken(Long userId, Long performanceId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다"));

        Performance performance = performanceRepository.findById(performanceId)
                .orElseThrow(() -> new IllegalArgumentException("공연을 찾을 수 없습니다"));

        // 기존 활성 토큰 확인
        Optional<QueueToken> existingToken = queueTokenRepository
                .findActiveTokenByUserAndPerformance(user, performance);

        if (existingToken.isPresent()) {
            QueueToken token = existingToken.get();
            if (!token.isExpired()) {
                updateQueuePosition(token);
                return createTokenResponse(token, "기존 토큰을 반환합니다.");
            } else {
                // 만료된 토큰은 상태 업데이트
                token.markAsExpired();
                queueTokenRepository.save(token);
            }
        }
        // 현재 활성 세션 수 확인 후 즉시 활성화 가능한지 체크
        Long waitingCount = queueTokenRepository.countWaitingTokensByPerformance(performance);
        Long currentActiveSessions = queueTokenRepository.countActiveTokensByPerformance(performance);
        int maxConcurrentSessions = getMaxConcurrentSessions();


        // 새 토큰 생성 (기본적으로 WAITING 상태)
        String tokenString = generateToken();

        QueueToken newToken = QueueToken.builder()
                .token(tokenString)
                .user(user)
                .performance(performance)
                .status(QueueToken.TokenStatus.WAITING) // 기본은 WAITING
                .issuedAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusHours(2))
                .build();

        // DB에 저장
        QueueToken savedToken = queueTokenRepository.save(newToken);
        // 대기열 위치 계산
        updateQueuePosition(savedToken);

        String message;

        if (waitingCount == 0 && currentActiveSessions < maxConcurrentSessions) {
            // 대기자가 없고 여유가 있으면 즉시 활성화
            savedToken.activate();
            savedToken.setPositionInQueue(0);
            savedToken.setEstimatedWaitTimeMinutes(0);
            savedToken = queueTokenRepository.save(savedToken);
            message = "예매 세션이 활성화되었습니다.";
            log.info("대기자 없음 - 토큰 즉시 활성화: {}", savedToken.getToken());
        } else {
            // 대기자가 있거나 여유가 없으면 대기열에 추가
            message = "대기열에 추가되었습니다. 순서를 기다려주세요.";
            log.info("대기열 추가: {} (현재 대기자: {}, 활성 세션: {}/{})",
                    savedToken.getToken(), waitingCount + 1, currentActiveSessions, maxConcurrentSessions);
        }

        return createTokenResponse(savedToken, message);
    }


    /**
     * 토큰 위치 정보 업데이트 (핵심 메서드)
     */
    private void updateTokenPosition(QueueToken token, Performance performance) {
        if (token.getStatus() == QueueToken.TokenStatus.WAITING) {
            // 현재 토큰보다 먼저 발급된 WAITING 토큰 개수로 위치 계산
            Long position = queueTokenRepository.findPositionInQueue(
                    performance, token.getIssuedAt());

            int queuePosition = Math.max(1, position.intValue() + 1);
            int estimatedMinutes = Math.max(60, queuePosition * 12);

            token.setPositionInQueue(queuePosition);
            token.setEstimatedWaitTimeMinutes(estimatedMinutes);

            log.debug("개별 토큰 위치 업데이트: {} - 위치: {}, 발급시간: {}",
                    token.getToken(), queuePosition, token.getIssuedAt());
        } else if (token.getStatus() == QueueToken.TokenStatus.ACTIVE) {
            token.setPositionInQueue(0);
            token.setEstimatedWaitTimeMinutes(0);
        }

        queueTokenRepository.save(token);
    }

    /**
     * 대기 중인 토큰들의 위치 정보 업데이트 (시간 순서 보장)
     */
    private void updateWaitingQueuePositions(Performance performance) {
        //  발급 시간 순서로 정렬하여 조회
        List<QueueToken> waitingTokens = queueTokenRepository
                .findWaitingTokensByPerformanceOrderByIssuedAt(performance);

        for (int i = 0; i < waitingTokens.size(); i++) {
            QueueToken token = waitingTokens.get(i);
            int position = i + 1; // 발급 시간 순서대로 1, 2, 3...
            int estimatedMinutes = Math.max(1, (int) Math.ceil(position * 0.2));

            token.setPositionInQueue(position);
            token.setEstimatedWaitTimeMinutes(estimatedMinutes);

            log.debug("대기열 위치 업데이트: {} - 순서: {}, 예상대기: {}분, 발급시간: {}",
                    token.getToken(), position, estimatedMinutes, token.getIssuedAt());
        }

        if (!waitingTokens.isEmpty()) {
            queueTokenRepository.saveAll(waitingTokens);
            log.info("대기열 위치 재정렬 완료 - 공연: {}, 대기자: {}명",
                    performance.getTitle(), waitingTokens.size());
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
                .positionInQueue(position)  // 실제 값 사용
                .estimatedWaitTime(waitTime)  // 실제 값 사용
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
     * 대기열 처리 (스케줄러에서 호출) 30초 주기
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