package org.ddcn41.ticketing_system.domain.booking.service;

import lombok.RequiredArgsConstructor;
import org.ddcn41.ticketing_system.domain.performance.entity.PerformanceSchedule;
import org.ddcn41.ticketing_system.domain.performance.repository.PerformanceScheduleRepository;
import org.ddcn41.ticketing_system.domain.booking.dto.BookingDto;
import org.ddcn41.ticketing_system.domain.booking.dto.BookingSeatDto;
import org.ddcn41.ticketing_system.domain.booking.dto.request.CancelBookingRequestDto;
import org.ddcn41.ticketing_system.domain.booking.dto.request.CreateBookingRequestDto;
import org.ddcn41.ticketing_system.domain.booking.dto.response.CancelBooking200ResponseDto;
import org.ddcn41.ticketing_system.domain.booking.dto.response.CreateBookingResponseDto;
import org.ddcn41.ticketing_system.domain.booking.dto.response.GetBookingDetail200ResponseDto;
import org.ddcn41.ticketing_system.domain.booking.dto.response.GetBookings200ResponseDto;
import org.ddcn41.ticketing_system.domain.booking.entity.Booking;
import org.ddcn41.ticketing_system.domain.booking.entity.Booking.BookingStatus;
import org.ddcn41.ticketing_system.domain.booking.entity.BookingSeat;
import org.ddcn41.ticketing_system.domain.seat.entity.ScheduleSeat;
import org.ddcn41.ticketing_system.domain.seat.repository.ScheduleSeatRepository;
import org.ddcn41.ticketing_system.domain.seat.service.SeatService;
import org.ddcn41.ticketing_system.domain.user.entity.User;
import org.ddcn41.ticketing_system.domain.booking.repository.BookingRepository;
import org.ddcn41.ticketing_system.domain.booking.repository.BookingSeatRepository;
import org.ddcn41.ticketing_system.domain.user.repository.UserRepository;

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

    private final SeatService seatService;

    @Transactional(rollbackFor = Exception.class)
    public CreateBookingResponseDto createBooking(String username, CreateBookingRequestDto req) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED, "사용자 인증 실패"));

        PerformanceSchedule schedule = scheduleRepository.findById(req.getScheduleId())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "스케줄을 찾을 수 없습니다"));

        // 요청된 좌석들이 해당 스케줄에 속하는지 먼저 검증
        List<ScheduleSeat> requestedSeats = scheduleSeatRepository.findBySchedule_ScheduleIdAndSeatIdIn(
                req.getScheduleId(), req.getSeatIds());

        if (requestedSeats.size() != req.getSeatIds().size()) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "선택한 좌석 중 일부가 해당 공연 스케줄에 속하지 않습니다");
        }

        // 보안  2: 모든 좌석이 올바른 스케줄에 속하는지 재확인
        boolean allSeatsInSchedule = requestedSeats.stream()
                .allMatch(seat -> seat.getSchedule().getScheduleId().equals(req.getScheduleId()));

        if (!allSeatsInSchedule) {
            throw new ResponseStatusException(BAD_REQUEST,
                    "선택한 좌석이 요청된 공연 스케줄과 일치하지 않습니다");
        }

        // 좌석 가용성 확인 및 낙관적 락으로 좌석 상태 변경
        for (ScheduleSeat seat : requestedSeats) {
            if (seat.getStatus() != ScheduleSeat.SeatStatus.AVAILABLE) {
                throw new ResponseStatusException(BAD_REQUEST, "예약 불가능한 좌석이 포함되어 있습니다: " + seat.getSeatId());
            }
            // 예약 생성 시 좌석 상태를 LOCKED로 변경
            seat.setStatus(ScheduleSeat.SeatStatus.LOCKED);
        }

        // 낙관적 락으로 좌석 상태 저장 (버전 충돌 시 OptimisticLockException 발생)
        try {
            scheduleSeatRepository.saveAll(requestedSeats);
        } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
            throw new ResponseStatusException(BAD_REQUEST, "다른 사용자가 먼저 예약한 좌석이 있습니다. 다시 시도해주세요.");
        }

        // 이미 검증된 requestedSeats 사용 (중복 조회 방지)
        List<ScheduleSeat> seats = requestedSeats;

        BigDecimal total = seats.stream()
                .map(ScheduleSeat::getPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        String bookingNumber = "DDCN-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();

        Booking booking = Booking.builder()
                .bookingNumber(bookingNumber)
                .user(user)
                .schedule(schedule)
                .seatCount(seats.size())
                .totalAmount(total)
                .status(BookingStatus.PENDING) // 결제 전 PENDING
//                .expiresAt(lockResponse.getExpiresAt()) // 락 만료 시간 동일
                .build();

        Booking saved = bookingRepository.save(booking);

        // 예약 좌석 정보 생성 (좌석 상태 변경은 하지 않음)
        List<BookingSeat> savedSeats = seats.stream()
                .map(seat -> BookingSeat.builder()
                        .booking(saved)
                        .seat(seat)
                        .seatPrice(seat.getPrice())
                        .build())
                .map(bookingSeatRepository::save)
                .collect(Collectors.toList());

        saved.setBookingSeats(savedSeats);

        return toCreateResponse(saved);
    }

    /**
     * 예약 확정 (결제 완료 후 호출)
     */
    @Transactional(rollbackFor = Exception.class)
    public void confirmBooking(Long bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "예매를 찾을 수 없습니다"));

        if (booking.getStatus() != BookingStatus.PENDING) {
            throw new ResponseStatusException(BAD_REQUEST, "확정할 수 없는 예약 상태입니다");
        }

        // 좌석 확정 (낙관적 락 사용)
        List<ScheduleSeat> seats = booking.getBookingSeats().stream()
                .map(bs -> bs.getSeat())
                .collect(Collectors.toList());

        // 좌석이 LOCKED 상태에서만 BOOKED로 변경 가능
        for (ScheduleSeat seat : seats) {
            if (seat.getStatus() != ScheduleSeat.SeatStatus.LOCKED) {
                throw new ResponseStatusException(BAD_REQUEST, "확정할 수 없는 좌석 상태입니다");
            }
            // 좌석 상태를 BOOKED로 변경
            seat.setStatus(ScheduleSeat.SeatStatus.BOOKED);
        }

        try {
            // 낙관적 락으로 좌석 상태를 LOCKED에서 BOOKED로 변경
            scheduleSeatRepository.saveAll(seats);
            
            // 예약 상태 변경
            booking.setStatus(BookingStatus.CONFIRMED);
            bookingRepository.save(booking);
        } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
            throw new ResponseStatusException(BAD_REQUEST, "좌석 상태가 변경되었습니다. 다시 시도해주세요.");
        }
    }

    /**
     * 예약 상세 조회 (관리자용 - 소유권 검증 없음)
     */
    @Transactional(readOnly = true)
    public GetBookingDetail200ResponseDto getBookingDetail(Long bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "예매를 찾을 수 없습니다"));
        return toDetailDto(booking);
    }

    /**
     * 사용자 예약 상세 조회 (소유권 검증 포함)
     */
    @Transactional(readOnly = true)
    public GetBookingDetail200ResponseDto getUserBookingDetail(String username, Long bookingId) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED, "사용자 인증 실패"));

        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "예매를 찾을 수 없습니다"));

        // 소유권 검증
        if (!booking.getUser().getUserId().equals(user.getUserId())) {
            throw new ResponseStatusException(FORBIDDEN, "해당 예매에 접근할 권한이 없습니다");
        }

        return toDetailDto(booking);
    }

    /**
     * 예약 목록 조회
     */
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

    /**
     * 예약 취소
     */
    @Transactional(rollbackFor = Exception.class)
    public CancelBooking200ResponseDto cancelBooking(Long bookingId, CancelBookingRequestDto req) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "예매를 찾을 수 없습니다"));

        if (booking.getStatus() == BookingStatus.CANCELLED) {
            throw new ResponseStatusException(BAD_REQUEST, "이미 취소된 예매입니다");
        }

        // 좌석 취소 (SeatService에 위임)
        List<Long> seatIds = booking.getBookingSeats().stream()
                .map(bs -> bs.getSeat().getSeatId())
                .collect(Collectors.toList());

        boolean cancelled = seatService.cancelSeats(seatIds);

        if (!cancelled) {
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, "좌석 취소 실패");
        }

        // 예약 상태 변경
        booking.setStatus(BookingStatus.CANCELLED);
        booking.setCancelledAt(java.time.LocalDateTime.now());
        if (req != null) {
            booking.setCancellationReason(req.getReason());
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

    /**
     * 예약 만료 처리 (스케줄러에서 호출)
     */
    @Transactional(rollbackFor = Exception.class)
    public void expireBooking(Long bookingId) {
        Booking booking = bookingRepository.findById(bookingId)
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "예매를 찾을 수 없습니다"));

        if (booking.getStatus() == BookingStatus.PENDING) {
            // 좌석 락 해제 (SeatService에 위임)
            List<Long> seatIds = booking.getBookingSeats().stream()
                    .map(bs -> bs.getSeat().getSeatId())
                    .collect(Collectors.toList());

            seatService.releaseSeats(seatIds, booking.getUser().getUserId(), null);

            // 예약 취소
            booking.setStatus(BookingStatus.CANCELLED);
            booking.setCancelledAt(java.time.LocalDateTime.now());
            booking.setCancellationReason("결제 시간 만료");
            bookingRepository.save(booking);
        }
    }

    /**
     * 사용자별 예약 목록 조회
     */
    @Transactional(readOnly = true)
    public GetBookings200ResponseDto getUserBookings(String username, String status, int page, int limit) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED, "사용자 인증 실패"));

        PageRequest pr = PageRequest.of(Math.max(page - 1, 0), Math.max(limit, 1));

        Page<Booking> result;
        if (status != null && !status.isBlank()) {
            BookingStatus bs;
            try {
                bs = BookingStatus.valueOf(status.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(BAD_REQUEST, "유효하지 않은 상태 값");
            }
            result = bookingRepository.findByUserAndStatus(user, bs, pr);
        } else {
            result = bookingRepository.findByUser(user, pr);
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

    // === Private Helper Methods (DTO 변환) ===

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
