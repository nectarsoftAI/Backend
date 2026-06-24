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
import java.util.stream.Collectors;

@Value
@Builder
public class MeetingListResponse {

    List<MeetingItem> meetings;

    @Value
    @Builder
    public static class MeetingItem {
        UUID meetingId;
        String title;
        String meetingType;
        String status;
        Integer durationSeconds;
        OffsetDateTime meetingDate;
        OffsetDateTime createdAt;
        List<ParticipantDto> participants;
        List<String> keywords;

        @Value
        @Builder
        public static class ParticipantDto {
            String speakerLabel;
            String speakerDisplay;
        }

        public static MeetingItem from(Meeting m, List<Transcript> transcripts, Optional<MeetingSummary> summary) {
            List<ParticipantDto> participants = transcripts.stream()
                    .collect(Collectors.toMap(
                            Transcript::getSpeakerLabel,
                            t -> ParticipantDto.builder()
                                    .speakerLabel(t.getSpeakerLabel())
                                    .speakerDisplay(t.getSpeakerDisplay())
                                    .build(),
                            (a, b) -> a))
                    .values()
                    .stream()
                    .toList();

            List<String> keywords = summary.map(s -> parseJsonArray(s.getKeywords()))
                    .orElse(Collections.emptyList());

            return MeetingItem.builder()
                    .meetingId(m.getMeetingId())
                    .title(m.getTitle())
                    .meetingType(m.getMeetingType().name())
                    .status(m.getStatus().name())
                    .durationSeconds(m.getDurationSeconds())
                    .meetingDate(m.getMeetingDate())
                    .createdAt(m.getCreatedAt())
                    .participants(participants)
                    .keywords(keywords)
                    .build();
        }

        private static List<String> parseJsonArray(String json) {
            if (json == null || json.isBlank()) return Collections.emptyList();
            try {
                return new ObjectMapper().readValue(json, new TypeReference<List<String>>() {});
            } catch (Exception e) {
                return Collections.emptyList();
            }
        }
    }
}
