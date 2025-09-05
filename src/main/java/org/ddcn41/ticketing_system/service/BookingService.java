package org.ddcn41.ticketing_system.service;

import lombok.RequiredArgsConstructor;
import org.ddcn41.ticketing_system.domain.performance.entity.PerformanceSchedule;
import org.ddcn41.ticketing_system.domain.performance.repository.PerformanceScheduleRepository;
import org.ddcn41.ticketing_system.dto.*;
import org.ddcn41.ticketing_system.dto.request.CancelBookingRequestDto;
import org.ddcn41.ticketing_system.dto.request.CreateBookingRequestDto;
import org.ddcn41.ticketing_system.dto.response.CancelBooking200ResponseDto;
import org.ddcn41.ticketing_system.dto.response.CreateBookingResponseDto;
import org.ddcn41.ticketing_system.dto.response.GetBookingDetail200ResponseDto;
import org.ddcn41.ticketing_system.dto.response.GetBookings200ResponseDto;
import org.ddcn41.ticketing_system.entity.*;
import org.ddcn41.ticketing_system.entity.Booking.BookingStatus;
import org.ddcn41.ticketing_system.entity.ScheduleSeat.SeatStatus;
import org.ddcn41.ticketing_system.repository.*;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.*;

@Service
@RequiredArgsConstructor
public class BookingService {

    private final BookingRepository bookingRepository;
    private final BookingSeatRepository bookingSeatRepository;
    private final PerformanceScheduleRepository scheduleRepository;
    private final ScheduleSeatRepository scheduleSeatRepository;
    private final UserRepository userRepository;

