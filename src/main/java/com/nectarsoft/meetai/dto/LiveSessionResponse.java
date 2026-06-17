package com.nectarsoft.meetai.dto;

import com.nectarsoft.meetai.model.Meeting;
import lombok.Builder;
import lombok.Value;

import java.util.UUID;

@Value
@Builder
public class LiveSessionResponse {
    UUID meetingId;
    String status;
    String wsUrl;

    public static LiveSessionResponse from(Meeting meeting, String baseUrl) {
        return LiveSessionResponse.builder()
                .meetingId(meeting.getMeetingId())
                .status(meeting.getStatus().name())
                .wsUrl("ws://" + baseUrl + "/api/v1/live/ws/" + meeting.getMeetingId())
                .build();
    }
}
