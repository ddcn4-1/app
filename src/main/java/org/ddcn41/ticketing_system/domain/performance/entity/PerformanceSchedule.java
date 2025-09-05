package org.ddcn41.ticketing_system.domain.performance.entity;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.ddcn41.ticketing_system.domain.performance.entity.Performance;
import org.ddcn41.ticketing_system.entity.Booking;
import org.ddcn41.ticketing_system.entity.ScheduleSeat;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "performance_schedules")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class PerformanceSchedule {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "schedule_id")
    private Long scheduleId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "performance_id", nullable = false)
    private Performance performance;

    @Column(name = "show_datetime", nullable = false)
    private LocalDateTime showDatetime;

    @Column(name = "total_seats")
    @Builder.Default
    private Integer totalSeats = 0;

    @Column(name = "available_seats")
    @Builder.Default
    private Integer availableSeats = 0;

    @Enumerated(EnumType.STRING)
    @Column(length = 20)
    @Builder.Default
    private ScheduleStatus status = ScheduleStatus.OPEN;

    @Column(name = "booking_start_at")
    private LocalDateTime bookingStartAt;

    @Column(name = "booking_end_at")
    private LocalDateTime bookingEndAt;

    @CreationTimestamp
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @OneToMany(mappedBy = "schedule", cascade = CascadeType.ALL)
    private List<ScheduleSeat> seats;

    @OneToMany(mappedBy = "schedule", cascade = CascadeType.ALL)
    private List<Booking> bookings;

    public enum ScheduleStatus {
        OPEN, CLOSED, SOLDOUT
    }
}