package org.ddcn41.ticketing_system.domain.booking.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
import org.ddcn41.ticketing_system.domain.queue.entity.QueueToken;
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
import org.ddcn41.ticketing_system.domain.queue.service.QueueService;


import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.springframework.http.HttpStatus.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class BookingService {

    private final BookingRepository bookingRepository;
    private final BookingSeatRepository bookingSeatRepository;
    private final PerformanceScheduleRepository scheduleRepository;
    private final ScheduleSeatRepository scheduleSeatRepository;
    private final UserRepository userRepository;

    private final SeatService seatService;
    private final QueueService queueService;

    @Transactional(rollbackFor = Exception.class)
    public CreateBookingResponseDto createBooking(String username, CreateBookingRequestDto req) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED, "사용자 인증 실패"));

        PerformanceSchedule schedule = scheduleRepository.findById(req.getScheduleId())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "스케줄을 찾을 수 없습니다"));

        // ⭐ 대기열 토큰 검증 강화
        if (req.getQueueToken() != null && !req.getQueueToken().trim().isEmpty()) {
            boolean isValidToken = queueService.validateTokenForBooking(
                    req.getQueueToken(), user.getUserId());

            if (!isValidToken) {
                throw new ResponseStatusException(BAD_REQUEST,
                        "유효하지 않은 대기열 토큰입니다. 토큰이 만료되었거나 권한이 없습니다. 대기열을 통해 다시 시도해주세요.");
            }

            // ⭐ 토큰 유효성 재확인 (동시성 이슈 대응)
            try {
                QueueToken queueToken = queueService.getTokenByString(req.getQueueToken());
                if (!queueToken.isActiveForBooking()) {
                    throw new ResponseStatusException(BAD_REQUEST,
                            "토큰이 예매 가능한 상태가 아닙니다. 시간이 만료되었을 수 있습니다.");
                }
            } catch (Exception e) {
                throw new ResponseStatusException(BAD_REQUEST,
                        "토큰 검증 중 오류가 발생했습니다. 다시 시도해주세요.");
            }

        } else {
            // 토큰이 없는 경우 - 공연별 정책에 따라 처리
            throw new ResponseStatusException(BAD_REQUEST,
                    "대기열 토큰이 필요합니다. 대기열에 참여해주세요.");
        }

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

        // 예매 완료 시 토큰 사용 처리
        try {
            if (req.getQueueToken() != null && !req.getQueueToken().trim().isEmpty()) {
                queueService.useToken(req.getQueueToken());
                log.info("토큰 사용 완료 - 사용자: {}, 토큰: {}", username, req.getQueueToken());
            }
        } catch (Exception e) {
            log.warn("토큰 사용 처리 중 오류 발생: {}", e.getMessage());
            // 예매는 완료되었으므로 로그만 남기고 계속 진행
        }


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
        // JOIN FETCH로 연관 엔티티들을 한 번에 로딩
        Booking booking = bookingRepository.findByIdWithSeats(bookingId);
        if (booking == null) {
            throw new ResponseStatusException(NOT_FOUND, "예매를 찾을 수 없습니다");
        }

        if (booking.getStatus() != BookingStatus.PENDING) {
            throw new ResponseStatusException(BAD_REQUEST, "확정할 수 없는 예약 상태입니다");
        }

        // 좌석 확정 (낙관적 락 사용)
        List<ScheduleSeat> seats = booking.getBookingSeats().stream()
                .map(bs -> bs.getSeat())
                .collect(Collectors.toList());

        // 좌석 확정 로직 (LOCKED 또는 AVAILABLE 상태에서 BOOKED로 변경 가능)
        for (ScheduleSeat seat : seats) {
            if (seat.getStatus() == ScheduleSeat.SeatStatus.BOOKED) {
                throw new ResponseStatusException(BAD_REQUEST, 
                    String.format("이미 예약된 좌석입니다. 좌석ID: %d", seat.getSeatId()));
            }
            
            // LOCKED 또는 AVAILABLE 상태에서 BOOKED로 변경
            if (seat.getStatus() != ScheduleSeat.SeatStatus.LOCKED && 
                seat.getStatus() != ScheduleSeat.SeatStatus.AVAILABLE) {
                throw new ResponseStatusException(BAD_REQUEST, 
                    String.format("확정할 수 없는 좌석 상태입니다. 좌석ID: %d, 현재상태: %s", 
                        seat.getSeatId(), seat.getStatus()));
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
            
            // performance_schedules의 available_seats 감소
            PerformanceSchedule schedule = booking.getSchedule();
            schedule.setAvailableSeats(schedule.getAvailableSeats() - booking.getSeatCount());
            scheduleRepository.save(schedule);
        } catch (org.springframework.orm.ObjectOptimisticLockingFailureException e) {
            throw new ResponseStatusException(BAD_REQUEST, "좌석 상태가 변경되었습니다. 다시 시도해주세요.");
        } catch (Exception e) {
            // 모든 예외를 로깅하고 적절한 메시지 반환
            throw new ResponseStatusException(INTERNAL_SERVER_ERROR, 
                "예약 확정 중 오류가 발생했습니다: " + e.getMessage(), e);
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
     * 예약 목록 조회 (DTO Projection 사용 - 성능 최적화)
     */
    @Transactional(readOnly = true)
    public GetBookings200ResponseDto getBookings(String status, int page, int limit) {
        PageRequest pr = PageRequest.of(Math.max(page - 1, 0), Math.max(limit, 1));

        Page<org.ddcn41.ticketing_system.domain.booking.dto.BookingProjection> result;
        if (status != null && !status.isBlank()) {
            BookingStatus bs;
            try {
                bs = BookingStatus.valueOf(status.toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new ResponseStatusException(BAD_REQUEST, "유효하지 않은 상태 값");
            }
            result = bookingRepository.findAllByStatusWithDetails(bs, pr);
        } else {
            result = bookingRepository.findAllWithDetails(pr);
        }

        List<BookingDto> items = result.getContent().stream()
                .map(this::toListDtoFromProjection)
                .collect(Collectors.toList());

        return GetBookings200ResponseDto.builder()
                .bookings(items)
                .total(Math.toIntExact(result.getTotalElements()))
                .page(page)
                .build();
    }

    /**
     * 예약 목록 조회 (기존 Entity 방식 - 하위 호환용)
     */
    @Transactional(readOnly = true)
    public GetBookings200ResponseDto getBookingsLegacy(String status, int page, int limit) {
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

        // performance_schedules의 available_seats 증가 (확정된 예약만)
        boolean wasConfirmed = booking.getStatus() == BookingStatus.CONFIRMED;
        
        // 예약 상태 변경
        booking.setStatus(BookingStatus.CANCELLED);
        booking.setCancelledAt(java.time.LocalDateTime.now());
        if (req != null) {
            booking.setCancellationReason(req.getReason());
        }

        bookingRepository.save(booking);

        // 확정된 예약이었다면 available_seats 증가
        if (wasConfirmed) {
            PerformanceSchedule schedule = booking.getSchedule();
            schedule.setAvailableSeats(schedule.getAvailableSeats() + booking.getSeatCount());
            scheduleRepository.save(schedule);
        }

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

    /**
     * BookingProjection을 BookingDto로 변환 (성능 최적화)
     */
    private BookingDto toListDtoFromProjection(org.ddcn41.ticketing_system.domain.booking.dto.BookingProjection p) {
        return BookingDto.builder()
                .bookingId(p.getBookingId())
                .bookingNumber(p.getBookingNumber())
                .userId(p.getUserId())
                .userName(p.getUserName())
                .userPhone(p.getUserPhone())
                .scheduleId(p.getScheduleId())
                .performanceTitle(p.getPerformanceTitle())
                .venueName(p.getVenueName())
                .showDate(odt(p.getShowDatetime()))
                .seatCode(p.getSeatCode())
                .seatZone(p.getSeatZone())
                .seatCount(p.getSeatCount())
                .totalAmount(p.getTotalAmount() == null ? 0.0 : p.getTotalAmount().doubleValue())
                .status(p.getStatus() == null ? null : BookingDto.StatusEnum.valueOf(p.getStatus()))
                .expiresAt(odt(p.getExpiresAt()))
                .bookedAt(odt(p.getBookedAt()))
                .cancelledAt(odt(p.getCancelledAt()))
                .cancellationReason(p.getCancellationReason())
                .createdAt(odt(p.getCreatedAt()))
                .updatedAt(odt(p.getUpdatedAt()))
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
        // Get first seat info for display
        String seatCode = null;
        String seatZone = null;
        if (b.getBookingSeats() != null && !b.getBookingSeats().isEmpty()) {
            var scheduleSeat = b.getBookingSeats().get(0).getSeat();
            if (scheduleSeat != null && scheduleSeat.getVenueSeat() != null) {
                var venueSeat = scheduleSeat.getVenueSeat();
                seatCode = venueSeat.getSeatRow() + venueSeat.getSeatNumber();
                seatZone = venueSeat.getSeatZone();
            }
        }
        
        return BookingDto.builder()
                .bookingId(b.getBookingId())
                .bookingNumber(b.getBookingNumber())
                .userId(b.getUser() != null ? b.getUser().getUserId() : null)
                .userName(b.getUser() != null ? b.getUser().getName() : null)
                .userPhone(b.getUser() != null ? b.getUser().getPhone() : null)
                .scheduleId(b.getSchedule() != null ? b.getSchedule().getScheduleId() : null)
                .performanceTitle(b.getSchedule() != null && b.getSchedule().getPerformance() != null ? b.getSchedule().getPerformance().getTitle() : null)
                .venueName(b.getSchedule() != null && b.getSchedule().getPerformance() != null && b.getSchedule().getPerformance().getVenue() != null ? b.getSchedule().getPerformance().getVenue().getVenueName() : null)
                .showDate(b.getSchedule() != null ? odt(b.getSchedule().getShowDatetime()) : null)
                .seatCode(seatCode)
                .seatZone(seatZone)
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
        // Get first seat info for display
        String seatCode = null;
        String seatZone = null;
        if (b.getBookingSeats() != null && !b.getBookingSeats().isEmpty()) {
            var scheduleSeat = b.getBookingSeats().get(0).getSeat();
            if (scheduleSeat != null && scheduleSeat.getVenueSeat() != null) {
                var venueSeat = scheduleSeat.getVenueSeat();
                seatCode = venueSeat.getSeatRow() + venueSeat.getSeatNumber();
                seatZone = venueSeat.getSeatZone();
            }
        }
        
        return GetBookingDetail200ResponseDto.builder()
                .bookingId(b.getBookingId())
                .bookingNumber(b.getBookingNumber())
                .userId(b.getUser() != null ? b.getUser().getUserId() : null)
                .userName(b.getUser() != null ? b.getUser().getName() : null)
                .userPhone(b.getUser() != null ? b.getUser().getPhone() : null)
                .scheduleId(b.getSchedule() != null ? b.getSchedule().getScheduleId() : null)
                .performanceTitle(b.getSchedule() != null && b.getSchedule().getPerformance() != null ? b.getSchedule().getPerformance().getTitle() : null)
                .venueName(b.getSchedule() != null && b.getSchedule().getPerformance() != null && b.getSchedule().getPerformance().getVenue() != null ? b.getSchedule().getPerformance().getVenue().getVenueName() : null)
                .showDate(b.getSchedule() != null ? odt(b.getSchedule().getShowDatetime()) : null)
                .seatCode(seatCode)
                .seatZone(seatZone)
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
