package org.ddcn41.ticketing_system.domain.queue.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
@Transactional
public class QueueService {

    private final QueueTokenRepository queueTokenRepository;
    private final PerformanceRepository performanceRepository;
    private final UserRepository userRepository;

    @Value("${queue.max-active-tokens:100}")
    private int maxActiveTokens;

    @Value("${queue.token-valid-hours:24}")
    private int tokenValidHours;

    @Value("${queue.booking-time-minutes:10}")
    private int bookingTimeMinutes;

    private final SecureRandom secureRandom = new SecureRandom();

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