package com.nectarsoft.meetai.dto;

import com.nectarsoft.meetai.model.Meeting;
import com.nectarsoft.meetai.model.MeetingSummary;
import com.nectarsoft.meetai.model.Transcript;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Value;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
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
    SummaryDto summary;

    @Value
    @Builder
    public static class TranscriptDto {
        String speakerLabel;
        String speakerDisplay;
        double startSec;
        double endSec;
        String content;
    }

    @Value
    @Builder
    public static class SummaryDto {
        List<String> keyPoints;
        List<String> decisions;
        List<String> keywords;
        List<ActionItemDto> actionItems;

        @Value
        @Builder
        public static class ActionItemDto {
            String task;
            String assignee;
            String due;
        }
    }

    public static MeetingDetailResponse from(Meeting m, List<Transcript> transcripts, Optional<MeetingSummary> summaryOpt) {
        List<TranscriptDto> dtos = transcripts.stream()
                .map(t -> TranscriptDto.builder()
                        .speakerLabel(t.getSpeakerLabel())
                        .speakerDisplay(t.getSpeakerDisplay())
                        .startSec(t.getStartSec())
                        .endSec(t.getEndSec())
                        .content(t.getContent())
                        .build())
                .toList();

        SummaryDto summaryDto = summaryOpt.map(s -> SummaryDto.builder()
                .keyPoints(parseStringList(s.getKeyPoints()))
                .decisions(parseStringList(s.getDecisions()))
                .keywords(parseStringList(s.getKeywords()))
                .actionItems(parseActionItems(s.getActionItems()))
                .build()
        ).orElse(null);

        return MeetingDetailResponse.builder()
                .meetingId(m.getMeetingId())
                .title(m.getTitle())
                .meetingType(m.getMeetingType().name())
                .status(m.getStatus().name())
                .durationSeconds(m.getDurationSeconds())
                .meetingDate(m.getMeetingDate())
                .createdAt(m.getCreatedAt())
                .transcripts(dtos)
                .summary(summaryDto)
                .build();
    }

    private static List<String> parseStringList(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return new ObjectMapper().readValue(json, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }

    private static List<SummaryDto.ActionItemDto> parseActionItems(String json) {
        if (json == null || json.isBlank()) return Collections.emptyList();
        try {
            return new ObjectMapper().readValue(json, new TypeReference<List<SummaryDto.ActionItemDto>>() {});
        } catch (Exception e) {
            return Collections.emptyList();
        }
    }
}
