package com.nectarsoft.meetai.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "meeting_participants",
        uniqueConstraints = @UniqueConstraint(columnNames = {"meeting_id", "user_id"}))
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class MeetingParticipant {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "participant_id")
    private Long participantId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "meeting_id", nullable = false)
    private Meeting meeting;

    @Column(name = "user_id", nullable = false, columnDefinition = "uuid")
    private UUID userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    @Builder.Default
    private ParticipantRole role = ParticipantRole.GUEST;

    @Column(name = "can_invite", nullable = false)
    @Builder.Default
    private boolean canInvite = false;

    @Column(name = "can_edit", nullable = false)
    @Builder.Default
    private boolean canEdit = false;

    @Column(name = "can_delete", nullable = false)
    @Builder.Default
    private boolean canDelete = false;

    @Column(name = "can_start_end", nullable = false)
    @Builder.Default
    private boolean canStartEnd = false;

    @CreationTimestamp
    @Column(name = "joined_at", updatable = false, columnDefinition = "timestamptz")
    private OffsetDateTime joinedAt;
}
