package org.ddcn41.ticketing_system.domain.booking.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.ddcn41.ticketing_system.domain.booking.dto.request.CancelBookingRequestDto;
import org.ddcn41.ticketing_system.domain.booking.dto.request.CreateBookingRequestDto;
import org.ddcn41.ticketing_system.domain.booking.dto.response.CancelBooking200ResponseDto;
import org.ddcn41.ticketing_system.domain.booking.dto.response.CreateBookingResponseDto;
import org.ddcn41.ticketing_system.domain.booking.dto.response.GetBookingDetail200ResponseDto;
import org.ddcn41.ticketing_system.domain.booking.dto.response.GetBookings200ResponseDto;
import org.ddcn41.ticketing_system.domain.booking.service.BookingService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/v1/bookings")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;

    @PostMapping
    public ResponseEntity<CreateBookingResponseDto> createBooking(
            @Valid @RequestBody CreateBookingRequestDto body,
            Authentication authentication) {

        // 보안 : 인증 컨텍스트에서 사용자명 추출
        String username = authentication != null ? authentication.getName() : null;
        if (username == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "인증이 필요합니다");
        }

        try {
            CreateBookingResponseDto res = bookingService.createBooking(username, body);
            return ResponseEntity.status(HttpStatus.CREATED).body(res);
        } catch (ResponseStatusException ex) {
            System.err.println("[createBooking] ResponseStatusException: " + ex.getReason());
            ex.printStackTrace();
            throw ex; // rethrow to let framework/global handler process
        } catch (Exception ex) {
            System.err.println("[createBooking] Unexpected exception: " + ex.getMessage());
            ex.printStackTrace();
            throw ex;
        }
    }

    @GetMapping("/{bookingId}")
    public ResponseEntity<GetBookingDetail200ResponseDto> getBookingDetail(
            @PathVariable Long bookingId,
            Authentication authentication) {
        try {
            // 보안: 본인의 예약만 조회할 수 있도록 제한 (서비스에서 구현)
            return ResponseEntity.ok(bookingService.getBookingDetail(bookingId));
        } catch (ResponseStatusException ex) {
            System.err.println("[getBookingDetail] ResponseStatusException: " + ex.getReason());
            ex.printStackTrace();
            throw ex;
        } catch (Exception ex) {
            System.err.println("[getBookingDetail] Unexpected exception: " + ex.getMessage());
            ex.printStackTrace();
            throw ex;
        }
    }

    @GetMapping
    public ResponseEntity<GetBookings200ResponseDto> getBookings(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", required = false, defaultValue = "1") Integer page,
            @RequestParam(value = "limit", required = false, defaultValue = "20") Integer limit,
            Authentication authentication) {
        try {
            // 일반 사용자는 본인 예약만, 관리자는 모든 예약 조회
            String username = authentication != null ? authentication.getName() : null;
            if (username == null) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "인증이 필요합니다");
            }

            // 관리자 권한 확인 로직은 서비스에서 처리
            return ResponseEntity.ok(bookingService.getBookings(status, page, limit));
        } catch (ResponseStatusException ex) {
            System.err.println("[getBookings] ResponseStatusException: " + ex.getReason());
            ex.printStackTrace();
            throw ex;
        } catch (Exception ex) {
            System.err.println("[getBookings] Unexpected exception: " + ex.getMessage());
            ex.printStackTrace();
            throw ex;
        }
    }

    @PatchMapping("/{bookingId}/cancel")
    public ResponseEntity<CancelBooking200ResponseDto> cancelBooking(
            @PathVariable Long bookingId,
            @Valid @RequestBody(required = false) CancelBookingRequestDto body,
            Authentication authentication) {
        try {
            // 보안: 본인의 예약만 취소할 수 있도록 제한 (서비스에서 구현)
            return ResponseEntity.ok(bookingService.cancelBooking(bookingId, body));
        } catch (ResponseStatusException ex) {
            System.err.println("[cancelBooking] ResponseStatusException: " + ex.getReason());
            ex.printStackTrace();
            throw ex;
        } catch (Exception ex) {
            System.err.println("[cancelBooking] Unexpected exception: " + ex.getMessage());
            ex.printStackTrace();
            throw ex;
        }
    }

    /**
     * 사용자별 예약 목록 조회 (본인 예약만)
     */
    @GetMapping("/my")
    public ResponseEntity<GetBookings200ResponseDto> getMyBookings(
            @RequestParam(value = "status", required = false) String status,
            @RequestParam(value = "page", required = false, defaultValue = "1") Integer page,
            @RequestParam(value = "limit", required = false, defaultValue = "20") Integer limit,
            Authentication authentication) {
        try {
            String username = authentication != null ? authentication.getName() : null;
            if (username == null) {
                throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "인증이 필요합니다");
            }

            return ResponseEntity.ok(bookingService.getUserBookings(username, status, page, limit));
        } catch (ResponseStatusException ex) {
            System.err.println("[getMyBookings] ResponseStatusException: " + ex.getReason());
            ex.printStackTrace();
            throw ex;
        } catch (Exception ex) {
            System.err.println("[getMyBookings] Unexpected exception: " + ex.getMessage());
            ex.printStackTrace();
            throw ex;
        }
    }
}