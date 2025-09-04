package org.ddcn41.ticketing_system.controller;

import lombok.RequiredArgsConstructor;
import org.ddcn41.ticketing_system.dto.response.PerformanceResponse;
import org.ddcn41.ticketing_system.entity.Performance;
import org.ddcn41.ticketing_system.service.PerformanceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/performances")
@RequiredArgsConstructor
public class PerformanceController {

    private final PerformanceService performanceService;

    @GetMapping
    public ResponseEntity<List<PerformanceResponse>> getAllPerformance(){
        // 구현 예쩡
        return ResponseEntity.ok().build();
    }

    @GetMapping("/{performanceId}")
    public ResponseEntity<PerformanceResponse> getPerformanceById(@PathVariable long performanceId){
        Performance performance = performanceService.getPerformanceById(performanceId);
        return ResponseEntity.ok(PerformanceResponse.from(performance));
    }

    @GetMapping("/search")
    public ResponseEntity<List<PerformanceResponse>> getPerformances(
            @RequestParam(required = false) String title,
            @RequestParam(required = false) String venue,
            @RequestParam(required = false) Performance.PerformanceStatus status) {

        List<Performance> performances = performanceService.searchPerformances(title, venue, status);
        List<PerformanceResponse> responses = performances.stream()
                .map(PerformanceResponse::from)
                .collect(Collectors.toList());

        return ResponseEntity.ok(responses);
    }





}
