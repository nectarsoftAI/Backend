package com.nectarsoft.meetai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nectarsoft.meetai.core.websocket.OnlineRoomManager;
import com.nectarsoft.meetai.model.Meeting;
import com.nectarsoft.meetai.repository.MeetingRepository;
import com.nectarsoft.meetai.service.AssemblyAiStreamingManager;
import com.nectarsoft.meetai.service.LiveService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.nio.ByteBuffer;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class LiveWebSocketHandler extends AbstractWebSocketHandler {

    private final LiveService liveService;
    private final MeetingRepository meetingRepo;
    private final AssemblyAiStreamingManager streamingManager;
    private final OnlineRoomManager roomManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String meetingId = meetingId(session);
        session.setBinaryMessageSizeLimit(10 * 1024 * 1024);
        session.setTextMessageSizeLimit(64 * 1024);

        Meeting meeting = meetingRepo.findById(UUID.fromString(meetingId)).orElse(null);
        if (meeting == null) {
            session.close(CloseStatus.BAD_DATA);
            return;
        }
        String profileId = meeting.getUserId().toString();
        session.getAttributes().put("profileId", profileId);

        roomManager.join(meetingId, profileId, session);

        Map<String, Object> ready = new LinkedHashMap<>();
        ready.put("type", "session_ready");
        ready.put("meeting_id", meetingId);
        if (meeting.getMeetingDate() != null) {
            ready.put("startedAt", meeting.getMeetingDate().toString());
        }
        synchronized (session) {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(ready)));
        }
        log.info("[LiveWS] 연결 — meetingId={}, profileId={}", meetingId, profileId);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        String meetingId = meetingId(session);
        String profileId = profileId(session);
        if (profileId == null) return;

        ByteBuffer payload = message.getPayload();
        byte[] pcm = new byte[payload.remaining()];
        payload.get(pcm);

        log.debug("[LiveWS] PCM 수신 — meetingId={}, bytes={}", meetingId, pcm.length);
        try {
            streamingManager.sendAudio(meetingId, profileId, pcm);
        } catch (Exception e) {
            log.error("[LiveWS] 오디오 스트리밍 오류 — meetingId={}: {}", meetingId, e.getMessage());
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        String meetingId = meetingId(session);
        if (message.getPayload().contains("\"end\"")) {
            liveService.endSession(meetingId);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String meetingId = meetingId(session);
        String profileId = profileId(session);
        if (profileId != null) {
            roomManager.leave(meetingId, profileId);
            liveService.endSession(meetingId);
        }
        log.info("[LiveWS] 연결 종료 — meetingId={}, status={}", meetingId, status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("[LiveWS] 오류 — meetingId={}: {}", meetingId(session), exception.getMessage());
    }

    private String meetingId(WebSocketSession session) {
        return (String) session.getAttributes().get("meetingId");
    }

    private String profileId(WebSocketSession session) {
        Object v = session.getAttributes().get("profileId");
        return v != null ? v.toString() : null;
    }
}
