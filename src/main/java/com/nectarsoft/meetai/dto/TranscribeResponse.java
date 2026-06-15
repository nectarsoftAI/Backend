package com.nectarsoft.meetai.dto;

import com.nectarsoft.meetai.service.stt.RawSegment;
import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class TranscribeResponse {
    String meetingId;
    String engineUsed;
    int segmentCount;
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

    public static TranscribeResponse from(List<RawSegment> raws, String engine, String meetingId) {
        List<SegmentDto> dtos = raws.stream()
                .map(r -> SegmentDto.builder()
                        .speakerId(r.getSpeakerId())
                        .startSec(r.getStartSec())
                        .endSec(r.getEndSec())
                        .text(r.getText())
                        .confidence(r.getConfidence())
                        .lowConfidence(r.isLowConfidence())
                        .build())
                .toList();
        return TranscribeResponse.builder()
                .meetingId(meetingId)
                .engineUsed(engine)
                .segmentCount(dtos.size())
                .segments(dtos)
                .build();
    }
}
