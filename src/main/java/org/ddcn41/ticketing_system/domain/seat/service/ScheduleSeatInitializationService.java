package org.ddcn41.ticketing_system.domain.seat.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.ddcn41.ticketing_system.domain.performance.entity.PerformanceSchedule;
import org.ddcn41.ticketing_system.domain.performance.repository.PerformanceScheduleRepository;
import org.ddcn41.ticketing_system.domain.seat.dto.response.InitializeSeatsResponse;
import org.ddcn41.ticketing_system.domain.seat.entity.ScheduleSeat;
import org.ddcn41.ticketing_system.domain.seat.repository.ScheduleSeatRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
public class ScheduleSeatInitializationService {

    private final PerformanceScheduleRepository scheduleRepository;
    private final ScheduleSeatRepository scheduleSeatRepository;
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 모든 스케줄에 대해 좌석 초기화 수행
     */
    @Transactional
    public List<InitializeSeatsResponse> initializeAll(boolean dryRun) {
        List<PerformanceSchedule> schedules = scheduleRepository.findAll();
        List<InitializeSeatsResponse> results = new ArrayList<>();
        for (PerformanceSchedule s : schedules) {
            try {
                results.add(initialize(s.getScheduleId(), dryRun));
            } catch (RuntimeException ex) {
                // 개별 스케줄 실패는 전체 중단 없이 계속 진행
                results.add(InitializeSeatsResponse.builder()
                        .scheduleId(s.getScheduleId())
                        .created(0)
                        .total(Math.toIntExact(scheduleSeatRepository.countBySchedule_ScheduleId(s.getScheduleId())))
                        .available(scheduleSeatRepository.countAvailableSeatsByScheduleId(s.getScheduleId()))
                        .dryRun(dryRun)
                        .build());
            }
        }
        return results;
    }

