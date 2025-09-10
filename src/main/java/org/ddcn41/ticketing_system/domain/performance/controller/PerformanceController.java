package org.ddcn41.ticketing_system.domain.performance.controller;

import lombok.RequiredArgsConstructor;
import org.ddcn41.ticketing_system.domain.performance.dto.response.PerformanceResponse;
import org.ddcn41.ticketing_system.domain.performance.entity.Performance;
import org.ddcn41.ticketing_system.domain.performance.service.PerformanceService;
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
        List<Performance> performances = performanceService.getAllPerformances();
        List<PerformanceResponse> responses = performances.stream()
                .map(PerformanceResponse::from)
                .collect(Collectors.toList());

        return ResponseEntity.ok(responses);
    }

    @GetMapping("/{performanceId}")
    public ResponseEntity<PerformanceResponse> getPerformanceById(@PathVariable long performanceId){
        Performance performance = performanceService.getPerformanceById(performanceId);
        return ResponseEntity.ok(PerformanceResponse.from(performance));
    }

    @GetMapping("/search")
    public ResponseEntity<List<PerformanceResponse>> searchPerformances(
            @RequestParam(required = false, defaultValue = "") String name,
            @RequestParam(required = false, defaultValue = "") String venue,
            @RequestParam(required = false, defaultValue = "") String status) {

        List<Performance> performances = performanceService.searchPerformances(name, venue, status);
        List<PerformanceResponse> responses = performances.stream()
                .map(PerformanceResponse::from)
                .collect(Collectors.toList());

        return ResponseEntity.ok(responses);}





}
