package org.ddcn41.ticketing_system.domain.booking.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;

public interface BookingProjection {
    Long getBookingId();
    String getBookingNumber();
    
    // User
    Long getUserId();
    String getUserName();
    String getUserPhone();
    
    // Schedule
    Long getScheduleId();
    LocalDateTime getShowDatetime();
    
    // Performance
    String getPerformanceTitle();
    
    // Venue
    String getVenueName();
    
    // Seat
    String getSeatCode(); // concatenated seat_row + seat_number
    String getSeatZone();
    Long getBookingSeatId();
    String getSeatGrade();
    String getSeatRowLabel();
    String getSeatColNum();
    java.math.BigDecimal getSeatPrice();
    
    // Booking
    Integer getSeatCount();
    BigDecimal getTotalAmount();
    String getStatus(); // BookingStatus enum을 String으로
    
    LocalDateTime getExpiresAt();
    LocalDateTime getBookedAt();
    LocalDateTime getCancelledAt();
    String getCancellationReason();
    LocalDateTime getCreatedAt();
    LocalDateTime getUpdatedAt();
}
