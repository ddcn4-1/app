package org.ddcn41.ticketing_system.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.ddcn41.ticketing_system.dto.BookingSeatDto;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.OffsetDateTime;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CreateBookingResponseDto {
    private Long bookingId;
    private String bookingNumber;
    private Long userId;
    private Long scheduleId;
    private Integer seatCount;
    private Double totalAmount;
    private String status;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private OffsetDateTime expiresAt;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private OffsetDateTime bookedAt;

    private List<BookingSeatDto> seats;
}

