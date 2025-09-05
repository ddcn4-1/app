package org.ddcn41.ticketing_system.domain.venue.service;

import lombok.RequiredArgsConstructor;
import org.ddcn41.ticketing_system.domain.venue.dto.VenueDto;
import org.ddcn41.ticketing_system.domain.venue.entity.Venue;
import org.ddcn41.ticketing_system.domain.venue.repository.VenueRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class VenueService {

    private final VenueRepository venueRepository;

    // 모든 공연장 조회
    public List<VenueDto> getAllVenues() {
        return venueRepository.findAll().stream()
                .map(this::convertToDto)
                .collect(Collectors.toList());
    }

    // 공연장 ID로 조회
    public VenueDto getVenueById(Long venueId) {
        Venue venue = venueRepository.findById(venueId)
                .orElseThrow(() -> new RuntimeException("Venue not found with id: " + venueId));
        return convertToDto(venue);
    }

    // 공연장 생성
    @Transactional
    public VenueDto createVenue(VenueDto venueDto) {
        Venue venue = Venue.builder()
                .venueName(venueDto.getVenueName())
                .address(venueDto.getAddress())
                .description(venueDto.getDescription())
                .totalCapacity(venueDto.getTotalCapacity())
                .contact(venueDto.getContact())
                .build();

        Venue savedVenue = venueRepository.save(venue);
        return convertToDto(savedVenue);
    }

    // 공연장 수정
    @Transactional
    public VenueDto updateVenue(Long venueId, VenueDto venueDto) {
        Venue venue = venueRepository.findById(venueId)
                .orElseThrow(() -> new RuntimeException("Venue not found with id: " + venueId));

        venue.setVenueName(venueDto.getVenueName());
        venue.setAddress(venueDto.getAddress());
        venue.setDescription(venueDto.getDescription());
        venue.setTotalCapacity(venueDto.getTotalCapacity());
        venue.setContact(venueDto.getContact());

        Venue updatedVenue = venueRepository.save(venue);
        return convertToDto(updatedVenue);
    }

    // 공연장 삭제
    @Transactional
    public void deleteVenue(Long venueId) {
        if (!venueRepository.existsById(venueId)) {
            throw new RuntimeException("Venue not found with id: " + venueId);
        }
        venueRepository.deleteById(venueId);
    }

    // Entity를 DTO로 변환
    private VenueDto convertToDto(Venue venue) {
        return VenueDto.builder()
                .venueId(venue.getVenueId())
                .venueName(venue.getVenueName())
                .address(venue.getAddress())
                .description(venue.getDescription())
                .totalCapacity(venue.getTotalCapacity())
                .contact(venue.getContact())
                .build();
    }
}