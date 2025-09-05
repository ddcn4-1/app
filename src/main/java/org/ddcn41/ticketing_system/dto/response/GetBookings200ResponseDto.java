package org.ddcn41.ticketing_system.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.ddcn41.ticketing_system.dto.BookingDto;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class GetBookings200ResponseDto {
    private List<BookingDto> bookings;
    private Integer total;
    private Integer page;
}

