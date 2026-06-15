package com.nectarsoft.meetai.dto;

import com.nectarsoft.meetai.model.LiveSession;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class LiveSessionResponse {
    String sessionId;
    String status;
    String wsUrl;

    public static LiveSessionResponse from(LiveSession session, String baseUrl) {
        return LiveSessionResponse.builder()
                .sessionId(session.getId())
                .status(session.getStatus().name())
                .wsUrl("ws://" + baseUrl + "/api/v1/live/ws/" + session.getId())
                .build();
    }
}
