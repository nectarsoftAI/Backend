package com.nectarsoft.meetai.model;

import jakarta.persistence.*;
import lombok.*;

@Entity
@Table(name = "transcript_segments")
@Getter @Setter @Builder @NoArgsConstructor @AllArgsConstructor
public class TranscriptSegment {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "meeting_id", nullable = false)
    private Meeting meeting;

    private String speakerId;
    private double startSec;
    private double endSec;

    @Column(columnDefinition = "TEXT")
    private String text;

    private double confidence;
    private boolean lowConfidence;
    private int segmentIndex;
}
