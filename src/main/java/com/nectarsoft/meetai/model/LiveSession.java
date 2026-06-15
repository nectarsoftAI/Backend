package com.nectarsoft.meetai.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "live_sessions")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class LiveSession {

    @Id
    @Column(length = 36)
    private String id;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private LiveSessionStatus status;

    /** 세션 종료 후 생성된 Meeting ID */
    private String meetingId;

    @CreationTimestamp
    private LocalDateTime createdAt;

    private LocalDateTime endedAt;
}
