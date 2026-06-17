package com.nectarsoft.meetai.dto;

import com.nectarsoft.meetai.service.stt.RawSegment;
import lombok.Builder;
import lombok.Value;

import java.util.List;
import java.util.UUID;

@Value
@Builder
public class TranscribeResponse {
    UUID meetingId;
    String engineUsed;
    int segmentCount;
    List<TranscriptDto> transcripts;

    @Value
    @Builder
    public static class TranscriptDto {
        String speakerLabel;
        String speakerDisplay;
        double startSec;
        double endSec;
        String content;
        double confidence;
        boolean lowConfidence;
    }

    public static TranscribeResponse from(List<RawSegment> raws, String engine, UUID meetingId) {
        List<TranscriptDto> dtos = raws.stream()
                .map(r -> TranscriptDto.builder()
                        .speakerLabel(r.getSpeakerId())
                        .speakerDisplay(r.getSpeakerId())
                        .startSec(r.getStartSec())
                        .endSec(r.getEndSec())
                        .content(r.getText())
                        .confidence(r.getConfidence())
                        .lowConfidence(r.isLowConfidence())
                        .build())
                .toList();
        return TranscribeResponse.builder()
                .meetingId(meetingId)
                .engineUsed(engine)
                .segmentCount(dtos.size())
                .transcripts(dtos)
                .build();
    }
}
