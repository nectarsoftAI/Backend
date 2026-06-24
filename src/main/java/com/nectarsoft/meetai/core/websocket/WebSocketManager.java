package com.nectarsoft.meetai.core.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class WebSocketManager {

    private final ConcurrentHashMap<String, Set<WebSocketSession>> sessions = new ConcurrentHashMap<>();

    public void register(String meetingId, WebSocketSession session) {
        sessions.computeIfAbsent(meetingId, k -> ConcurrentHashMap.newKeySet()).add(session);
        log.info("[WS] 연결 등록 — meetingId={}, wsId={}", meetingId, session.getId());
    }

    public void remove(String meetingId, WebSocketSession session) {
        Set<WebSocketSession> set = sessions.get(meetingId);
        if (set != null) {
            set.remove(session);
            if (set.isEmpty()) sessions.remove(meetingId);
        }
    }

    public void broadcast(String meetingId, String json) {
        Set<WebSocketSession> set = sessions.getOrDefault(meetingId, Set.of());
        for (WebSocketSession ws : set) {
            if (ws.isOpen()) {
                synchronized (ws) {
                    try {
                        ws.sendMessage(new TextMessage(json));
                    } catch (Exception ex) {
                        log.warn("[WS] 전송 실패 wsId={}: {}", ws.getId(), ex.getMessage());
                    }
                }
            }
        }
    }

    public void closeAll(String meetingId) {
        Set<WebSocketSession> set = sessions.remove(meetingId);
        if (set == null) return;
        for (WebSocketSession ws : set) {
            try {
                if (ws.isOpen()) ws.close(CloseStatus.NORMAL);
            } catch (Exception ignored) {}
        }
        log.info("[WS] 세션 종료 — meetingId={}", meetingId);
    }

    public boolean hasSession(String meetingId) {
        return sessions.containsKey(meetingId);
    }
}
