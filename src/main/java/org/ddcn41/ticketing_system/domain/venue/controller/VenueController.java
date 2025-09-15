package org.ddcn41.ticketing_system.domain.venue.controller;

import lombok.RequiredArgsConstructor;
import org.ddcn41.ticketing_system.domain.venue.dto.VenueDto;
import org.ddcn41.ticketing_system.domain.venue.service.VenueService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/venues")
@RequiredArgsConstructor
public class VenueController {

    private final VenueService venueService;

    // 모든 공연장 조회
    @GetMapping
    public ResponseEntity<List<VenueDto>> getAllVenues() {
        List<VenueDto> venues = venueService.getAllVenues();
        return ResponseEntity.ok(venues);
    }

    // 특정 공연장 조회
    @GetMapping("/{venueId}")
    public ResponseEntity<VenueDto> getVenueById(@PathVariable Long venueId) {
        VenueDto venue = venueService.getVenueById(venueId);
        return ResponseEntity.ok(venue);
    }

    // 공연장 생성
    @PostMapping
    public ResponseEntity<VenueDto> createVenue(@RequestBody VenueDto venueDto) {
        VenueDto createdVenue = venueService.createVenue(venueDto);
        return ResponseEntity.status(HttpStatus.CREATED).body(createdVenue);
    }

    // 공연장 수정
    @PutMapping("/{venueId}")
    public ResponseEntity<VenueDto> updateVenue(@PathVariable Long venueId, @RequestBody VenueDto venueDto) {
        VenueDto updatedVenue = venueService.updateVenue(venueId, venueDto);
        return ResponseEntity.ok(updatedVenue);
    }

    // 공연장 삭제
    @DeleteMapping("/{venueId}")
    public ResponseEntity<Void> deleteVenue(@PathVariable Long venueId) {
        venueService.deleteVenue(venueId);
        return ResponseEntity.noContent().build();
    }

    // 공연장 좌석 배치도 조회
    @GetMapping("/{venueId}/seatmap")
    public ResponseEntity<String> getVenueSeatMap(@PathVariable Long venueId) {
        String seatMapJson = venueService.getVenueSeatMap(venueId);
        if (seatMapJson == null || seatMapJson.isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .header("Content-Type", "application/json")
                .body(seatMapJson);
    }
}