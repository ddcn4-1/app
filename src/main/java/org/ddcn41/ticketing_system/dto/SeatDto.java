package org.ddcn41.ticketing_system.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SeatDto {
    private Long seatId;
    private Long scheduleId;
    private Long venueSeatId;
    private String seatRow;
    private String seatNumber;
    private String seatZone;
    private String seatGrade;
    private BigDecimal price;
    private String status; // AVAILABLE, LOCKED, BOOKED
}