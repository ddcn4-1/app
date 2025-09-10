package org.ddcn41.ticketing_system.domain.performance.controller;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.ddcn41.ticketing_system.domain.performance.dto.response.PerformanceResponse;
import org.ddcn41.ticketing_system.domain.performance.entity.Performance;
import org.ddcn41.ticketing_system.domain.performance.service.PerformanceService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@Tag(name="Performances", description = "APIs for performance")
@RestController
@RequestMapping("/v1/performances")
@RequiredArgsConstructor
public class PerformanceController {

    private final PerformanceService performanceService;
    @Operation(summary = "모든 공연 조회", description = "클라이언트 화면에서 공연 전체 조회 시 사용")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Success get all performances", content = @Content(schema = @Schema(implementation = PerformanceResponse.class),mediaType = "application/json"))
    })
    @GetMapping
    public ResponseEntity<List<PerformanceResponse>> getAllPerformance(){
        List<Performance> performances = performanceService.getAllPerformances();
        List<PerformanceResponse> responses = performances.stream()
                .map(PerformanceResponse::from)
                .collect(Collectors.toList());

        return ResponseEntity.ok(responses);
    }

    @Operation(summary = "특정 공연 조회", description = "performanceId를 통한 공연 상세 조회")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Success get a performance", content = @Content(schema = @Schema(implementation = PerformanceResponse.class),mediaType = "application/json"))
    })
    @GetMapping("/{performanceId}")
    public ResponseEntity<PerformanceResponse> getPerformanceById(@PathVariable long performanceId){
        Performance performance = performanceService.getPerformanceById(performanceId);
        return ResponseEntity.ok(PerformanceResponse.from(performance));
    }

    @Operation(summary = "공연 검색", description = "제목, 장소, status에 따른 공연 검색")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Success get a performance", content = @Content(schema = @Schema(implementation = PerformanceResponse.class),mediaType = "application/json"))
    })
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
