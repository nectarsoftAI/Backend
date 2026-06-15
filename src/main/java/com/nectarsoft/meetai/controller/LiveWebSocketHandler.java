package com.nectarsoft.meetai.controller;

import com.nectarsoft.meetai.core.websocket.WebSocketManager;
import com.nectarsoft.meetai.service.LiveService;
import jakarta.websocket.*;
import jakarta.websocket.server.PathParam;
import jakarta.websocket.server.ServerEndpoint;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Slf4j
@Component
@ServerEndpoint("/api/v1/live/ws/{sessionId}")
public class LiveWebSocketHandler {

    // @ServerEndpoint는 연결마다 새 인스턴스 생성 → static으로 보관
    private static WebSocketManager wsManager;
    private static LiveService liveService;

    public LiveWebSocketHandler() {}

    /** MeetAiApplication(ApplicationReadyEvent)에서 호출 */
    public static void init(WebSocketManager wm, LiveService ls) {
        wsManager = wm;
        liveService = ls;
        log.info("[WS] LiveWebSocketHandler 의존성 초기화 완료");
    }

    @OnOpen
    public void onOpen(Session session, @PathParam("sessionId") String sessionId) {
        session.setMaxBinaryMessageBufferSize(10 * 1024 * 1024);
        session.setMaxTextMessageBufferSize(64 * 1024);
        if (wsManager == null) {
            log.error("[WS] wsManager 미초기화 — sessionId={}", sessionId);
            try { session.close(); } catch (Exception ignored) {}
            return;
        }
        wsManager.register(sessionId, session);
        log.info("[WS] 연결 — sessionId={}", sessionId);
    }

    @OnMessage
    public void onBinaryMessage(byte[] data, Session session,
                                @PathParam("sessionId") String sessionId) {
        log.debug("[WS] 바이너리 수신 — sessionId={}, bytes={}", sessionId, data.length);
        try {
            liveService.handleChunk(sessionId, data);
        } catch (Exception e) {
            // 예외가 WebSocket을 닫지 않도록 catch (STT 결과 수신 대기 중)
            log.warn("[WS] 청크 처리 무시 — {}: {}", sessionId, e.getMessage());
        }
    }

    @OnMessage
    public void onTextMessage(String message, Session session,
                              @PathParam("sessionId") String sessionId) {
        if (message.contains("\"end\"")) {
            liveService.endSession(sessionId);
        }
    }

    @OnClose
    public void onClose(Session session, CloseReason reason,
                        @PathParam("sessionId") String sessionId) {
        if (wsManager != null) wsManager.remove(sessionId, session);
        log.info("[WS] 연결 종료 — sessionId={}, reason={}",
                sessionId, reason != null ? reason.getReasonPhrase() : "null");
    }

    @OnError
    public void onError(Session session, Throwable error,
                        @PathParam("sessionId") String sessionId) {
        log.error("[WS] 오류 — sessionId={}: {}", sessionId, error.getMessage());
    }
}
