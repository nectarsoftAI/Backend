package com.nectarsoft.meetai.model;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

@Entity
@Table(name = "transcripts")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class Transcript {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "transcript_id")
    private Long transcriptId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "meeting_id", nullable = false)
    private Meeting meeting;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "stt_id", nullable = false)
    private SttResult sttResult;

    @Column(name = "speaker_label", length = 50)
    private String speakerLabel;

    @Column(name = "speaker_display", length = 100)
    private String speakerDisplay;

    @Column(name = "start_sec")
    private Double startSec;

    @Column(name = "end_sec")
    private Double endSec;

    // PG: text / H2: clob — 방언 중립 롱텍스트 (columnDefinition "TEXT"는 H2 2.x에서 미지원)
    @JdbcTypeCode(SqlTypes.LONGVARCHAR)
    private String content;
}
