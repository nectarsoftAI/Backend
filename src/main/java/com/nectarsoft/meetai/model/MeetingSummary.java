package com.nectarsoft.meetai.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;

@Entity
@Table(name = "meeting_summaries")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class MeetingSummary {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "summary_id")
    private Long summaryId;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "meeting_id", unique = true, nullable = false)
    private Meeting meeting;

    @Column(name = "llm_model", length = 100)
    private String llmModel;

    @Column(name = "retry_count")
    @Builder.Default
    private Short retryCount = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "processing_status", nullable = false)
    private SttProcessingStatus processingStatus;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "key_points", columnDefinition = "jsonb")
    private String keyPoints;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "decisions", columnDefinition = "jsonb")
    private String decisions;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "action_items", columnDefinition = "jsonb")
    private String actionItems;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "keywords", columnDefinition = "jsonb")
    private String keywords;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "raw_response", columnDefinition = "jsonb")
    private String rawResponse;

    @Column(name = "processed_at", columnDefinition = "timestamptz")
    private OffsetDateTime processedAt;
}
