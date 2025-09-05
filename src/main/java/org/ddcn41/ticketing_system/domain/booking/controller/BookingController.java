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
    public ResponseEntity<CreateBookingResponseDto> createBooking(@Valid @RequestBody CreateBookingRequestDto body) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String username = auth != null ? auth.getName() : null;
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
    public ResponseEntity<GetBookingDetail200ResponseDto> getBookingDetail(@PathVariable Long bookingId) {
        try {
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
            @RequestParam(value = "limit", required = false, defaultValue = "20") Integer limit) {
        try {
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
            @Valid @RequestBody(required = false) CancelBookingRequestDto body) {
        try {
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
}
