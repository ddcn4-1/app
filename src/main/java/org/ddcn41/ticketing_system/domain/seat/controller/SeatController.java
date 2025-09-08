package org.ddcn41.ticketing_system.domain.seat.controller;


import lombok.RequiredArgsConstructor;
import org.ddcn41.ticketing_system.domain.seat.dto.request.SeatConfirmRequest;
import org.ddcn41.ticketing_system.domain.seat.dto.request.SeatLockRequest;
import org.ddcn41.ticketing_system.domain.seat.dto.request.SeatReleaseRequest;
import org.ddcn41.ticketing_system.dto.response.ApiResponse;
import org.ddcn41.ticketing_system.domain.seat.dto.response.SeatAvailabilityResponse;
import org.ddcn41.ticketing_system.domain.seat.dto.response.SeatLockResponse;
import org.ddcn41.ticketing_system.domain.seat.service.SeatService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
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
            @Valid @RequestBody SeatLockRequest request) {

        // scheduleId는 검증용으로 사용 (실제 좌석 ID로 스케줄 검증)
        SeatLockResponse response = seatService.lockSeats(
                request.getSeatIds(),
                request.getUserId(),
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
            @Valid @RequestBody SeatReleaseRequest request) {

        boolean released = seatService.releaseSeats(
                request.getSeatIds(),
                request.getUserId(),
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
            @Valid @RequestBody SeatConfirmRequest request) {

        boolean confirmed = seatService.confirmSeats(
                request.getSeatIds(),
                request.getUserId()
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