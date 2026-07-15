package com.nectarsoft.meetai.dto;

import com.nectarsoft.meetai.core.util.Keywords;
import com.nectarsoft.meetai.model.Meeting;
import com.nectarsoft.meetai.model.MeetingSummary;
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
    SummaryDto summary;

    @Value
    @Builder
    public static class TranscriptDto {
        Long transcriptId;
        String speakerLabel;
        String speakerDisplay;
        double startSec;
        double endSec;
        String content;
    }

    @Value
    @Builder
    public static class SummaryDto {
        String keyPoints;
        String decisions;
        String actionItems;
        // 프론트 parseSummaryDto가 JSON.parse하는 계약 — 반드시 JSON 배열 "문자열" (배열 아님)
        String keywords;
        String processingStatus;
        OffsetDateTime processedAt;
    }

    public static MeetingDetailResponse from(Meeting m, List<Transcript> transcripts, MeetingSummary summary) {
        List<TranscriptDto> dtos = transcripts.stream()
                .map(t -> TranscriptDto.builder()
                        .transcriptId(t.getTranscriptId())
                        .speakerLabel(t.getSpeakerLabel())
                        .speakerDisplay(t.getSpeakerDisplay())
                        .startSec(t.getStartSec())
                        .endSec(t.getEndSec())
                        .content(t.getContent())
                        .build())
                .toList();

        SummaryDto summaryDto = summary == null ? null : SummaryDto.builder()
                .keyPoints(summary.getKeyPoints())
                .decisions(summary.getDecisions())
                .actionItems(summary.getActionItems())
                // 오염된 값(이중 인코딩 등)도 parse→toJson 왕복으로 항상 깨끗한 배열 문자열로 정규화
                .keywords(Keywords.toJson(Keywords.parse(summary.getKeywords())))
                .processingStatus(summary.getProcessingStatus().name())
                .processedAt(summary.getProcessedAt())
                .build();

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


}
