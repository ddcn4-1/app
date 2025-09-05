package org.ddcn41.ticketing_system.domain.booking.dto;

import lombok.*;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.OffsetDateTime;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Setter
@Builder
public class BookingSeatDto {
    private Long bookingSeatId;
    private Long bookingId;
    private Long seatId;
    private Double seatPrice;

    @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
    private OffsetDateTime createdAt;
}