    @Transactional
    public InitializeSeatsResponse initialize(Long scheduleId, boolean dryRun) {
        PerformanceSchedule schedule = scheduleRepository.findById(scheduleId)
                .orElseThrow(() -> new IllegalArgumentException("스케줄을 찾을 수 없습니다: " + scheduleId));

        if (schedule.getPerformance() == null || schedule.getPerformance().getVenue() == null) {
            throw new IllegalArgumentException("스케줄에 공연장 정보가 없습니다: " + scheduleId);
        }

        String seatMapJson = schedule.getPerformance().getVenue().getSeatMapJson();
        if (seatMapJson == null || seatMapJson.isBlank()) {
            throw new IllegalArgumentException("공연장의 좌석 맵 JSON이 비어있습니다");
        }

        JsonNode root;
        try {
            root = objectMapper.readTree(seatMapJson);
        } catch (IOException e) {
            throw new IllegalArgumentException("좌석 맵 JSON 파싱 실패", e);
        }

        JsonNode sections = root.path("sections");
        // pricing map: grade -> price
        java.util.Map<String, java.math.BigDecimal> pricing = new java.util.HashMap<>();
        JsonNode pricingNode = root.path("pricing");
        if (pricingNode.isObject()) {
            java.util.Iterator<java.util.Map.Entry<String, JsonNode>> it = pricingNode.fields();
            while (it.hasNext()) {
                java.util.Map.Entry<String, JsonNode> e = it.next();
                try {
                    pricing.put(e.getKey(), new java.math.BigDecimal(e.getValue().asText()));
                } catch (Exception ignored) {}
            }
        }
        if (!sections.isArray()) {
            throw new IllegalArgumentException("좌석 맵 JSON의 sections 형식이 올바르지 않습니다");
        }

        int created = 0;
        List<ScheduleSeat> newBatch = new ArrayList<>();
        List<ScheduleSeat> updateBatch = new ArrayList<>();

        // 기존 좌석 유지: 존재하는 좌석은 그대로 두고, 없는 좌석만 생성
        List<ScheduleSeat> existingSeats = scheduleSeatRepository.findBySchedule_ScheduleId(scheduleId);
        java.util.Map<String, ScheduleSeat> existingMap = new java.util.HashMap<>();
        for (ScheduleSeat s : existingSeats) {
            existingMap.put(key(s.getZone(), s.getRowLabel(), s.getColNum()), s);
        }
        long existingTotal = existingSeats.size();
        int existingAvailable = scheduleSeatRepository.countAvailableSeatsByScheduleId(scheduleId);

        for (JsonNode sec : sections) {
            String zone = textOrNull(sec, "zone");
            String grade = textOrNull(sec, "grade");
            int rows = intOrDefault(sec, "rows", 0);
            int cols = intOrDefault(sec, "cols", 0);
            String rowLabelFrom = textOrNull(sec, "rowLabelFrom");
            int seatStart = intOrDefault(sec, "seatStart", 1);

            if (rows <= 0 || cols <= 0 || rowLabelFrom == null || rowLabelFrom.isBlank()) {
                continue; // 불완전 섹션은 스킵
            }

            for (int r = 0; r < rows; r++) {
                String rowLabel = incrementAlpha(rowLabelFrom, r);
                for (int c = 0; c < cols; c++) {
                    String colNum = String.valueOf(seatStart + c);
                    java.math.BigDecimal price = pricing.getOrDefault(grade == null ? "" : grade, java.math.BigDecimal.ZERO);
                    String k = key(zone, rowLabel, colNum);
                    ScheduleSeat existingSeat = existingMap.get(k);
                    if (existingSeat != null) {
                        // 기존 좌석: 가격만 JSON pricing으로 동기화(필요 시)
                        if (price != null) {
                            java.math.BigDecimal current = existingSeat.getPrice() == null ? java.math.BigDecimal.ZERO : existingSeat.getPrice();
                            if (current.compareTo(price) != 0) {
                                existingSeat.setPrice(price);
                                if (!dryRun) updateBatch.add(existingSeat);
                            }
                        }
                        // 생성 카운트 증가 없음
                    } else {
                        // 신규 좌석 생성
                        ScheduleSeat seat = ScheduleSeat.builder()
                                .schedule(schedule)
                                .grade(grade == null ? "" : grade)
                                .zone(zone)
                                .rowLabel(rowLabel)
                                .colNum(colNum)
                                .price(price)
                                .build();

                        if (!dryRun) {
                            newBatch.add(seat);
                            if (newBatch.size() >= 500) {
                                scheduleSeatRepository.saveAll(newBatch);
                                newBatch.clear();
                            }
                        }
                        created++;
                    }
                }
            }
        }

        if (!dryRun) {
            if (!newBatch.isEmpty()) {
                scheduleSeatRepository.saveAll(newBatch);
            }
            if (!updateBatch.isEmpty()) {
                scheduleSeatRepository.saveAll(updateBatch);
            }
        }

        // 카운터 재계산 및 반영
        long total;
        int available;
        if (dryRun) {
            total = existingTotal + created;
            available = existingAvailable + created; // 신규 좌석은 AVAILABLE로 시작
        } else {
            total = scheduleSeatRepository.countBySchedule_ScheduleId(scheduleId);
            available = scheduleSeatRepository.countAvailableSeatsByScheduleId(scheduleId);
            schedule.setTotalSeats(Math.toIntExact(total));
            schedule.setAvailableSeats(available);
            scheduleRepository.save(schedule);
            scheduleRepository.refreshScheduleStatus(scheduleId);
        }

        return InitializeSeatsResponse.builder()
                .scheduleId(scheduleId)
                .created(created)
                .total(Math.toIntExact(total))
                .available(available)
                .dryRun(dryRun)
                .build();
    }

    private static String key(String zone, String rowLabel, String colNum) {
        return (zone == null ? "" : zone) + "|" + (rowLabel == null ? "" : rowLabel) + "|" + (colNum == null ? "" : colNum);
    }

    private static String textOrNull(JsonNode node, String field) {
        JsonNode n = node.get(field);
        return n == null || n.isNull() ? null : n.asText();
    }

    private static int intOrDefault(JsonNode node, String field, int def) {
        JsonNode n = node.get(field);
        return n == null || !n.canConvertToInt() ? def : n.asInt();
    }

    // A..Z, AA..AZ, BA.. 증가
    private static String incrementAlpha(String start, int offset) {
        String base = start.toUpperCase();
        int value = alphaToInt(base) + offset;
        return intToAlpha(value);
    }

    private static int alphaToInt(String s) {
        int v = 0;
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if (ch < 'A' || ch > 'Z') throw new IllegalArgumentException("Invalid row label: " + s);
            v = v * 26 + (ch - 'A' + 1);
        }
        return v - 1; // zero-based
    }

    private static String intToAlpha(int v) {
        v = v + 1; // one-based
        StringBuilder sb = new StringBuilder();
        while (v > 0) {
            int rem = (v - 1) % 26;
            sb.append((char) ('A' + rem));
            v = (v - 1) / 26;
        }
        return sb.reverse().toString();
    }
}
