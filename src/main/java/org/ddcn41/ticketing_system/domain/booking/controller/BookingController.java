package org.ddcn41.ticketing_system.domain.booking.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
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

@RestController
@RequiredArgsConstructor
@RequestMapping("/v1/bookings")
@Tag(name = "Bookings", description = "APIs for booking management")
public class BookingController {

    private final BookingService bookingService;

    @PostMapping
    @Operation(summary = "Create a booking", description = "Creates a new booking for the authenticated user")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Booking created",
                    content = @Content(schema = @Schema(implementation = CreateBookingResponseDto.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content),
            @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
            @ApiResponse(responseCode = "404", description = "Related resource not found", content = @Content)
    })
    public ResponseEntity<CreateBookingResponseDto> createBooking(
            @Valid @RequestBody CreateBookingRequestDto body) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String username = auth != null ? auth.getName() : null;
        CreateBookingResponseDto res = bookingService.createBooking(username, body);
        return ResponseEntity.status(HttpStatus.CREATED).body(res);
    }

    @GetMapping("/{bookingId}")
    @Operation(summary = "Get my booking detail", description = "Fetches detailed information for user's own booking")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "OK",
                    content = @Content(schema = @Schema(implementation = GetBookingDetail200ResponseDto.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
            @ApiResponse(responseCode = "403", description = "Access denied - not your booking", content = @Content),
            @ApiResponse(responseCode = "404", description = "Booking not found", content = @Content)
    })
    public ResponseEntity<GetBookingDetail200ResponseDto> getMyBookingDetail(
            @Parameter(description = "Booking ID", required = true)
            @PathVariable Long bookingId) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String username = auth != null ? auth.getName() : null;
        return ResponseEntity.ok(bookingService.getUserBookingDetail(username, bookingId));
    }

    @GetMapping("/me")
    @Operation(summary = "List my bookings", description = "Lists current user's bookings filtered by status with pagination")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "OK",
                    content = @Content(schema = @Schema(implementation = GetBookings200ResponseDto.class))),
            @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content)
    })
    public ResponseEntity<GetBookings200ResponseDto> getMyBookings(
            @Parameter(description = "Filter by booking status (optional)")
            @RequestParam(value = "status", required = false) String status,
            @Parameter(description = "Page number (1-based)", example = "1")
            @RequestParam(value = "page", required = false, defaultValue = "1") Integer page,
            @Parameter(description = "Items per page", example = "20")
            @RequestParam(value = "limit", required = false, defaultValue = "20") Integer limit) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String username = auth != null ? auth.getName() : null;
        return ResponseEntity.ok(bookingService.getUserBookings(username, status, page, limit));
    }

    @PatchMapping("/{bookingId}/cancel")
    @Operation(summary = "Cancel a booking", description = "Cancels an existing booking")
    @SecurityRequirement(name = "bearerAuth")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Booking cancelled",
                    content = @Content(schema = @Schema(implementation = CancelBooking200ResponseDto.class))),
            @ApiResponse(responseCode = "400", description = "Invalid request", content = @Content),
            @ApiResponse(responseCode = "401", description = "Unauthorized", content = @Content),
            @ApiResponse(responseCode = "404", description = "Booking not found", content = @Content)
    })
    public ResponseEntity<CancelBooking200ResponseDto> cancelBooking(
            @Parameter(description = "Booking ID", required = true)
            @PathVariable Long bookingId,
            @Valid @RequestBody(required = false) CancelBookingRequestDto body) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        String username = auth != null ? auth.getName() : null;
        return ResponseEntity.ok(bookingService.cancelBooking(bookingId, body, username));
    }
}
