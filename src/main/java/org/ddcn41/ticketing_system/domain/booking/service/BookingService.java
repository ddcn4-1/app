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
import org.ddcn41.ticketing_system.domain.queue.service.QueueService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static org.springframework.http.HttpStatus.*;

@Service
@RequiredArgsConstructor
public class BookingService {

    private static final Logger log = LoggerFactory.getLogger(BookingService.class);

    private final BookingRepository bookingRepository;
    private final BookingSeatRepository bookingSeatRepository;
    private final PerformanceScheduleRepository scheduleRepository;
    private final ScheduleSeatRepository scheduleSeatRepository;
    private final UserRepository userRepository;

    private final SeatService seatService;
    private final BookingAuditService bookingAuditService;
    private final QueueService queueService;

    @Transactional(rollbackFor = Exception.class)
    public CreateBookingResponseDto createBooking(String username, CreateBookingRequestDto req) {
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(UNAUTHORIZED, "사용자 인증 실패"));

        PerformanceSchedule schedule = scheduleRepository.findById(req.getScheduleId())
                .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "스케줄을 찾을 수 없습니다"));

        // 대기열 토큰 검증 추가 (기존 seat_map_json 로직 이전에)
        validateQueueTokenIfRequired(req, user, schedule);


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
                .status(BookingStatus.CONFIRMED)
                .build();

        Booking saved = bookingRepository.save(booking);

        // 예매 완료 시 토큰 사용 처리
        if (req.getQueueToken() != null && !req.getQueueToken().trim().isEmpty()) {
            try {
                queueService.useToken(req.getQueueToken());
                // log.info("토큰 사용 완료 - 사용자: {}, 토큰: {}", username, req.getQueueToken());
            } catch (Exception e) {
                // log.warn("토큰 사용 처리 중 오류 발생: {}", e.getMessage());
                // 예매는 완료되었으므로 로그만 남기고 계속 진행
            }
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

        // 좌석을 최종 예약 상태로 전환 (이미 LOCKED 상태까지 검증 및 카운터 반영 완료)
        seats.forEach(seat -> seat.setStatus(ScheduleSeat.SeatStatus.BOOKED));
        scheduleSeatRepository.saveAll(seats);

        List<Long> seatIds = seats.stream().map(ScheduleSeat::getSeatId).collect(Collectors.toList());
        bookingAuditService.logBookingCreated(user, saved, seatIds);

        return toCreateResponse(saved);
    }
    
    /**
     * 대기열 토큰 검증 - schedule 파라미터 추가
     */
    private void validateQueueTokenIfRequired(CreateBookingRequestDto req, User user, PerformanceSchedule schedule) {
        if (req.getQueueToken() != null && !req.getQueueToken().trim().isEmpty()) {
            boolean isValidToken = queueService.validateTokenForBooking(
                    req.getQueueToken(),
                    user.getUserId(),
                    schedule.getPerformance().getPerformanceId()
            );

           if (!isValidToken) {
               throw new ResponseStatusException(BAD_REQUEST,
                       "유효하지 않은 대기열 토큰입니다. 토큰이 만료되었거나 다른 공연의 토큰입니다. 대기열을 통해 다시 시도해주세요.");
           }

            // 토큰 유효성 재확인 (동시성 이슈 대응)
            try {
                if (!queueService.isTokenActiveForBooking(req.getQueueToken())) {
                    throw new ResponseStatusException(BAD_REQUEST,
                            "토큰이 예매 가능한 상태가 아닙니다. 시간이 만료되었을 수 있습니다.");
                }
            } catch (Exception e) {
                log.warn("토큰 검증 중 오류 발생: {}", e.getMessage());
                throw new ResponseStatusException(BAD_REQUEST,
                        "토큰 검증 중 오류가 발생했습니다. 다시 시도해주세요.");
            }
        } else {
            // 토큰이 없는 경우 - 공연별 정책에 따라 처리
            throw new ResponseStatusException(BAD_REQUEST,
                    "대기열 토큰이 필요합니다. 대기열에 참여해주세요.");
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
    public CancelBooking200ResponseDto cancelBooking(Long bookingId, CancelBookingRequestDto req, String actorUsername) {
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

        CancelBooking200ResponseDto response = CancelBooking200ResponseDto.builder()
                .message("예매 취소 성공")
                .bookingId(booking.getBookingId())
                .status(BookingStatus.CANCELLED.name())
                .cancelledAt(odt(booking.getCancelledAt()))
                .refundAmount(booking.getTotalAmount() == null ? 0.0 : booking.getTotalAmount().doubleValue())
                .build();

        bookingAuditService.logBookingCancelled(actorUsername, booking, req != null ? req.getReason() : null);
        return response;
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
        List<BookingSeatDto> seatDtos = new ArrayList<>();
        if (p.getBookingSeatId() != null) {
            seatDtos.add(BookingSeatDto.builder()
                    .bookingSeatId(p.getBookingSeatId())
                    .bookingId(p.getBookingId())
                    .seatId(null)
                    .seatPrice(p.getSeatPrice() == null ? 0.0 : p.getSeatPrice().doubleValue())
                    .grade(p.getSeatGrade())
                    .zone(p.getSeatZone())
                    .rowLabel(p.getSeatRowLabel())
                    .colNum(p.getSeatColNum())
                    .createdAt(null)
                    .build());
        }

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
                .seats(seatDtos.isEmpty() ? List.of() : seatDtos)
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
        ScheduleSeat scheduleSeat = bs.getSeat();
        return BookingSeatDto.builder()
                .bookingSeatId(bs.getBookingSeatId())
                .bookingId(bs.getBooking() != null ? bs.getBooking().getBookingId() : null)
                .seatId(scheduleSeat != null ? scheduleSeat.getSeatId() : null)
                .seatPrice(bs.getSeatPrice() == null ? 0.0 : bs.getSeatPrice().doubleValue())
                .grade(scheduleSeat != null ? scheduleSeat.getGrade() : null)
                .zone(scheduleSeat != null ? scheduleSeat.getZone() : null)
                .rowLabel(scheduleSeat != null ? scheduleSeat.getRowLabel() : null)
                .colNum(scheduleSeat != null ? scheduleSeat.getColNum() : null)
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
                .seats(b.getBookingSeats() == null ? List.of() : b.getBookingSeats().stream().map(this::toSeatDto).collect(Collectors.toList()))
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
                String rowLabel = scheduleSeat.getRowLabel();
                String colNum = scheduleSeat.getColNum();
                if (rowLabel != null && colNum != null) {
                    seatCode = rowLabel + colNum;
                }
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

    private static String safeUpper(String value) {
        return value == null ? null : value.trim().toUpperCase();
    }

    private static boolean validateBySeatMap(JsonNode sections, String grade, String zone, String rowLabel, String colNum) {
        if (!sections.isArray() || rowLabel == null || colNum == null) {
            return false;
        }

        String normalizedRow = rowLabel.trim().toUpperCase();
        String normalizedCol = colNum.trim();
        String normalizedGrade = grade == null ? null : grade.trim().toUpperCase();
        String normalizedZone = zone == null ? null : zone.trim().toUpperCase();

        for (JsonNode section : sections) {
            int rows = section.path("rows").asInt(0);
            int cols = section.path("cols").asInt(0);
            if (rows <= 0 || cols <= 0) {
                continue;
            }

            String sectionRowStart = safeUpper(textOrNull(section, "rowLabelFrom"));
            if (sectionRowStart == null || sectionRowStart.isBlank()) {
                continue;
            }

            String sectionGrade = safeUpper(textOrNull(section, "grade"));
            String sectionZone = safeUpper(textOrNull(section, "zone"));

            if (normalizedGrade != null && !normalizedGrade.isBlank() && !normalizedGrade.equals(sectionGrade)) {
                continue;
            }
            if (normalizedZone != null && !normalizedZone.isBlank() && !normalizedZone.equals(sectionZone)) {
                continue;
            }

            int seatStart = section.path("seatStart").asInt(1);

            for (int r = 0; r < rows; r++) {
                String currentRow = incrementAlpha(sectionRowStart, r);
                if (!currentRow.equals(normalizedRow)) {
                    continue;
                }

                for (int c = 0; c < cols; c++) {
                    String currentCol = String.valueOf(seatStart + c);
                    if (currentCol.equals(normalizedCol)) {
                        return true;
                    }
                }
            }
        }

        return false;
    }

    private static BigDecimal priceByGrade(JsonNode pricingNode, String grade) {
        if (pricingNode == null || !pricingNode.isObject() || grade == null) {
            return null;
        }

        JsonNode direct = pricingNode.get(grade);
        if (direct != null) {
            try {
                return new BigDecimal(direct.asText());
            } catch (NumberFormatException ignored) {
                return null;
            }
        }

        String normalizedGrade = grade.trim().toUpperCase();
        java.util.Iterator<java.util.Map.Entry<String, JsonNode>> fields = pricingNode.fields();
        while (fields.hasNext()) {
            java.util.Map.Entry<String, JsonNode> entry = fields.next();
            if (normalizedGrade.equals(entry.getKey().trim().toUpperCase())) {
                try {
                    return new BigDecimal(entry.getValue().asText());
                } catch (NumberFormatException ignored) {
                    return null;
                }
            }
        }
        return null;
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asText();
    }

    private static String incrementAlpha(String start, int offset) {
        int baseValue = alphaToInt(start) + offset;
        return intToAlpha(baseValue);
    }

    private static int alphaToInt(String s) {
        int value = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch < 'A' || ch > 'Z') {
                throw new IllegalArgumentException("Invalid row label: " + s);
            }
            value = value * 26 + (ch - 'A' + 1);
        }
        return value - 1;
    }

    private static String intToAlpha(int value) {
        value = value + 1;
        StringBuilder sb = new StringBuilder();
        while (value > 0) {
            int remainder = (value - 1) % 26;
            sb.append((char) ('A' + remainder));
            value = (value - 1) / 26;
        }
        return sb.reverse().toString();
    }
}
