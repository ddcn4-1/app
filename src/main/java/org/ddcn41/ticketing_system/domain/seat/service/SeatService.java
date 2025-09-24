package org.ddcn41.ticketing_system.domain.seat.service;

import lombok.RequiredArgsConstructor;
import org.ddcn41.ticketing_system.domain.performance.repository.PerformanceScheduleRepository;
import org.ddcn41.ticketing_system.domain.seat.entity.ScheduleSeat;
import org.ddcn41.ticketing_system.domain.seat.entity.SeatLock;
import org.ddcn41.ticketing_system.domain.seat.repository.ScheduleSeatRepository;
import org.ddcn41.ticketing_system.domain.seat.repository.SeatLockRepository;
import org.ddcn41.ticketing_system.domain.user.entity.User;
import org.ddcn41.ticketing_system.domain.user.repository.UserRepository;
import org.ddcn41.ticketing_system.domain.seat.dto.SeatDto;
import org.ddcn41.ticketing_system.domain.seat.dto.response.SeatAvailabilityResponse;
import org.ddcn41.ticketing_system.domain.seat.dto.response.SeatLockResponse;

import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 좌석 상태 관리 전담 서비스 (SSOT)
 * - 좌석 가용성 확인
 * - 좌석 락/언락
 * - 좌석 예약 확정
 * - 좌석 상태 조회
 */
@Service
@RequiredArgsConstructor
@Transactional
public class SeatService {

    private final ScheduleSeatRepository scheduleSeatRepository;
    private final SeatLockRepository seatLockRepository;
    private final PerformanceScheduleRepository scheduleRepository;
    private final UserRepository userRepository;
    private final RedisTemplate<String, String> redisTemplate;

    private static final int LOCK_DURATION_MINUTES = 1;
    private static final String REDIS_LOCK_PREFIX = "seat_lock:";

    /**
     * 스케줄의 모든 좌석 상태 조회
     */
    @Transactional(readOnly = true)
    public SeatAvailabilityResponse getSeatsAvailability(Long scheduleId) {
        List<ScheduleSeat> seats = scheduleSeatRepository.findBySchedule_ScheduleId(scheduleId);

        List<SeatDto> seatDtos = seats.stream()
                .map(this::convertToSeatDto)
                .collect(Collectors.toList());

        long availableCount = seats.stream()
                .filter(seat -> seat.getStatus() == ScheduleSeat.SeatStatus.AVAILABLE)
                .count();

        return SeatAvailabilityResponse.builder()
                .scheduleId(scheduleId)
                .totalSeats(seats.size())
                .availableSeats((int) availableCount)
                .seats(seatDtos)
                .build();
    }

    /**
     * 특정 좌석들의 가용성 확인
     */
    @Transactional(readOnly = true)
    public boolean areSeatsAvailable(List<Long> seatIds) {
        List<ScheduleSeat> seats = scheduleSeatRepository.findAllById(seatIds);

        if (seats.size() != seatIds.size()) {
            return false; // 일부 좌석이 존재하지 않음
        }

        return seats.stream()
                .allMatch(seat -> seat.getStatus() == ScheduleSeat.SeatStatus.AVAILABLE);
    }

