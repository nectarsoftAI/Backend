package com.nectarsoft.meetai.dto;

import com.nectarsoft.meetai.model.Meeting;
import com.nectarsoft.meetai.model.Transcript;
import lombok.Builder;
import lombok.Value;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Value
@Builder
public class MeetingDetailResponse {
    UUID meetingId;
    String title;
    String meetingType;
    String status;
    Integer durationSeconds;
    OffsetDateTime meetingDate;
    OffsetDateTime createdAt;
    List<TranscriptDto> transcripts;

    @Value
    @Builder
    public static class TranscriptDto {
        String speakerLabel;
        String speakerDisplay;
        double startSec;
        double endSec;
        String content;
    }

    public static MeetingDetailResponse from(Meeting m, List<Transcript> transcripts) {
        List<TranscriptDto> dtos = transcripts.stream()
                .map(t -> TranscriptDto.builder()
                        .speakerLabel(t.getSpeakerLabel())
                        .speakerDisplay(t.getSpeakerDisplay())
                        .startSec(t.getStartSec())
                        .endSec(t.getEndSec())
                        .content(t.getContent())
                        .build())
                .toList();
        return MeetingDetailResponse.builder()
                .meetingId(m.getMeetingId())
                .title(m.getTitle())
                .meetingType(m.getMeetingType().name())
                .status(m.getStatus().name())
                .durationSeconds(m.getDurationSeconds())
                .meetingDate(m.getMeetingDate())
                .createdAt(m.getCreatedAt())
                .transcripts(dtos)
                .build();
    }
}
