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
        if (!sections.isArray()) {
            throw new IllegalArgumentException("좌석 맵 JSON의 sections 형식이 올바르지 않습니다");
        }

        int created = 0;
        List<ScheduleSeat> batch = new ArrayList<>();

        // 초기화: 기존 좌석 제거 후 재생성 (dryRun이면 카운트만 계산)
        long existing = scheduleSeatRepository.countBySchedule_ScheduleId(scheduleId);
        if (!dryRun && existing > 0) {
            scheduleSeatRepository.deleteBySchedule_ScheduleId(scheduleId);
        }

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
                    ScheduleSeat seat = ScheduleSeat.builder()
                            .schedule(schedule)
                            .grade(grade == null ? "" : grade)
                            .zone(zone)
                            .rowLabel(rowLabel)
                            .colNum(colNum)
                            .build();

                    if (!dryRun) {
                        batch.add(seat);
                        if (batch.size() >= 500) {
                            scheduleSeatRepository.saveAll(batch);
                            batch.clear();
                        }
                    }
                    created++;
                }
            }
        }

        if (!dryRun && !batch.isEmpty()) {
            scheduleSeatRepository.saveAll(batch);
        }

        // 카운터 재계산 및 반영
        long total;
        int available;
        if (dryRun) {
            total = created;
            available = created;
        } else {
            total = scheduleSeatRepository.countBySchedule_ScheduleId(scheduleId);
            available = scheduleSeatRepository.countAvailableSeatsByScheduleId(scheduleId);
            schedule.setTotalSeats(Math.toIntExact(total));
            schedule.setAvailableSeats(available);
            scheduleRepository.save(schedule);
        }

        return InitializeSeatsResponse.builder()
                .scheduleId(scheduleId)
                .created(created)
                .total(Math.toIntExact(total))
                .available(available)
                .dryRun(dryRun)
                .build();
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
