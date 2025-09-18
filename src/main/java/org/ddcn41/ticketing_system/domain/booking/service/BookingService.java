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
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

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

        // seat_map_json 파싱 (검증/가격)
        ObjectMapper om = new ObjectMapper();
        JsonNode root;
        try {
            String seatMapJson = schedule.getPerformance().getVenue().getSeatMapJson();
            root = om.readTree(seatMapJson == null ? "{}" : seatMapJson);
        } catch (Exception e) {
            throw new ResponseStatusException(BAD_REQUEST, "좌석 맵 정보가 올바르지 않습니다");
        }

        JsonNode sections = root.path("sections");
        JsonNode pricingNode = root.path("pricing");

        // 좌석 선택을 실제 ScheduleSeat로 매핑 및 검증
        List<ScheduleSeat> requestedSeats = req.getSeats().stream().map(sel -> {
            String grade = safeUpper(sel.getGrade());
            String zone = safeUpper(sel.getZone());
            String rowLabel = safeUpper(sel.getRowLabel());
            String colNum = sel.getColNum();

            boolean validByJson = validateBySeatMap(sections, grade, zone, rowLabel, colNum);
            if (!validByJson) {
                throw new ResponseStatusException(BAD_REQUEST, String.format("유효하지 않은 좌석 지정: %s/%s-%s%s", grade, zone, rowLabel, colNum));
            }

            ScheduleSeat seat = scheduleSeatRepository.findBySchedule_ScheduleIdAndZoneAndRowLabelAndColNum(
                    schedule.getScheduleId(), zone, rowLabel, colNum);
            if (seat == null) {
                throw new ResponseStatusException(BAD_REQUEST, String.format("존재하지 않는 좌석: %s/%s-%s%s", grade, zone, rowLabel, colNum));
            }
            if (!safeUpper(seat.getGrade()).equals(grade) || !safeUpper(seat.getZone()).equals(zone)) {
                throw new ResponseStatusException(BAD_REQUEST, "좌석의 등급/구역 정보가 요청과 일치하지 않습니다");
            }
            BigDecimal price = priceByGrade(pricingNode, grade);
            if (price != null) {
                seat.setPrice(price);
            }
            if (seat.getStatus() != ScheduleSeat.SeatStatus.AVAILABLE) {
                throw new ResponseStatusException(BAD_REQUEST, "예약 불가능한 좌석이 포함되어 있습니다: " + seat.getSeatId());
            }
            seat.setStatus(ScheduleSeat.SeatStatus.LOCKED);
            return seat;
        }).collect(Collectors.toList());

        // 낙관적 락 검증을 커밋 전에 강제 수행 (flush 시 버전 충돌 발생 가능)
        // NOTE: 커밋 시 자동 flush로도 충분하면 이 flush는 생략 가능
        try {
            scheduleSeatRepository.saveAll(requestedSeats);
            scheduleSeatRepository.flush();
            // 좌석을 AVAILABLE -> LOCKED로 변경한 수만큼 가용 좌석 카운터 감소
            if (!requestedSeats.isEmpty()) {
                int affected = scheduleRepository.decrementAvailableSeats(req.getScheduleId(), requestedSeats.size());
                if (affected == 0) {
                    throw new ResponseStatusException(BAD_REQUEST, "잔여 좌석 수가 부족합니다. 다시 시도해주세요.");
                }
                scheduleRepository.refreshScheduleStatus(req.getScheduleId());
            }
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
            // 낙관적 락 검증을 커밋 전에 강제 수행 (flush 시 버전 충돌 발생 가능)
            // NOTE: 커밋 시 자동 flush로도 충분하면 이 flush는 생략 가능
            scheduleSeatRepository.flush();

            // 예약 상태 변경 (가용 좌석 카운터는 LOCK 시점에 감소했으므로 추가 감소 없음)
            booking.setStatus(BookingStatus.CONFIRMED);
            // NOTE: booking은 영속 엔티티이므로 변경감지로 커밋 시 자동 반영됩니다. 명시 저장이 필요 없으면 생략 가능
            // bookingRepository.save(booking);
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

        // 동일 예약에 대해 좌석이 여러 행으로 반환될 수 있으므로 bookingId로 그룹화하여 좌석 코드를 누적
        var grouped = new java.util.LinkedHashMap<Long, BookingDto>();
        for (org.ddcn41.ticketing_system.domain.booking.dto.BookingProjection p : result.getContent()) {
            BookingDto dto = grouped.get(p.getBookingId());
            if (dto == null) {
                dto = BookingDto.builder()
                        .bookingId(p.getBookingId())
                        .bookingNumber(p.getBookingNumber())
                        .userId(p.getUserId())
                        .userName(p.getUserName())
                        .userPhone(p.getUserPhone())
                        .scheduleId(p.getScheduleId())
                        .performanceTitle(p.getPerformanceTitle())
                        .venueName(p.getVenueName())
                        .showDate(odt(p.getShowDatetime()))
                        .seatCount(p.getSeatCount())
                        .totalAmount(p.getTotalAmount() == null ? 0.0 : p.getTotalAmount().doubleValue())
                        .status(p.getStatus() == null ? null : BookingDto.StatusEnum.valueOf(p.getStatus()))
                        .expiresAt(odt(p.getExpiresAt()))
                        .bookedAt(odt(p.getBookedAt()))
                        .cancelledAt(odt(p.getCancelledAt()))
                        .cancellationReason(p.getCancellationReason())
                        .createdAt(odt(p.getCreatedAt()))
                        .updatedAt(odt(p.getUpdatedAt()))
                        .seats(new java.util.ArrayList<>())
                        .build();
                grouped.put(p.getBookingId(), dto);
            }
            // 좌석 상세 추가 (존재하는 행에 한해서)
            if (p.getBookingSeatId() != null) {
                BookingSeatDto seatDto = BookingSeatDto.builder()
                        .bookingSeatId(p.getBookingSeatId())
                        .bookingId(p.getBookingId())
                        .seatId(null) // projection에 seatId 미포함 -> 필요시 추가
                        .seatPrice(p.getSeatPrice() == null ? 0.0 : p.getSeatPrice().doubleValue())
                        .grade(p.getSeatGrade())
                        .zone(p.getSeatZone())
                        .rowLabel(p.getSeatRowLabel())
                        .colNum(p.getSeatColNum())
                        .createdAt(null)
                        .build();
                dto.getSeats().add(seatDto);
            }
        }

        List<BookingDto> items = new java.util.ArrayList<>(grouped.values());

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

        // available_seats 증감은 SeatService.cancelSeats 내부에서 BOOKED -> AVAILABLE 전이 시 처리됨
        
        // 예약 상태 변경
        booking.setStatus(BookingStatus.CANCELLED);
        booking.setCancelledAt(java.time.LocalDateTime.now());
        if (req != null) {
            booking.setCancellationReason(req.getReason());
        }

        // NOTE: 영속 엔티티이므로 변경감지로 커밋 시 자동 반영됨. 명시 저장이 필요 없으면 생략 가능
        // bookingRepository.save(booking);

        // 확정된 예약이었다면 좌석 상태를 AVAILABLE로 되돌리는 cancelSeats에서 카운터 증가 처리 완료
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
            // NOTE: 영속 엔티티이므로 변경감지로 커밋 시 자동 반영됨. 명시 저장이 필요 없으면 생략 가능
            // bookingRepository.save(booking);
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
                .seatCount(p.getSeatCount())
                .totalAmount(p.getTotalAmount() == null ? 0.0 : p.getTotalAmount().doubleValue())
                .status(p.getStatus() == null ? null : BookingDto.StatusEnum.valueOf(p.getStatus()))
                .expiresAt(odt(p.getExpiresAt()))
                .bookedAt(odt(p.getBookedAt()))
                .cancelledAt(odt(p.getCancelledAt()))
                .cancellationReason(p.getCancellationReason())
                .createdAt(odt(p.getCreatedAt()))
                .updatedAt(odt(p.getUpdatedAt()))
                .seats(new java.util.ArrayList<>())
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
                .grade(bs.getSeat() != null ? bs.getSeat().getGrade() : null)
                .zone(bs.getSeat() != null ? bs.getSeat().getZone() : null)
                .rowLabel(bs.getSeat() != null ? bs.getSeat().getRowLabel() : null)
                .colNum(bs.getSeat() != null ? bs.getSeat().getColNum() : null)
                .createdAt(odt(bs.getCreatedAt()))
                .build();
    }

    private BookingDto toListDto(Booking b) {
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
                .seatCount(b.getSeatCount())
                .totalAmount(b.getTotalAmount() == null ? 0.0 : b.getTotalAmount().doubleValue())
                .seats(b.getBookingSeats() == null ? java.util.List.of() : b.getBookingSeats().stream().map(this::toSeatDto).collect(java.util.stream.Collectors.toList()))
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
            if (scheduleSeat != null) {
                seatCode = scheduleSeat.getRowLabel() + scheduleSeat.getColNum();
                seatZone = scheduleSeat.getZone();
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

    // === Private Helpers for seat-map validation and pricing ===
    private static String safeUpper(String s) {
        return s == null ? null : s.trim().toUpperCase();
    }

    private static BigDecimal priceByGrade(com.fasterxml.jackson.databind.JsonNode pricingNode, String grade) {
        if (pricingNode != null && pricingNode.isObject() && grade != null) {
            com.fasterxml.jackson.databind.JsonNode p = pricingNode.get(grade);
            if (p != null && !p.isNull()) {
                try { return new BigDecimal(p.asText()); } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private static boolean validateBySeatMap(com.fasterxml.jackson.databind.JsonNode sections, String grade, String zone, String rowLabel, String colNum) {
        if (sections == null || !sections.isArray()) return false;
        int col;
        try { col = Integer.parseInt(colNum); } catch (Exception e) { return false; }
        for (com.fasterxml.jackson.databind.JsonNode sec : sections) {
            String g = safeUpper(textOrNull(sec, "grade"));
            String z = safeUpper(textOrNull(sec, "zone"));
            if (!grade.equals(g) || !zone.equals(z)) continue;
            String rowLabelFrom = safeUpper(textOrNull(sec, "rowLabelFrom"));
            int rows = intOrDefault(sec, "rows", 0);
            int cols = intOrDefault(sec, "cols", 0);
            int seatStart = intOrDefault(sec, "seatStart", 1);
            if (rows <= 0 || cols <= 0 || rowLabelFrom == null) continue;
            Integer rowIdx = rowIndex(rowLabelFrom, rowLabel);
            if (rowIdx == null) continue;
            if (rowIdx >= 0 && rowIdx < rows && col >= seatStart && col < seatStart + cols) {
                return true;
            }
        }
        return false;
    }

    private static String textOrNull(com.fasterxml.jackson.databind.JsonNode node, String field) {
        com.fasterxml.jackson.databind.JsonNode n = node.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }
    private static int intOrDefault(com.fasterxml.jackson.databind.JsonNode node, String field, int def) {
        com.fasterxml.jackson.databind.JsonNode n = node.get(field);
        return n == null || !n.canConvertToInt() ? def : n.asInt();
    }
    private static Integer rowIndex(String from, String target) {
        try {
            int f = alphaToInt(from);
            int t = alphaToInt(target);
            return t - f;
        } catch (Exception e) { return null; }
    }
    private static int alphaToInt(String s) {
        int v = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch < 'A' || ch > 'Z') throw new IllegalArgumentException("Invalid row label: " + s);
            v = v * 26 + (ch - 'A' + 1);
        }
        return v - 1;
    }
}
