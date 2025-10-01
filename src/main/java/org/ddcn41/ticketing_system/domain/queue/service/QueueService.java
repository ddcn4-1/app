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
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

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

    // 동시성 제어용 락
    private final Object queueLock = new Object();

    private static final String SESSION_KEY_PREFIX = "active_sessions:";
    private static final String HEARTBEAT_KEY_PREFIX = "heartbeat:";
    private static final String ACTIVE_TOKENS_KEY_PREFIX = "active_tokens:"; // 새로 추가

    /**
     * 대기열 생성 시 직접 입장 세션 추적용
     */

    public QueueCheckResponse getBookingToken(Long performanceId, Long scheduleId, Long userId) {
        String activeTokensKey = ACTIVE_TOKENS_KEY_PREFIX + performanceId;

        synchronized (queueLock) {
            try {
                // 1 사용자 및 공연 정보 조회
                User user = userRepository.findById(userId)
                        .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다"));

                Performance performance = performanceRepository.findById(performanceId)
                        .orElseThrow(() -> new IllegalArgumentException("공연을 찾을 수 없습니다"));

                // 2 기존 활성 토큰 확인
                Optional<QueueToken> existingToken = queueTokenRepository
                        .findActiveTokenByUserAndPerformance(user, performance);

                if (existingToken.isPresent()) {
                    QueueToken token = existingToken.get();
                    if (!token.isExpired()) {
                        // 기존 토큰이 유효하면 재사용
                        return buildQueueCheckResponse(token, performanceId, scheduleId);
                    } else {
                        // 만료된 토큰은 정리
                        token.markAsExpired();
                        queueTokenRepository.save(token);
                        if (token.getStatus() == QueueToken.TokenStatus.ACTIVE) {
                            releaseTokenFromRedis(performanceId);
                        }
                    }
                }

                // 3 현재 활성 토큰 수 확인
                String activeTokensStr = redisTemplate.opsForValue().get(activeTokensKey);
                int activeTokens = activeTokensStr != null ? Integer.parseInt(activeTokensStr) : 0;

                // 4 토큰 생성 및 상태 결정
                String tokenString = generateToken();
                QueueToken newToken;

                if (activeTokens < maxActiveTokens) { //  직접 입장 - ACTIVE 토큰 생성
                    newToken = createActiveToken(tokenString, user, performance);

                    // Redis 카운터 증가
                    redisTemplate.opsForValue().increment(activeTokensKey);
                    redisTemplate.expire(activeTokensKey, Duration.ofMinutes(10));

                    // Heartbeat 시작
                    startHeartbeat(userId, performanceId, scheduleId);

                    log.info("직접 입장 - ACTIVE 토큰 생성: {}", tokenString);

                    return QueueCheckResponse.builder()
                            .requiresQueue(false)
                            .canProceedDirectly(true)
                            .sessionId(tokenString)
                            .message("좌석 선택으로 이동합니다")
                            .currentActiveSessions(activeTokens + 1)
                            .maxConcurrentSessions(maxActiveTokens)
                            .build();

                } else {
                    // 대기열 진입 - WAITING 토큰 생성
                    newToken = createWaitingToken(tokenString, user, performance);

                    // 대기 순번 계산
                    updateQueuePosition(newToken);

                    int waitingCount = getRedisWaitingCount(performanceId);
                    int estimatedWait = newToken.getPositionInQueue() * waitTimePerPerson;

                    log.info("대기열 진입 - WAITING 토큰 생성: {} (순번: {})",
                            tokenString, newToken.getPositionInQueue());

                    return QueueCheckResponse.builder()
                            .requiresQueue(true)
                            .canProceedDirectly(false)
                            .sessionId(tokenString)
                            .message("현재 많은 사용자가 접속중입니다. 대기열에 참여합니다.")
                            .currentActiveSessions(activeTokens)
                            .maxConcurrentSessions(maxActiveTokens)
                            .estimatedWaitTime(estimatedWait)
                            .currentWaitingCount(waitingCount)
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
    }

    //  ACTIVE 토큰 생성 헬퍼 메서드
    private QueueToken createActiveToken(String tokenString, User user, Performance performance) {
        QueueToken token = QueueToken.builder()
                .token(tokenString)
                .user(user)
                .performance(performance)
                .status(QueueToken.TokenStatus.ACTIVE)
                .issuedAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusHours(1))
                .positionInQueue(0)
                .estimatedWaitTimeMinutes(0)
                .build();

        token.activate(); // bookingExpiresAt 설정
        return queueTokenRepository.save(token);
    }

    //  WAITING 토큰 생성 헬퍼 메서드
    private QueueToken createWaitingToken(String tokenString, User user, Performance performance) {
        QueueToken token = QueueToken.builder()
                .token(tokenString)
                .user(user)
                .performance(performance)
                .status(QueueToken.TokenStatus.WAITING)
                .issuedAt(LocalDateTime.now())
                .expiresAt(LocalDateTime.now().plusHours(2))
                .positionInQueue(1) // 초기값, updateQueuePosition에서 재계산
                .estimatedWaitTimeMinutes(waitTimePerPerson / 60)
                .build();

        return queueTokenRepository.save(token);
    }

    // 기존 토큰으로 응답 생성
    private QueueCheckResponse buildQueueCheckResponse(QueueToken token, Long performanceId, Long scheduleId) {
        if (token.getStatus() == QueueToken.TokenStatus.ACTIVE) {
            // ACTIVE 토큰 - 직접 입장 가능
            String activeTokensKey = ACTIVE_TOKENS_KEY_PREFIX + performanceId;
            String activeTokensStr = redisTemplate.opsForValue().get(activeTokensKey);
            int activeTokens = activeTokensStr != null ? Integer.parseInt(activeTokensStr) : 0;

            return QueueCheckResponse.builder()
                    .requiresQueue(false)
                    .canProceedDirectly(true)
                    .sessionId(token.getToken())
                    .message("이미 활성화된 토큰이 있습니다")
                    .currentActiveSessions(activeTokens)
                    .maxConcurrentSessions(maxActiveTokens)
                    .build();

        } else {
            // WAITING 토큰 - 대기 중
            updateQueuePosition(token);
            int estimatedWait = token.getPositionInQueue() * waitTimePerPerson;

            return QueueCheckResponse.builder()
                    .requiresQueue(true)
                    .canProceedDirectly(false)
                    .sessionId(token.getToken())
                    .message("대기열에서 대기 중입니다")
                    .estimatedWaitTime(estimatedWait)
                    .currentWaitingCount(token.getPositionInQueue())
                    .build();
        }
    }

    /**
     * 대기열 토큰 발급 - Redis 기반 todo. test 진행 후 삭제 예정
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

        if (currentActive < maxActiveTokens) { //즉시 입장
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
     * 토큰 상태 조회
     */
    public QueueStatusResponse activateToken(String token, Long userId, Long performanceId, Long scheduleId) {
        QueueToken queueToken = queueTokenRepository.findByToken(token)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "토큰을 찾을 수 없습니다"));

        if (!queueToken.getUser().getUserId().equals(userId)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "토큰을 찾을 수 없습니다");
        }

        if (!queueToken.getPerformance().getPerformanceId().equals(performanceId)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "요청한 공연 정보와 토큰이 일치하지 않습니다");
        }

        if (queueToken.getStatus() == QueueToken.TokenStatus.CANCELLED ||
                queueToken.getStatus() == QueueToken.TokenStatus.USED) {
            throw new ResponseStatusException(HttpStatus.GONE, "토큰이 만료되었거나 취소되었습니다");
        }

        if (queueToken.getStatus() == QueueToken.TokenStatus.ACTIVE) {
            if (queueToken.isExpired()) {
                queueToken.markAsExpired();
                queueTokenRepository.save(queueToken);
                releaseTokenFromRedis(performanceId);
                activateNextTokens(queueToken.getPerformance());
                throw new ResponseStatusException(HttpStatus.GONE, "토큰이 만료되었습니다");
            }
            return buildQueueStatusResponse(queueToken);
        }

        if (queueToken.isExpired()) {
            queueToken.markAsExpired();
            queueTokenRepository.save(queueToken);
            updateWaitingPositions(queueToken.getPerformance());
            throw new ResponseStatusException(HttpStatus.GONE, "토큰이 만료되었습니다");
        }

        if (queueToken.getStatus() != QueueToken.TokenStatus.WAITING) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "대기 중인 토큰만 활성화할 수 있습니다");
        }

        Long position = queueTokenRepository.findPositionInQueue(
                queueToken.getPerformance(), queueToken.getIssuedAt()) + 1;

        int estimatedSeconds = position.intValue() * waitTimePerPerson;
        int estimatedMinutes = Math.max(1, estimatedSeconds / 60);
        queueToken.setPositionInQueue(position.intValue());
        queueToken.setEstimatedWaitTimeMinutes(estimatedMinutes);

        String activeTokensKey = ACTIVE_TOKENS_KEY_PREFIX + performanceId;

        synchronized (queueLock) {
            // 1) 락 안에서 "현재" 순번 재계산 (진짜 1등인지 확인)
            Long currentPosition = queueTokenRepository.findPositionInQueue(
                    queueToken.getPerformance(), queueToken.getIssuedAt()
            ) + 1;

            // 2) 맨 앞이 아니면 거절 (FIFO 보장)
            if (currentPosition > 1) {
                queueTokenRepository.save(queueToken);
                throw new ResponseStatusException(
                        HttpStatus.CONFLICT,
                        "아직 차례가 아닙니다. 현재 대기번호: " + currentPosition
                );
            }
            // 3) 활성 슬롯 확인(동기화 포함)
            // ID로 조회
            Long dbActiveCount = queueTokenRepository.countActiveTokensByPerformanceId(performanceId);
            String redisCountStr = redisTemplate.opsForValue().get(activeTokensKey);
            int redisActiveCount = redisCountStr != null ? Integer.parseInt(redisCountStr) : 0;

            // Redis-DB 동기화 (양방향)
            if (redisActiveCount != dbActiveCount.intValue()) {
                log.warn("Redis-DB 불일치 감지. Redis: {}, DB: {}. DB 기준으로 동기화...",
                        redisActiveCount, dbActiveCount);

                // DB 값을 신뢰할 수 있는 source of truth로 사용
                redisTemplate.opsForValue().set(activeTokensKey, dbActiveCount.toString());
                redisTemplate.expire(activeTokensKey, Duration.ofMinutes(10));
                redisActiveCount = dbActiveCount.intValue();
                log.info("동기화 완료. 현재 활성 토큰: {}", redisActiveCount);
            }

            if (redisActiveCount >= maxActiveTokens) {
                throw new ResponseStatusException(HttpStatus.CONFLICT,
                        "현재 입장 가능한 인원이 가득 찼습니다");
            }

            Long newCount = redisTemplate.opsForValue().increment(activeTokensKey);
            redisTemplate.expire(activeTokensKey, Duration.ofMinutes(10));

            try {
                queueToken.activate();
                queueTokenRepository.save(queueToken);
                startHeartbeat(userId, performanceId, scheduleId);
                updateWaitingPositions(queueToken.getPerformance());  // 이미 초기화됨

            } catch (RuntimeException ex) {
                redisTemplate.opsForValue().decrement(activeTokensKey);
                throw ex;
            }
        }

        return buildQueueStatusResponse(queueToken);
    }

    /**
     * 토큰 검증 - 사용자 ID와 공연 ID 모두 검증
     */
    @Deprecated
    @Transactional
    public boolean validateTokenForBooking(String token, Long userId) {
        log.warn("Deprecated method called - 공연 ID 없는 구버전 호출");

        try {
            QueueToken queueToken = queueTokenRepository.findByToken(token).orElse(null);
            if (queueToken == null) return false;

            // 새로운 3-parameter 메서드 호출
            return validateTokenForBooking(token, userId, queueToken.getPerformance().getPerformanceId());
        } catch (Exception e) {
            log.error("토큰 검증 중 오류", e);
            return false;
        }
    }

    @Transactional
    public boolean validateTokenForBooking(String token, Long userId, Long performanceId) {
        if (token == null || token.trim().isEmpty()) {
            return false;
        }

        Optional<QueueToken> optionalToken = queueTokenRepository.findByToken(token);
        if (optionalToken.isEmpty()) {
            log.warn("토큰을 찾을 수 없음: {}", token);
            return false;
        }

        QueueToken queueToken = optionalToken.get();

        // 토큰 만료 확인
        if (queueToken.isExpired()) {
            queueToken.markAsExpired();
            queueTokenRepository.save(queueToken);

            if (queueToken.getStatus() == QueueToken.TokenStatus.ACTIVE) {
                releaseTokenFromRedis(queueToken.getPerformance().getPerformanceId());
                activateNextTokens(queueToken.getPerformance());
            }

            log.warn("만료된 토큰: {}", token);
            return false;
        }

        // 사용자 ID 검증
        if (!queueToken.getUser().getUserId().equals(userId)) {
            log.warn("토큰 소유자 불일치 - 토큰: {}, 요청 사용자: {}, 토큰 소유자: {}",
                    token, userId, queueToken.getUser().getUserId());
            return false;
        }

        // 공연 ID 검증
        if (!queueToken.getPerformance().getPerformanceId().equals(performanceId)) {
            log.warn("토큰-공연 불일치 - 토큰 공연: {}, 요청 공연: {}",
                    queueToken.getPerformance().getPerformanceId(), performanceId);
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
     * 세션 해제 (synchronized 버전)
     */
    @Transactional
    public void releaseSession(Long userId, Long performanceId, Long scheduleId) {
        String heartbeatKey = HEARTBEAT_KEY_PREFIX + userId + ":" + performanceId + ":" + scheduleId;
        String activeTokensKey = ACTIVE_TOKENS_KEY_PREFIX + performanceId;

        log.info("=== 세션 해제 시작: 사용자={}, 공연={} ===", userId, performanceId);

        synchronized (queueLock) { //  동시성 제어

            // 1. Heartbeat 삭제
            boolean heartbeatExisted = Boolean.TRUE.equals(redisTemplate.delete(heartbeatKey));

            // 2. DB 토큰 만료 처리
            User user = userRepository.findById(userId).orElse(null);
            Performance performance = performanceRepository.findById(performanceId).orElse(null);

            if (user != null && performance != null) {
                Optional<QueueToken> activeToken = queueTokenRepository
                        .findActiveTokenByUserAndPerformance(user, performance);

                if (activeToken.isPresent() &&
                        activeToken.get().getStatus() == QueueToken.TokenStatus.ACTIVE) {

                    QueueToken token = activeToken.get();
                    token.markAsExpired();
                    queueTokenRepository.save(token);

                    log.info(">>> DB 토큰 만료: {}", token.getToken());
                }
            }

            // 3. Redis 카운터 감소 (heartbeat가 있었거나 토큰이 ACTIVE였으면)
            if (heartbeatExisted) {
                String countStr = redisTemplate.opsForValue().get(activeTokensKey);
                int currentCount = (countStr != null) ? Integer.parseInt(countStr) : 0;

                if (currentCount > 0) {
                    redisTemplate.opsForValue().decrement(activeTokensKey);
                    log.info(">>> Redis 카운터 감소: {} -> {}", currentCount, currentCount - 1);
                }
            }

            // 4. 다음 대기자 활성화 (락 안에서 실행)
            if (performance != null) {
                activateNextTokensInternal(performance, activeTokensKey);
            }

        } //  락 해제

        log.info(">>> 세션 해제 완료");
    }

    /**
     * 내부용 - 락이 이미 걸려있다고 가정
     * releaseSession() 안에서만 호출
     */
    private void activateNextTokensInternal(Performance performance, String activeTokensKey) {
        // 현재 활성 토큰 수 확인
        String activeStr = redisTemplate.opsForValue().get(activeTokensKey);
        int currentActive = (activeStr != null) ? Integer.parseInt(activeStr) : 0;

        log.info("=== 다음 대기자 활성화: 공연={}, 현재={}/{} ===",
                performance.getTitle(), currentActive, maxActiveTokens);

        if (currentActive < maxActiveTokens) {
            int slotsAvailable = maxActiveTokens - currentActive;

            // WAITING 토큰 조회 (FIFO)
            List<QueueToken> waitingTokens = queueTokenRepository
                    .findWaitingTokensByPerformanceOrderByIssuedAt(performance)
                    .stream()
                    .limit(slotsAvailable)
                    .collect(Collectors.toList());

            for (QueueToken token : waitingTokens) {
                // Redis 카운터 증가
                redisTemplate.opsForValue().increment(activeTokensKey);
                redisTemplate.expire(activeTokensKey, Duration.ofMinutes(10));

                // DB 토큰 활성화
                token.activate();
                log.info(">>> 토큰 활성화: {}", token.getToken());
            }

            if (!waitingTokens.isEmpty()) {
                queueTokenRepository.saveAll(waitingTokens);
                updateWaitingPositions(performance);
            }
        }
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
     * 다음 대기자 활성화
     */
    @Transactional
    public void activateNextTokens(Performance performance) {
        String activeTokensKey = ACTIVE_TOKENS_KEY_PREFIX + performance.getPerformanceId();

        synchronized (queueLock) { // 동시성 제어
            activateNextTokensInternal(performance, activeTokensKey);
        }
    }

    /**
     * 토큰이 예매 가능한 상태인지 확인 (BookingService에서 사용)
     */
    @Transactional(readOnly = true)
    public boolean isTokenActiveForBooking(String token) {
        try {
            QueueToken queueToken = queueTokenRepository.findByToken(token)
                    .orElse(null);

            if (queueToken == null) {
                return false;
            }

            return queueToken.isActiveForBooking();
        } catch (Exception e) {
            log.warn("토큰 상태 확인 중 오류 발생: {}", e.getMessage());
            return false;
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

    /**
     * Heartbeat 시작
     */
    private void startHeartbeat(Long userId, Long performanceId, Long scheduleId) {
        String heartbeatKey = HEARTBEAT_KEY_PREFIX + userId + ":" + performanceId + ":" + scheduleId;
        redisTemplate.opsForValue().set(heartbeatKey, LocalDateTime.now().toString(),
                Duration.ofSeconds(maxInactiveSeconds));
        log.info("Heartbeat 시작: {}", heartbeatKey);
    }
    /**
     * Heartbeat 갱신
     */
    public void updateHeartbeat(Long userId, Long performanceId, Long scheduleId) {
        String heartbeatKey = HEARTBEAT_KEY_PREFIX + userId + ":" + performanceId + ":" + scheduleId;
        redisTemplate.opsForValue().set(heartbeatKey, LocalDateTime.now().toString(),
                Duration.ofSeconds(maxInactiveSeconds));
    }

    /**
     * 비활성 세션 정리 및 만료 토큰 처리
     */
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
                            // processTimeout에서 releaseSession을 호출하면
                            // 그 안에서 heartbeat를 제거하므로 중복 제거 방지됨
                            processTimeout(heartbeatKey);
                        }
                    }
                }
            }

            // 직접 세션 정리 (heartbeat 없이 남아있는 경우)
            Set<String> directSessionKeys = redisTemplate.keys(ACTIVE_TOKENS_KEY_PREFIX + "*");
            if (directSessionKeys != null) {
                for (String directKey : directSessionKeys) {
                    String[] parts = directKey.replace(ACTIVE_TOKENS_KEY_PREFIX, "").split(":");
                    if (parts.length >= 3) {
                        String correspondingHeartbeat = HEARTBEAT_KEY_PREFIX + parts[0] + ":" + parts[1] + ":" + parts[2];

                        // 해당 heartbeat가 없다면 고아 직접 세션이므로 정리
                        if (!redisTemplate.hasKey(correspondingHeartbeat)) {
                            redisTemplate.delete(directKey);
                            try {
                                Long performanceId = Long.parseLong(parts[1]);
                                releaseTokenFromRedis(performanceId);
                                log.info(">>> 고아 직접 세션 정리: {}", directKey);
                            } catch (NumberFormatException e) {
                                log.warn("직접 세션 키 파싱 실패: {}", directKey);
                            }
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
                .findWaitingTokensByPerformanceOrderByIssuedAt(performance);

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
    private QueueStatusResponse buildQueueStatusResponse(QueueToken token) {
        Integer position = token.getPositionInQueue() != null ? token.getPositionInQueue() :
                (token.getStatus() == QueueToken.TokenStatus.WAITING ? 1 : 0);
        Integer waitTime = token.getEstimatedWaitTimeMinutes() != null ?
                token.getEstimatedWaitTimeMinutes() :
                (token.getStatus() == QueueToken.TokenStatus.WAITING ? Math.max(1, waitTimePerPerson / 60) : 0);

        return QueueStatusResponse.builder()
                .token(token.getToken())
                .status(token.getStatus())
                .positionInQueue(position)
                .estimatedWaitTime(waitTime)
                .isActiveForBooking(token.isActiveForBooking())
                .bookingExpiresAt(token.getBookingExpiresAt())
                .performanceTitle(token.getPerformance() != null ? token.getPerformance().getTitle() : null)
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

        // 중요: 상태를 변경하기 전에 원래 상태 확인
        QueueToken.TokenStatus originalStatus = queueToken.getStatus();
        boolean wasActive = (originalStatus == QueueToken.TokenStatus.ACTIVE);

        // 토큰 상태를 CANCELLED로 변경
        queueToken.setStatus(QueueToken.TokenStatus.CANCELLED);
        queueTokenRepository.save(queueToken);

        log.info("토큰 취소: {} (원래 상태: {})", token, originalStatus);

        // 원래 활성 상태였다면 Redis 카운터 감소
        if (wasActive) {
            releaseTokenFromRedis(queueToken.getPerformance().getPerformanceId());
            log.info(">>> 활성 토큰 취소로 Redis 카운터 감소");
        }

        // 다음 대기자 활성화
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