package org.ddcn41.ticketing_system.domain.seat.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.ddcn41.ticketing_system.domain.booking.entity.BookingSeat;
import org.ddcn41.ticketing_system.domain.performance.entity.PerformanceSchedule;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "schedule_seats",
        uniqueConstraints = @UniqueConstraint(columnNames = {"schedule_id", "zone", "row_label", "col_num"}))
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ScheduleSeat {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "seat_id")
    private Long seatId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "schedule_id", nullable = false)
    private PerformanceSchedule schedule;

    @Column(name = "grade", nullable = false, length = 10)
    private String grade;

    @Column(name = "zone", length = 50)
    private String zone;

    @Column(name = "row_label", nullable = false, length = 10)
    private String rowLabel;

    @Column(name = "col_num", nullable = false, length = 10)
    private String colNum;

    @Column(precision = 10, scale = 2)
    @Builder.Default
    private BigDecimal price = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    @Builder.Default
    private SeatStatus status = SeatStatus.AVAILABLE;

    @Version
    private Long version;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "seat", cascade = CascadeType.ALL)
    private List<BookingSeat> bookingSeats;

    @OneToMany(mappedBy = "seat", cascade = CascadeType.ALL)
    private List<SeatLock> seatLocks;

    public enum SeatStatus {
        AVAILABLE, LOCKED, BOOKED
    }
}