package com.nectarsoft.meetai.core.websocket;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class OnlineRoomManager {

    // meetingId → (profileId → session)
    private final ConcurrentHashMap<String, ConcurrentHashMap<String, WebSocketSession>> rooms = new ConcurrentHashMap<>();

    public void join(String meetingId, String profileId, WebSocketSession session) {
        rooms.computeIfAbsent(meetingId, k -> new ConcurrentHashMap<>()).put(profileId, session);
        log.info("[OnlineRoom] 입장 — meetingId={}, profileId={}", meetingId, profileId);
    }

    public void leave(String meetingId, String profileId) {
        ConcurrentHashMap<String, WebSocketSession> room = rooms.get(meetingId);
        if (room != null) {
            room.remove(profileId);
            if (room.isEmpty()) rooms.remove(meetingId);
        }
        log.info("[OnlineRoom] 퇴장 — meetingId={}, profileId={}", meetingId, profileId);
    }

    public Set<String> getProfileIds(String meetingId) {
        ConcurrentHashMap<String, WebSocketSession> room = rooms.get(meetingId);
        return room != null ? room.keySet() : Set.of();
    }

    public boolean isInRoom(String meetingId, String profileId) {
        ConcurrentHashMap<String, WebSocketSession> room = rooms.get(meetingId);
        return room != null && room.containsKey(profileId);
    }

    public void broadcast(String meetingId, String json) {
        ConcurrentHashMap<String, WebSocketSession> room = rooms.getOrDefault(meetingId, new ConcurrentHashMap<>());
        for (WebSocketSession ws : room.values()) {
            sendSafe(ws, json);
        }
    }

    public void broadcastExcept(String meetingId, String excludeProfileId, String json) {
        ConcurrentHashMap<String, WebSocketSession> room = rooms.getOrDefault(meetingId, new ConcurrentHashMap<>());
        for (Map.Entry<String, WebSocketSession> entry : room.entrySet()) {
            if (!entry.getKey().equals(excludeProfileId)) {
                sendSafe(entry.getValue(), json);
            }
        }
    }

    public void sendToOne(String meetingId, String profileId, String json) {
        ConcurrentHashMap<String, WebSocketSession> room = rooms.get(meetingId);
        if (room != null) {
            WebSocketSession ws = room.get(profileId);
            if (ws != null) sendSafe(ws, json);
        }
    }

    public void closeOne(String meetingId, String profileId) {
        ConcurrentHashMap<String, WebSocketSession> room = rooms.get(meetingId);
        if (room != null) {
            WebSocketSession ws = room.remove(profileId);
            if (ws != null && ws.isOpen()) {
                try { ws.close(CloseStatus.NORMAL); } catch (Exception ignored) {}
            }
        }
    }

    public void closeAll(String meetingId) {
        ConcurrentHashMap<String, WebSocketSession> room = rooms.remove(meetingId);
        if (room == null) return;
        for (WebSocketSession ws : room.values()) {
            try { if (ws.isOpen()) ws.close(CloseStatus.NORMAL); } catch (Exception ignored) {}
        }
        log.info("[OnlineRoom] 전체 종료 — meetingId={}", meetingId);
    }

    private void sendSafe(WebSocketSession ws, String json) {
        if (!ws.isOpen()) return;
        synchronized (ws) {
            try { ws.sendMessage(new TextMessage(json)); }
            catch (Exception e) { log.warn("[OnlineRoom] 전송 실패: {}", e.getMessage()); }
        }
    }
}
