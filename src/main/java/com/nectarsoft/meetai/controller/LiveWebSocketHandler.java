package com.nectarsoft.meetai.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nectarsoft.meetai.core.websocket.WebSocketManager;
import com.nectarsoft.meetai.service.LiveService;
import jakarta.websocket.*;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
@ServerEndpoint("/api/v1/live/ws/{meetingId}")
public class LiveWebSocketHandler {

    // @ServerEndpoint는 연결마다 새 인스턴스 생성 → static으로 보관
    private static WebSocketManager wsManager;
    private static LiveService liveService;
    private static final ObjectMapper objectMapper = new ObjectMapper();

    // WebSocket Session ID → Meeting ID 매핑
    private static final ConcurrentHashMap<String, String> sessionToMeeting = new ConcurrentHashMap<>();

    public LiveWebSocketHandler() {}

    /** MeetAiApplication(ApplicationReadyEvent)에서 호출 */
    public static void init(WebSocketManager wm, LiveService ls) {
        wsManager = wm;
        liveService = ls;
        log.info("[WS] LiveWebSocketHandler 의존성 초기화 완료");
    }

    @OnOpen
    public void onOpen(Session session, @PathParam("meetingId") String meetingId) {
        session.setMaxBinaryMessageBufferSize(10 * 1024 * 1024);
        session.setMaxTextMessageBufferSize(64 * 1024);

        sessionToMeeting.put(session.getId(), meetingId);
        wsManager.register(meetingId, session);

        try {
            String json = objectMapper.writeValueAsString(Map.of(
                    "type", "session_ready",
                    "meeting_id", meetingId
            ));
            session.getBasicRemote().sendText(json);
        } catch (Exception e) {
            log.error("[WS] session_ready 전송 실패: {}", e.getMessage());
        }

        log.info("[WS] 연결 — meetingId={}", meetingId);
    }

    @OnMessage
    public void onBinaryMessage(byte[] data, Session session) {
        String meetingId = sessionToMeeting.get(session.getId());
        if (meetingId == null) return;

        log.debug("[WS] 바이너리 수신 — meetingId={}, bytes={}", meetingId, data.length);
        try {
            liveService.handleChunk(meetingId, data);
        } catch (Exception e) {
            log.warn("[WS] 청크 처리 실패 — {}: {}", meetingId, e.getMessage());
        }
    }

    @OnMessage
    public void onTextMessage(String message, Session session) {
        String meetingId = sessionToMeeting.get(session.getId());
        if (meetingId == null) return;

        if (message.contains("\"end\"")) {
            liveService.endSession(meetingId);
        }
    }

    @OnClose
    public void onClose(Session session, CloseReason reason) {
        String meetingId = sessionToMeeting.remove(session.getId());
        if (meetingId != null) {
            wsManager.remove(meetingId, session);
            log.info("[WS] 연결 종료 — meetingId={}, reason={}", meetingId,
                    reason != null ? reason.getReasonPhrase() : "null");
        }
    }

    @OnError
    public void onError(Session session, Throwable error) {
        String meetingId = sessionToMeeting.get(session.getId());
        log.error("[WS] 오류 — meetingId={}: {}", meetingId, error.getMessage());
    }
}
