package com.nectarsoft.meetai.core.websocket;

import jakarta.websocket.Session;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Observer 패턴 — 세션별 WebSocket 연결 관리 및 브로드캐스트
 */
@Slf4j
@Component
public class WebSocketManager {

    private final ConcurrentHashMap<String, Set<Session>> sessions = new ConcurrentHashMap<>();

    public void register(String sessionId, Session ws) {
        sessions.computeIfAbsent(sessionId, k -> ConcurrentHashMap.newKeySet()).add(ws);
        log.info("[WS] 연결 등록 — sessionId={}, wsId={}", sessionId, ws.getId());
    }

    public void remove(String sessionId, Session ws) {
        Set<Session> set = sessions.get(sessionId);
        if (set != null) {
            set.remove(ws);
            if (set.isEmpty()) sessions.remove(sessionId);
        }
    }

    public void broadcast(String sessionId, String json) {
        Set<Session> set = sessions.getOrDefault(sessionId, Set.of());
        for (Session ws : set) {
            if (ws.isOpen()) {
                try {
                    ws.getBasicRemote().sendText(json);
                } catch (IOException ex) {
                    log.warn("[WS] 전송 실패 wsId={}: {}", ws.getId(), ex.getMessage());
                }
            }
        }
    }

    public void closeAll(String sessionId) {
        Set<Session> set = sessions.remove(sessionId);
        if (set == null) return;
        for (Session ws : set) {
            try {
                if (ws.isOpen()) ws.close();
            } catch (Exception ignored) {}
        }
        log.info("[WS] 세션 종료 — sessionId={}", sessionId);
    }

    public boolean hasSession(String sessionId) {
        return sessions.containsKey(sessionId);
    }
}
