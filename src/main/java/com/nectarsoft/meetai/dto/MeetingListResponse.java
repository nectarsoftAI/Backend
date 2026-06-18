package com.nectarsoft.meetai.dto;

import com.nectarsoft.meetai.model.Meeting;
import lombok.Builder;
import lombok.Value;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

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

        public static MeetingItem from(Meeting m) {
            return MeetingItem.builder()
                    .meetingId(m.getMeetingId())
                    .title(m.getTitle())
                    .meetingType(m.getMeetingType().name())
                    .status(m.getStatus().name())
                    .durationSeconds(m.getDurationSeconds())
                    .meetingDate(m.getMeetingDate())
                    .createdAt(m.getCreatedAt())
                    .build();
        }
    }
}
