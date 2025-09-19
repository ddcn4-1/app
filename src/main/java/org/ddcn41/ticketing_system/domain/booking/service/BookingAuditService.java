package org.ddcn41.ticketing_system.domain.booking.service;

import lombok.RequiredArgsConstructor;
import org.ddcn41.ticketing_system.domain.booking.entity.Booking;
import org.ddcn41.ticketing_system.domain.user.entity.User;
import org.ddcn41.ticketing_system.global.util.AuditEventBuilder;
import org.springframework.boot.actuate.audit.AuditEventRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;

@Service
@RequiredArgsConstructor
public class BookingAuditService {

    private static final String SYSTEM_PRINCIPAL = "system";

    private final AuditEventRepository auditEventRepository;

    public void logBookingCreated(User user, Booking booking, List<Long> seatIds) {
        auditEventRepository.add(
                AuditEventBuilder.builder()
                        .principal(resolvePrincipal(user))
                        .type("BOOKING_CREATED")
                        .data("bookingId", booking.getBookingId())
                        .data("scheduleId", booking.getSchedule() != null ? booking.getSchedule().getScheduleId() : null)
                        .data("seatCount", booking.getSeatCount())
                        .data("totalAmount", toPlainAmount(booking.getTotalAmount()))
                        .data("seatIds", seatIds)
                        .build()
        );
    }

    public void logBookingCancelled(String actorUsername, Booking booking, String reason) {
        auditEventRepository.add(
                AuditEventBuilder.builder()
                        .principal(actorUsername != null ? actorUsername : resolvePrincipal(booking.getUser()))
                        .type("BOOKING_CANCELLED")
                        .data("bookingId", booking.getBookingId())
                        .data("scheduleId", booking.getSchedule() != null ? booking.getSchedule().getScheduleId() : null)
                        .data("refundAmount", toPlainAmount(booking.getTotalAmount()))
                        .data("reason", reason)
                        .build()
        );
    }

    private String resolvePrincipal(User user) {
        if (user != null && user.getUsername() != null) {
            return user.getUsername();
        }
        return SYSTEM_PRINCIPAL;
    }

    private String toPlainAmount(BigDecimal amount) {
        if (amount == null) {
            return null;
        }
        return amount.stripTrailingZeros().toPlainString();
    }
}
