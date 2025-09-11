package org.ddcn41.ticketing_system.domain.booking.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.OffsetDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class BookingDto {
    private Long bookingId;
    private String bookingNumber;
    private Long userId;
    private String userName; // from User.name
    private String userPhone; // from User.phone
    private Long scheduleId;
    private String performanceTitle; // from PerformanceSchedule.performance.title
    private String venueName; // from PerformanceSchedule.performance.venue.venueName
    private OffsetDateTime showDate; // from PerformanceSchedule.showDatetime
    private String seatCode; // concatenated seat_row + seat_number
    private String seatZone; // from seat.seatZone
    private Integer seatCount;
    private Double totalAmount;

    public enum StatusEnum { PENDING, CONFIRMED, CANCELLED }
    private StatusEnum status;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private OffsetDateTime expiresAt;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private OffsetDateTime bookedAt;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private OffsetDateTime cancelledAt;
    private String cancellationReason;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private OffsetDateTime createdAt;
    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private OffsetDateTime updatedAt;
}
