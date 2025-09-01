package org.ddcn41.ticketing_system.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "venue_seats",
        uniqueConstraints = @UniqueConstraint(columnNames = {"venue_id", "seat_row", "seat_number"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class VenueSeat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "venue_seat_id")
    private Long venueSeatId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "venue_id", nullable = false)
    private Venue venue;

    @Column(name = "seat_row", nullable = false, length = 10)
    private String seatRow;

    @Column(name = "seat_number", nullable = false, length = 10)
    private String seatNumber;

    @Column(name = "seat_zone", length = 50)
    private String seatZone;

    @Enumerated(EnumType.STRING)
    @Column(name = "seat_grade", length = 20)
    private SeatGrade seatGrade;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @OneToMany(mappedBy = "venueSeat", cascade = CascadeType.ALL)
    private List<ScheduleSeat> scheduleSeats;

    public enum SeatGrade {
        VIP, R, S, A
    }
}