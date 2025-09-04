package org.ddcn41.ticketing_system.controller;

import lombok.RequiredArgsConstructor;
import org.ddcn41.ticketing_system.dto.response.PerformanceResponse;
import org.ddcn41.ticketing_system.entity.Performance;
import org.ddcn41.ticketing_system.service.PerformanceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

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
    public ResponseEntity<List<PerformanceResponse>> getPerformances(){
        //구현 예정
        return ResponseEntity.ok().build();
    }





}
