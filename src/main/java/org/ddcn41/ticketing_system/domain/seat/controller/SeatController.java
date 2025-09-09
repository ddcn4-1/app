package org.ddcn41.ticketing_system.domain.seat.controller;

import lombok.RequiredArgsConstructor;
import org.ddcn41.ticketing_system.domain.seat.dto.request.SeatConfirmRequest;
import org.ddcn41.ticketing_system.domain.seat.dto.request.SeatLockRequest;
import org.ddcn41.ticketing_system.domain.seat.dto.request.SeatReleaseRequest;
import org.ddcn41.ticketing_system.dto.response.ApiResponse;
import org.ddcn41.ticketing_system.domain.seat.dto.response.SeatAvailabilityResponse;
import org.ddcn41.ticketing_system.domain.seat.dto.response.SeatLockResponse;
import org.ddcn41.ticketing_system.domain.seat.service.SeatService;
import org.ddcn41.ticketing_system.domain.user.entity.User;
import org.ddcn41.ticketing_system.domain.user.service.UserService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import jakarta.validation.Valid;
import java.util.List;

/**
 * 좌석 상태 관리 API 컨트롤러
 * 스케줄별 좌석 관리를 담당하는 RESTful 엔드포인트
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
public class SeatController {

    private final SeatService seatService;
    private final UserService userService;

    /**
     * 스케줄의 좌석 가용성 조회
     * GET /api/v1/schedules/{scheduleId}/seats
     */
    @GetMapping("/schedules/{scheduleId}/seats")
    public ResponseEntity<ApiResponse<SeatAvailabilityResponse>> getScheduleSeats(
            @PathVariable Long scheduleId) {

        SeatAvailabilityResponse response = seatService.getSeatsAvailability(scheduleId);

        return ResponseEntity.ok(
                ApiResponse.success("좌석 조회 성공", response)
        );
    }

    /**
     * 특정 좌석들의 가용성 확인
     * POST /api/v1/seats/check-availability
     */
    @PostMapping("/seats/check-availability")
    public ResponseEntity<ApiResponse<Boolean>> checkSeatsAvailability(
            @RequestBody List<Long> seatIds) {

        boolean available = seatService.areSeatsAvailable(seatIds);

        return ResponseEntity.ok(
                ApiResponse.success(
                        available ? "모든 좌석이 예약 가능합니다" : "일부 좌석이 예약 불가능합니다",
                        available
                )
        );
    }

    /**
     * 스케줄의 좌석 락 요청
     * POST /api/v1/schedules/{scheduleId}/seats/lock
     */
    @PostMapping("/schedules/{scheduleId}/seats/lock")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<SeatLockResponse>> lockScheduleSeats(
            @PathVariable Long scheduleId,
            @Valid @RequestBody SeatLockRequest request,
            Authentication authentication) {

        // 보안 : 인증된 사용자 정보에서 userId 추출
        String username = authentication.getName();
        User authenticatedUser = userService.findByUsername(username);

        // 관리자가 아닌 경우, 요청의 userId와 인증된 사용자가 일치하는지 검증
        if (!User.Role.ADMIN.equals(authenticatedUser.getRole()) &&
                !authenticatedUser.getUserId().equals(request.getUserId())) {
            return ResponseEntity.status(403).body(
                    ApiResponse.error("본인의 좌석만 잠금할 수 있습니다", "FORBIDDEN", null)
            );
        }

        // 추가 검증: 요청된 좌석들이 해당 스케줄에 속하는지 확인
        boolean seatsValidForSchedule = seatService.validateSeatsForSchedule(request.getSeatIds(), scheduleId);
        if (!seatsValidForSchedule) {
            return ResponseEntity.badRequest().body(
                    ApiResponse.error("선택한 좌석이 해당 공연 스케줄에 속하지 않습니다", "INVALID_SEATS", null)
            );
        }

        // 실제 사용할 userId는 인증된 사용자의 ID (관리자인 경우에만 request의 userId 허용)
        Long effectiveUserId = User.Role.ADMIN.equals(authenticatedUser.getRole()) ?
                request.getUserId() : authenticatedUser.getUserId();

        SeatLockResponse response = seatService.lockSeats(
                request.getSeatIds(),
                effectiveUserId,  // 인증된 사용자 ID 사용
                request.getSessionId()
        );

        if (response.isSuccess()) {
            return ResponseEntity.ok(
                    ApiResponse.success(response.getMessage(), response)
            );
        } else {
            return ResponseEntity.badRequest().body(
                    ApiResponse.error(response.getMessage(), "SEAT_LOCK_FAILED", response)
            );
        }
    }

    /**
     * 스케줄의 좌석 락 해제
     * DELETE /api/v1/schedules/{scheduleId}/seats/lock
     */
    @DeleteMapping("/schedules/{scheduleId}/seats/lock")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Boolean>> releaseScheduleSeats(
            @PathVariable Long scheduleId,
            @Valid @RequestBody SeatReleaseRequest request,
            Authentication authentication) {

        // 보안 수정: 인증된 사용자 정보에서 userId 추출
        String username = authentication.getName();
        User authenticatedUser = userService.findByUsername(username);

        // 관리자가 아닌 경우, 요청의 userId와 인증된 사용자가 일치하는지 검증
        if (!User.Role.ADMIN.equals(authenticatedUser.getRole()) &&
                !authenticatedUser.getUserId().equals(request.getUserId())) {
            return ResponseEntity.status(403).body(
                    ApiResponse.error("본인의 좌석만 해제할 수 있습니다", "FORBIDDEN", false)
            );
        }

        // 추가 검증: 요청된 좌석들이 해당 스케줄에 속하는지 확인
        boolean seatsValidForSchedule = seatService.validateSeatsForSchedule(request.getSeatIds(), scheduleId);
        if (!seatsValidForSchedule) {
            return ResponseEntity.badRequest().body(
                    ApiResponse.error("선택한 좌석이 해당 공연 스케줄에 속하지 않습니다", "INVALID_SEATS", false)
            );
        }

        // 실제 사용할 userId는 인증된 사용자의 ID (관리자인 경우에만 request의 userId 허용)
        Long effectiveUserId = User.Role.ADMIN.equals(authenticatedUser.getRole()) ?
                request.getUserId() : authenticatedUser.getUserId();

        boolean released = seatService.releaseSeats(
                request.getSeatIds(),
                effectiveUserId,  // 인증된 사용자 ID 사용
                request.getSessionId()
        );

        return ResponseEntity.ok(
                ApiResponse.success(
                        released ? "좌석 락 해제 성공" : "일부 좌석 락 해제 실패",
                        released
                )
        );
    }

    /**
     * 좌석 예약 확정 (결제 완료 후)
     * POST /api/v1/seats/confirm
     */
    @PostMapping("/seats/confirm")
    @PreAuthorize("hasRole('USER') or hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Boolean>> confirmSeats(
            @Valid @RequestBody SeatConfirmRequest request,
            Authentication authentication) {

        //  인증된 사용자 정보에서 userId 추출
        String username = authentication.getName();
        User authenticatedUser = userService.findByUsername(username);

        // 관리자가 아닌 경우, 요청의 userId와 인증된 사용자가 일치하는지 검증
        if (!User.Role.ADMIN.equals(authenticatedUser.getRole()) &&
                !authenticatedUser.getUserId().equals(request.getUserId())) {
            return ResponseEntity.status(403).body(
                    ApiResponse.error("본인의 좌석만 확정할 수 있습니다", "FORBIDDEN", false)
            );
        }

        // 실제 사용할 userId는 인증된 사용자의 ID (관리자인 경우에만 request의 userId 허용)
        Long effectiveUserId = User.Role.ADMIN.equals(authenticatedUser.getRole()) ?
                request.getUserId() : authenticatedUser.getUserId();

        boolean confirmed = seatService.confirmSeats(
                request.getSeatIds(),
                effectiveUserId  // 인증된 사용자 ID 사용
        );

        if (confirmed) {
            return ResponseEntity.ok(
                    ApiResponse.success("좌석 예약 확정 성공", true)
            );
        } else {
            return ResponseEntity.badRequest().body(
                    ApiResponse.error("좌석 예약 확정 실패", "SEAT_CONFIRM_FAILED", false)
            );
        }
    }

    /**
     * 좌석 예약 취소 (환불 시)
     * POST /api/v1/seats/cancel
     */
    @PostMapping("/seats/cancel")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<Boolean>> cancelSeats(
            @RequestBody List<Long> seatIds) {

        boolean cancelled = seatService.cancelSeats(seatIds);

        return ResponseEntity.ok(
                ApiResponse.success("좌석 예약 취소 성공", cancelled)
        );
    }

    /**
     * 사용자의 모든 락 해제 (관리자 전용)
     * DELETE /api/v1/users/{userId}/seat-locks
     */
    @DeleteMapping("/users/{userId}/seat-locks")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<String>> releaseAllUserLocks(
            @PathVariable Long userId) {

        seatService.releaseAllUserLocks(userId);

        return ResponseEntity.ok(
                ApiResponse.success("사용자의 모든 좌석 락 해제 완료")
        );
    }

    /**
     * 만료된 락 정리 (스케줄러/관리자 전용)
     * POST /api/v1/seats/cleanup-expired
     */
    @PostMapping("/seats/cleanup-expired")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<ApiResponse<String>> cleanupExpiredLocks() {

        seatService.cleanupExpiredLocks();

        return ResponseEntity.ok(
                ApiResponse.success("만료된 락 정리 완료")
        );
    }
}