    /**
     * 좌석 락 시도
     */
    public SeatLockResponse lockSeats(List<Long> seatIds, Long userId, String sessionId) {
        // 1. 만료된 락 정리
        cleanupExpiredLocks();

        // 2. 사용자 정보 조회
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다: " + userId));

        // 3. 좌석 존재 및 가용성 확인
        List<ScheduleSeat> seats = scheduleSeatRepository.findAllById(seatIds);
        if (seats.size() != seatIds.size()) {
            return SeatLockResponse.failure("일부 좌석을 찾을 수 없습니다");
        }

        // 4. 모든 좌석이 사용 가능한지 확인
        for (ScheduleSeat seat : seats) {
            if (seat.getStatus() == ScheduleSeat.SeatStatus.BOOKED) {
                return SeatLockResponse.failure("이미 예약된 좌석이 포함되어 있습니다: " + seat.getSeatId());
            }

            if (seat.getStatus() == ScheduleSeat.SeatStatus.LOCKED) {
                // 같은 사용자/세션이면 연장, 아니면 실패
                Optional<SeatLock> existingLock = seatLockRepository
                        .findBySeatAndStatusAndExpiresAtAfter(seat, SeatLock.LockStatus.ACTIVE, LocalDateTime.now());

                if (existingLock.isPresent() && !isSameUserOrSession(existingLock.get(), user, sessionId)) {
                    return SeatLockResponse.failure("다른 사용자가 선택 중인 좌석입니다: " + seat.getSeatId());
                }
            }
        }

        // 5. Redis 분산 락으로 동시성 제어
        List<String> lockKeys = seatIds.stream()
                .map(id -> REDIS_LOCK_PREFIX + id)
                .collect(Collectors.toList());

        String lockValue = userId + ":" + sessionId;

        try {
            // 모든 좌석에 대해 Redis 락 획득 시도
            for (String lockKey : lockKeys) {
                Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                        lockKey, lockValue, LOCK_DURATION_MINUTES, TimeUnit.MINUTES
                );

                if (Boolean.FALSE.equals(acquired)) {
                    // 실패 시 이미 획득한 락들 해제
                    rollbackRedisLocks(lockKeys, lockValue);
                    return SeatLockResponse.failure("좌석 락 획득 실패");
                }
            }

            // 6. DB에 락 정보 저장 및 좌석 상태 변경
            LocalDateTime expiresAt = LocalDateTime.now().plusMinutes(LOCK_DURATION_MINUTES);

            int newlyLocked = 0;
            Long scheduleIdForCounter = null;
            for (ScheduleSeat seat : seats) {
                // 기존 락이 있다면 연장, 없다면 새로 생성
                Optional<SeatLock> existingLock = seatLockRepository
                        .findBySeatAndStatusAndExpiresAtAfter(seat, SeatLock.LockStatus.ACTIVE, LocalDateTime.now());

                if (existingLock.isPresent() && isSameUserOrSession(existingLock.get(), user, sessionId)) {
                    // 락 연장
                    SeatLock lock = existingLock.get();
                    lock.setExpiresAt(expiresAt);
                    seatLockRepository.save(lock);
                } else {
                    // 새 락 생성
                    SeatLock newLock = SeatLock.builder()
                            .seat(seat)
                            .user(user)
                            .sessionId(sessionId)
                            .expiresAt(expiresAt)
                            .status(SeatLock.LockStatus.ACTIVE)
                            .build();
                    seatLockRepository.save(newLock);
                }

                // 좌석 상태 변경 및 카운터 감소 대상 계산
                if (seat.getStatus() == ScheduleSeat.SeatStatus.AVAILABLE) {
                    newlyLocked++;
                }
                seat.setStatus(ScheduleSeat.SeatStatus.LOCKED);
                scheduleSeatRepository.save(seat);

                if (scheduleIdForCounter == null && seat.getSchedule() != null) {
                    scheduleIdForCounter = seat.getSchedule().getScheduleId();
                }
            }

            // 7. 스케줄 가용 좌석 카운터 감소 (AVAILABLE -> LOCKED 전이 수만큼)
            if (scheduleIdForCounter != null && newlyLocked > 0) {
                int affected = scheduleRepository.decrementAvailableSeats(scheduleIdForCounter, newlyLocked);
                if (affected == 0) {
                    throw new RuntimeException("잔여 좌석 수 갱신 실패: 이미 매진된 스케줄입니다.");
                }
                scheduleRepository.refreshScheduleStatus(scheduleIdForCounter);
            }

            return SeatLockResponse.success("좌석 락 성공", expiresAt);

        } catch (Exception e) {
            // 실패 시 Redis 락 정리
            rollbackRedisLocks(lockKeys, lockValue);
            throw new RuntimeException("좌석 락 처리 중 오류 발생", e);
        }
    }

    /**
     * 좌석 락 해제
     */
    public boolean releaseSeats(List<Long> seatIds, Long userId, String sessionId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다: " + userId));

        boolean allReleased = true;

        for (Long seatId : seatIds) {
            Optional<SeatLock> lockOpt = seatLockRepository
                    .findBySeatSeatIdAndStatus(seatId, SeatLock.LockStatus.ACTIVE);

            if (lockOpt.isPresent()) {
                SeatLock lock = lockOpt.get();

                // 권한 확인 (본인 또는 관리자)
                if (isSameUserOrSession(lock, user, sessionId) || user.getRole() == User.Role.ADMIN) {
                    releaseSingleSeat(lock);
                } else {
                    allReleased = false;
                }
            }
        }

        return allReleased;
    }

    /**
     * 좌석 예약 확정 (결제 완료 후 호출)
     */
    public boolean confirmSeats(List<Long> seatIds, Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다: " + userId));

        List<ScheduleSeat> seats = scheduleSeatRepository.findAllById(seatIds);

        for (ScheduleSeat seat : seats) {
            // 해당 사용자의 락이 있는지 확인
            Optional<SeatLock> lockOpt = seatLockRepository
                    .findBySeatSeatIdAndUserAndStatus(seat.getSeatId(), user, SeatLock.LockStatus.ACTIVE);

            if (lockOpt.isPresent()) {
                // 좌석 상태를 예약됨으로 변경
                seat.setStatus(ScheduleSeat.SeatStatus.BOOKED);
                scheduleSeatRepository.save(seat);

                // 락 해제
                SeatLock lock = lockOpt.get();
                lock.setStatus(SeatLock.LockStatus.RELEASED);
                seatLockRepository.save(lock);

                // Redis에서도 제거
                String lockKey = REDIS_LOCK_PREFIX + seat.getSeatId();
                redisTemplate.delete(lockKey);
            } else {
                // 락이 없으면 예약 실패
                return false;
            }
        }

        return true;
    }

    /**
     * 좌석 예약 취소 (환불 시 호출)
     */
    public boolean cancelSeats(List<Long> seatIds) {
        List<ScheduleSeat> seats = scheduleSeatRepository.findAllById(seatIds);

        int restored = 0;
        Long scheduleIdForCounter = null;

        for (ScheduleSeat seat : seats) {
            if (seat.getStatus() == ScheduleSeat.SeatStatus.BOOKED) {
                seat.setStatus(ScheduleSeat.SeatStatus.AVAILABLE);
                scheduleSeatRepository.save(seat);
                restored++;
                if (scheduleIdForCounter == null && seat.getSchedule() != null) {
                    scheduleIdForCounter = seat.getSchedule().getScheduleId();
                }
            }
        }

        if (scheduleIdForCounter != null && restored > 0) {
            int affected = scheduleRepository.incrementAvailableSeats(scheduleIdForCounter, restored);
            if (affected == 0) {
                System.err.println("Warning: 좌석 복원 중 가용 좌석 수가 이미 최대치에 도달했습니다. scheduleId=" + scheduleIdForCounter + ", restored=" + restored);
            }
            scheduleRepository.refreshScheduleStatus(scheduleIdForCounter);
        }

        return true;
    }

    /**
     * 만료된 락 정리
     */
    public void cleanupExpiredLocks() {
        List<SeatLock> expiredLocks = seatLockRepository
                .findByStatusAndExpiresAtBefore(SeatLock.LockStatus.ACTIVE, LocalDateTime.now());

        for (SeatLock lock : expiredLocks) {
            releaseSingleSeat(lock);
        }
    }

    /**
     * 사용자의 모든 활성 락 해제
     */
    public void releaseAllUserLocks(Long userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다: " + userId));

        List<SeatLock> userLocks = seatLockRepository
                .findByUserAndStatus(user, SeatLock.LockStatus.ACTIVE);

        for (SeatLock lock : userLocks) {
            releaseSingleSeat(lock);
        }
    }

    /**
     * 특정 사용자가 특정 좌석들을 예매할 수 있는지 확인
     * (자신이 락한 좌석은 예매 가능으로 판단)
     */
    @Transactional(readOnly = true)
    public boolean areSeatsAvailableForUser(List<Long> seatIds, Long userId) {
        List<ScheduleSeat> seats = scheduleSeatRepository.findAllById(seatIds);

        if (seats.size() != seatIds.size()) {
            return false; // 일부 좌석이 존재하지 않음
        }

        User user = userRepository.findById(userId)
                .orElseThrow(() -> new IllegalArgumentException("사용자를 찾을 수 없습니다: " + userId));

        for (ScheduleSeat seat : seats) {
            if (seat.getStatus() == ScheduleSeat.SeatStatus.AVAILABLE) {
                continue; // 사용 가능한 좌석
            }

            if (seat.getStatus() == ScheduleSeat.SeatStatus.BOOKED) {
                return false; // 이미 예매된 좌석
            }

            if (seat.getStatus() == ScheduleSeat.SeatStatus.LOCKED) {
                // 락된 좌석이지만 현재 사용자가 락한 것인지 확인
                Optional<SeatLock> lockOpt = seatLockRepository
                        .findBySeatSeatIdAndUserAndStatus(seat.getSeatId(), user, SeatLock.LockStatus.ACTIVE);

                if (lockOpt.isEmpty()) {
                    return false; // 다른 사용자가 락한 좌석
                }

                // 락이 만료되었는지 확인
                SeatLock lock = lockOpt.get();
                if (lock.getExpiresAt().isBefore(LocalDateTime.now())) {
                    return false; // 만료된 락
                }
            }
        }

        return true;
    }

    /**
     * 특정 좌석들이 지정된 스케줄에 속하는지 검증
     * @param seatIds 검증할 좌석 ID 목록
     * @param scheduleId 스케줄 ID
     * @return 모든 좌석이 해당 스케줄에 속하면 true, 아니면 false
     */
    @Transactional(readOnly = true)
    public boolean validateSeatsForSchedule(List<Long> seatIds, Long scheduleId) {
        if (seatIds == null || seatIds.isEmpty()) {
            return false;
        }

        // 요청된 좌석들을 스케줄 ID와 함께 조회
        List<ScheduleSeat> validSeats = scheduleSeatRepository.findBySchedule_ScheduleIdAndSeatIdIn(
                scheduleId, seatIds);

        // 조회된 좌석 수가 요청된 좌석 수와 같은지 확인
        return validSeats.size() == seatIds.size();
    }

    /**
     * 좌석들이 동일한 스케줄에 속하는지 검증 (cross-schedule 공격 방지)
     * @param seatIds 검증할 좌석 ID 목록
     * @return 모든 좌석이 동일한 스케줄에 속하면 해당 스케줄 ID, 아니면 null
     */
    @Transactional(readOnly = true)
    public Long validateSeatsInSameSchedule(List<Long> seatIds) {
        if (seatIds == null || seatIds.isEmpty()) {
            return null;
        }

        List<ScheduleSeat> seats = scheduleSeatRepository.findAllById(seatIds);

        if (seats.size() != seatIds.size()) {
            return null; // 일부 좌석이 존재하지 않음
        }

        // 모든 좌석의 스케줄 ID가 동일한지 확인
        Long scheduleId = seats.get(0).getSchedule().getScheduleId();
        boolean allSameSchedule = seats.stream()
                .allMatch(seat -> seat.getSchedule().getScheduleId().equals(scheduleId));

        return allSameSchedule ? scheduleId : null;
    }

    /**
     * 사용자가 특정 스케줄의 좌석들을 예약할 수 있는지 종합 검증
     * @param seatIds 좌석 ID 목록
     * @param scheduleId 스케줄 ID
     * @param userId 사용자 ID
     * @return 예약 가능하면 true
     */
    @Transactional(readOnly = true)
    public boolean canUserBookSeatsForSchedule(List<Long> seatIds, Long scheduleId, Long userId) {
        // 1. 좌석들이 해당 스케줄에 속하는지 검증
        if (!validateSeatsForSchedule(seatIds, scheduleId)) {
            return false;
        }

        // 2. 사용자가 해당 좌석들을 예약할 수 있는지 검증
        return areSeatsAvailableForUser(seatIds, userId);
    }

    // === Private Helper Methods ===

    private void releaseSingleSeat(SeatLock lock) {
        try {
            // 락 상태 변경
            lock.setStatus(SeatLock.LockStatus.RELEASED);
            seatLockRepository.save(lock);

            // 좌석 상태 되돌리기
            ScheduleSeat seat = lock.getSeat();
            boolean wasLockedOrBooked = (seat.getStatus() == ScheduleSeat.SeatStatus.LOCKED)
                    || (seat.getStatus() == ScheduleSeat.SeatStatus.BOOKED);
            seat.setStatus(ScheduleSeat.SeatStatus.AVAILABLE);
            scheduleSeatRepository.save(seat);

            // 가용 좌석 카운터 증가 (LOCKED/BOOKED -> AVAILABLE 전이인 경우만)
            if (seat.getSchedule() != null && wasLockedOrBooked) {
                Long scheduleId = seat.getSchedule().getScheduleId();
                int affected = scheduleRepository.incrementAvailableSeats(scheduleId, 1);
                if (affected == 0) {
                    System.err.println("Warning: 좌석 해제 중 가용 좌석 수가 이미 최대치에 도달했습니다. scheduleId=" + scheduleId + ", seatId=" + seat.getSeatId());
                }
                scheduleRepository.refreshScheduleStatus(scheduleId);
            }

            // Redis 락 해제
            String lockKey = REDIS_LOCK_PREFIX + seat.getSeatId();
            redisTemplate.delete(lockKey);

        } catch (Exception e) {
            throw new RuntimeException("좌석 락 해제 중 오류 발생", e);
        }
    }

    private void rollbackRedisLocks(List<String> lockKeys, String lockValue) {
        for (String lockKey : lockKeys) {
            try {
                // 같은 값으로 설정된 락만 삭제 (다른 프로세스의 락 보호)
                String currentValue = redisTemplate.opsForValue().get(lockKey);
                if (lockValue.equals(currentValue)) {
                    redisTemplate.delete(lockKey);
                }
            } catch (Exception e) {
                // 롤백 중 오류는 로깅만 하고 계속 진행
                System.err.println("Redis lock rollback error for key: " + lockKey);
            }
        }
    }

    private boolean isSameUserOrSession(SeatLock lock, User user, String sessionId) {
        return (lock.getUser() != null && lock.getUser().getUserId().equals(user.getUserId())) ||
                (lock.getSessionId() != null && lock.getSessionId().equals(sessionId));
    }

    private SeatDto convertToSeatDto(ScheduleSeat seat) {
        return SeatDto.builder()
                .seatId(seat.getSeatId())
                .scheduleId(seat.getSchedule().getScheduleId())
                .venueSeatId(null) // 더 이상 사용하지 않음
                .seatRow(seat.getRowLabel())
                .seatNumber(seat.getColNum())
                .seatZone(seat.getZone())
                .seatGrade(seat.getGrade())
                .price(seat.getPrice())
                .status(seat.getStatus().name())
                .build();
    }
}
