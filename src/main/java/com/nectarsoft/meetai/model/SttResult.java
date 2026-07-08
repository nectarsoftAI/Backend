package com.nectarsoft.meetai.model;

import jakarta.persistence.*;
import lombok.*;

import java.time.OffsetDateTime;

@Entity
@Table(name = "stt_results")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class SttResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "stt_id")
    private Long sttId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "meeting_id", nullable = false)
    private Meeting meeting;

    @Column(name = "retry_count")
    @Builder.Default
    private Short retryCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false)
    private SttProcessingStatus processingStatus;

    @Column(name = "processed_at")
    private OffsetDateTime processedAt;
}
