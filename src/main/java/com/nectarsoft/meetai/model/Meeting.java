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
    @Column(name = "meeting_id", updatable = false)
    private UUID meetingId;

    @Column(name = "user_id")
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

    @Column(name = "meeting_date")
    private OffsetDateTime meetingDate;

    @Column(name = "meeting_token", unique = true, length = 10)
    private String inviteToken;

    @PrePersist
    private void assignToken() {
        if (inviteToken == null) {
            inviteToken = UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        }
    }

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;
}
