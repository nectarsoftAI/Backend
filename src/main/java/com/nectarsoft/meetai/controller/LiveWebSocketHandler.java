package com.nectarsoft.meetai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nectarsoft.meetai.core.websocket.WebSocketManager;
import com.nectarsoft.meetai.service.LiveService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.nio.ByteBuffer;
import java.util.Map;

@Slf4j
@Component
@RequiredArgsConstructor
public class LiveWebSocketHandler extends AbstractWebSocketHandler {

    private final LiveService liveService;
    private final WebSocketManager wsManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String meetingId = meetingId(session);
        session.setBinaryMessageSizeLimit(10 * 1024 * 1024);
        session.setTextMessageSizeLimit(64 * 1024);

        wsManager.register(meetingId, session);

        String json = objectMapper.writeValueAsString(
                Map.of("type", "session_ready", "meeting_id", meetingId));
        synchronized (session) {
            session.sendMessage(new TextMessage(json));
        }
        log.info("[WS] 연결 — meetingId={}", meetingId);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        String meetingId = meetingId(session);
        ByteBuffer payload = message.getPayload();
        byte[] data = new byte[payload.remaining()];
        payload.get(data);

        log.debug("[WS] 바이너리 수신 — meetingId={}, bytes={}", meetingId, data.length);
        try {
            liveService.handleChunk(meetingId, data);
        } catch (Exception e) {
            log.warn("[WS] 청크 처리 실패 — {}: {}", meetingId, e.getMessage());
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
        wsManager.remove(meetingId, session);
        log.info("[WS] 연결 종료 — meetingId={}, status={}", meetingId, status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("[WS] 오류 — meetingId={}: {}", meetingId(session), exception.getMessage());
    }

    private String meetingId(WebSocketSession session) {
        return (String) session.getAttributes().get("meetingId");
    }
}
