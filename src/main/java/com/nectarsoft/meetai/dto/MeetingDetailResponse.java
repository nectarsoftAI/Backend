package com.nectarsoft.meetai.dto;

import com.nectarsoft.meetai.model.Meeting;
import com.nectarsoft.meetai.model.TranscriptSegment;
import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;
import java.util.List;

@Value
@Builder
public class MeetingDetailResponse {
    String meetingId;
    String filename;
    String status;
    String engineUsed;
    LocalDateTime createdAt;
    List<SegmentDto> segments;

    @Value
    @Builder
    public static class SegmentDto {
        String speakerId;
        double startSec;
        double endSec;
        String text;
        double confidence;
        boolean lowConfidence;
    }

    public static MeetingDetailResponse from(Meeting m) {
        List<SegmentDto> segs = m.getSegments().stream()
                .map(s -> SegmentDto.builder()
                        .speakerId(s.getSpeakerId())
                        .startSec(s.getStartSec())
                        .endSec(s.getEndSec())
                        .text(s.getText())
                        .confidence(s.getConfidence())
                        .lowConfidence(s.isLowConfidence())
                        .build())
                .toList();
        return MeetingDetailResponse.builder()
                .meetingId(m.getId())
                .filename(m.getFilename())
                .status(m.getStatus().name())
                .engineUsed(m.getEngineUsed())
                .createdAt(m.getCreatedAt())
                .segments(segs)
                .build();
    }
}
