package com.nectarsoft.meetai.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "meetings")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class Meeting {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(name = "meeting_id", columnDefinition = "uuid", updatable = false)
    private UUID meetingId;

    @Column(name = "user_id", columnDefinition = "uuid")
    private UUID userId;

    @Column(length = 255)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "meeting_type", nullable = false)
    private MeetingType meetingType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private MeetingStatus status;

    @Column(name = "duration_seconds")
    private Integer durationSeconds;

    @Column(name = "meeting_date", columnDefinition = "timestamptz")
    private OffsetDateTime meetingDate;

    @Column(name = "meeting_token", unique = true)
    private String inviteToken;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false, columnDefinition = "timestamptz")
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", columnDefinition = "timestamptz")
    private OffsetDateTime updatedAt;
}
