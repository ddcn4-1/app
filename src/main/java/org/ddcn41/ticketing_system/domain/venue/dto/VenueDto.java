package org.ddcn41.ticketing_system.domain.venue.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VenueDto {
    //todo: 나중에 이 api를 사용하는게 확정되면 request, resposne dto 구분 필요
    private Long venueId;
    private String venueName;
    private String address;
    private String description;
    private Integer totalCapacity;
    private String contact;
}