    @Transactional
    public CreateBookingResponseDto createBooking(String username, CreateBookingRequestDto req) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED, "사용자 인증 실패"));

        PerformanceSchedule schedule = scheduleRepository.findById(req.getScheduleId())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "스케줄을 찾을 수 없습니다"));

        List<ScheduleSeat> seats = scheduleSeatRepository.findBySchedule_ScheduleIdAndSeatIdIn(req.getScheduleId(), req.getSeatIds());
        if (seats.size() != req.getSeatIds().size()) {
            throw new ResponseStatusException(BAD_REQUEST, "일부 좌석을 찾을 수 없습니다");
        }

        // 좌석 가용성 체크
        if (seats.stream().anyMatch(s -> s.getStatus() != SeatStatus.AVAILABLE)) {
            throw new ResponseStatusException(BAD_REQUEST, "이미 예약되었거나 잠긴 좌석이 포함되어 있습니다");
        }

        BigDecimal total = seats.stream()
                .map(ScheduleSeat::getPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        String bookingNumber = "B-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        Booking booking = Booking.builder()
                .bookingNumber(bookingNumber)
                .user(user)
                .schedule(schedule)
                .seatCount(seats.size())
                .totalAmount(total)
                .status(BookingStatus.CONFIRMED)
                .build();

        Booking saved = bookingRepository.save(booking);

        // 좌석 예약 생성 및 좌석 상태 변경
        java.util.ArrayList<BookingSeat> savedSeats = new java.util.ArrayList<>();
        for (ScheduleSeat seat : seats) {
            BookingSeat bs = BookingSeat.builder()
                    .booking(saved)
                    .seat(seat)
                    .seatPrice(seat.getPrice())
                    .build();
            savedSeats.add(bookingSeatRepository.save(bs));
            seat.setStatus(SeatStatus.BOOKED);
        }

        // 좌석 상태 일괄 저장
        scheduleSeatRepository.saveAll(seats);
        saved.setBookingSeats(savedSeats);

        return toCreateResponse(saved);
    }

    @Transactional(readOnly = true)
    public GetBookingDetail200ResponseDto getBookingDetail(Long bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "예매를 찾을 수 없습니다"));
        return toDetailDto(booking);
    }

    @Transactional(readOnly = true)
    public GetBookings200ResponseDto getBookings(String status, int page, int limit) {
        PageRequest pr = PageRequest.of(Math.max(page - 1, 0), Math.max(limit, 1));

        Page<Booking> result;
        if (status != null && !status.isBlank()) {
            BookingStatus bs;
            try {
                bs = BookingStatus.valueOf(status.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(BAD_REQUEST, "유효하지 않은 상태 값");
            }
            result = bookingRepository.findAllByStatus(bs, pr);
        } else {
            result = bookingRepository.findAll(pr);
        }

        List<BookingDto> items = result.getContent().stream()
                .map(this::toListDto)
                .collect(Collectors.toList());

        return GetBookings200ResponseDto.builder()
                .bookings(items)
                .total(Math.toIntExact(result.getTotalElements()))
                .page(page)
                .build();
    }

    @Transactional
    public CancelBooking200ResponseDto cancelBooking(Long bookingId, CancelBookingRequestDto req) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "예매를 찾을 수 없습니다"));

        if (booking.getStatus() == BookingStatus.CANCELLED) {
            throw new ResponseStatusException(BAD_REQUEST, "이미 취소된 예매입니다");
        }

        booking.setStatus(BookingStatus.CANCELLED);
        booking.setCancelledAt(java.time.LocalDateTime.now());
        if (req != null) {
            booking.setCancellationReason(req.getReason());
        }

        // 좌석 상태 되돌리기
        List<BookingSeat> seats = booking.getBookingSeats();
        if (seats != null) {
            for (BookingSeat bs : seats) {
                ScheduleSeat seat = bs.getSeat();
                seat.setStatus(SeatStatus.AVAILABLE);
                scheduleSeatRepository.save(seat);
            }
        }

        bookingRepository.save(booking);

        return CancelBooking200ResponseDto.builder()
                .message("예매 취소 성공")
                .bookingId(booking.getBookingId())
                .status(BookingStatus.CANCELLED.name())
                .cancelledAt(odt(booking.getCancelledAt()))
                .refundAmount(booking.getTotalAmount() == null ? 0.0 : booking.getTotalAmount().doubleValue())
                .build();
    }

    private CreateBookingResponseDto toCreateResponse(Booking b) {
        return CreateBookingResponseDto.builder()
                .bookingId(b.getBookingId())
                .bookingNumber(b.getBookingNumber())
                .userId(b.getUser() != null ? b.getUser().getUserId() : null)
                .scheduleId(b.getSchedule() != null ? b.getSchedule().getScheduleId() : null)
                .seatCount(b.getSeatCount())
                .totalAmount(b.getTotalAmount() == null ? 0.0 : b.getTotalAmount().doubleValue())
                .status(b.getStatus() != null ? b.getStatus().name() : null)
                .expiresAt(odt(b.getExpiresAt()))
                .bookedAt(odt(b.getBookedAt()))
                .seats(b.getBookingSeats() == null ? List.of() : b.getBookingSeats().stream().map(this::toSeatDto).collect(Collectors.toList()))
                .build();
    }

    private BookingSeatDto toSeatDto(BookingSeat bs) {
        return BookingSeatDto.builder()
                .bookingSeatId(bs.getBookingSeatId())
                .bookingId(bs.getBooking() != null ? bs.getBooking().getBookingId() : null)
                .seatId(bs.getSeat() != null ? bs.getSeat().getSeatId() : null)
                .seatPrice(bs.getSeatPrice() == null ? 0.0 : bs.getSeatPrice().doubleValue())
                .createdAt(odt(bs.getCreatedAt()))
                .build();
    }

    private BookingDto toListDto(Booking b) {
        return BookingDto.builder()
                .bookingId(b.getBookingId())
                .bookingNumber(b.getBookingNumber())
                .userId(b.getUser() != null ? b.getUser().getUserId() : null)
                .scheduleId(b.getSchedule() != null ? b.getSchedule().getScheduleId() : null)
                .seatCount(b.getSeatCount())
                .totalAmount(b.getTotalAmount() == null ? 0.0 : b.getTotalAmount().doubleValue())
                .status(b.getStatus() == null ? null : BookingDto.StatusEnum.valueOf(b.getStatus().name()))
                .expiresAt(odt(b.getExpiresAt()))
                .bookedAt(odt(b.getBookedAt()))
                .cancelledAt(odt(b.getCancelledAt()))
                .cancellationReason(b.getCancellationReason())
                .createdAt(odt(b.getCreatedAt()))
                .updatedAt(odt(b.getUpdatedAt()))
                .build();
    }

    private GetBookingDetail200ResponseDto toDetailDto(Booking b) {
        return GetBookingDetail200ResponseDto.builder()
                .bookingId(b.getBookingId())
                .bookingNumber(b.getBookingNumber())
                .userId(b.getUser() != null ? b.getUser().getUserId() : null)
                .scheduleId(b.getSchedule() != null ? b.getSchedule().getScheduleId() : null)
                .seatCount(b.getSeatCount())
                .totalAmount(b.getTotalAmount() == null ? 0.0 : b.getTotalAmount().doubleValue())
                .status(b.getStatus() == null ? null : GetBookingDetail200ResponseDto.StatusEnum.valueOf(b.getStatus().name()))
                .expiresAt(odt(b.getExpiresAt()))
                .bookedAt(odt(b.getBookedAt()))
                .cancelledAt(odt(b.getCancelledAt()))
                .cancellationReason(b.getCancellationReason())
                .createdAt(odt(b.getCreatedAt()))
                .updatedAt(odt(b.getUpdatedAt()))
                .seats(b.getBookingSeats() == null ? List.of() : b.getBookingSeats().stream().map(this::toSeatDto).collect(Collectors.toList()))
                .build();
    }

    private OffsetDateTime odt(java.time.LocalDateTime ldt) {
        return ldt == null ? null : ldt.atOffset(ZoneOffset.UTC);
    }
}
