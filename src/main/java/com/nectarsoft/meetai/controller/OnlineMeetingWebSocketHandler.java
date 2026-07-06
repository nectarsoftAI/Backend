package com.nectarsoft.meetai.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nectarsoft.meetai.core.websocket.OnlineRoomManager;
import com.nectarsoft.meetai.model.*;
import com.nectarsoft.meetai.repository.MeetingParticipantRepository;
import com.nectarsoft.meetai.repository.MeetingRepository;
import com.nectarsoft.meetai.service.OnlineBufferProcessor;
import com.nectarsoft.meetai.service.SessionBuffer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class OnlineMeetingWebSocketHandler extends AbstractWebSocketHandler {

    private static final int ADMIN_GRACE_SECONDS = 30;

    private final OnlineRoomManager roomManager;
    private final MeetingRepository meetingRepo;
    private final MeetingParticipantRepository participantRepo;
    private final OnlineBufferProcessor bufferProcessor;
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ScheduledExecutorService graceScheduler = Executors.newScheduledThreadPool(2);

    // "meetingId:profileId" → 참여자별 오디오 버퍼
    private final ConcurrentHashMap<String, SessionBuffer> audioBuffers = new ConcurrentHashMap<>();

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws Exception {
        String meetingId = attr(session, "meetingId");
        String token     = attr(session, "token");
        String profileId = attr(session, "profileId");

        if (meetingId == null || profileId == null) {
            session.close(CloseStatus.BAD_DATA);
            return;
        }

        Meeting meeting = meetingRepo.findById(UUID.fromString(meetingId)).orElse(null);
        if (meeting == null) {
            session.close(CloseStatus.BAD_DATA);
            return;
        }

        // ADMIN: 회의 생성자 UUID로 판단 (DB 조회 불필요)
        UUID pId = UUID.fromString(profileId);
        boolean isAdmin = pId.equals(meeting.getUserId());

        if (!isAdmin && (token == null || !token.equalsIgnoreCase(meeting.getInviteToken()))) {
            log.warn("[OnlineWS] 토큰 불일치 — meetingId={}, profileId={}", meetingId, profileId);
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        session.setBinaryMessageSizeLimit(10 * 1024 * 1024);
        session.setTextMessageSizeLimit(64 * 1024);

        // 세션에 role 저장 (이후 권한 체크에 사용)
        String role = isAdmin ? "ADMIN" : "GUEST";
        session.getAttributes().put("role", role);

        // GUEST 첫 입장 시 DB 등록 (실패해도 WS 연결은 유지)
        if (!isAdmin) {
            try {
                if (!participantRepo.existsByMeetingMeetingIdAndProfileId(UUID.fromString(meetingId), pId)) {
                    participantRepo.save(MeetingParticipant.builder()
                            .meeting(meeting).profileId(pId)
                            .role(ParticipantRole.GUEST)
                            .canInvite(false).canEdit(false).canDelete(false).canRunMeeting(false)
                            .build());
                }
            } catch (Exception e) {
                log.warn("[OnlineWS] 게스트 DB 등록 실패 (무시) — profileId={}: {}", profileId, e.getMessage());
            }
        }

        roomManager.join(meetingId, profileId, session);
        roomManager.broadcastExcept(meetingId, profileId, objectMapper.writeValueAsString(Map.of(
                "type", "participant_joined",
                "profileId", profileId,
                "role", role
        )));

        // 신규 참여자에게 room_info 전송
        synchronized (session) {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(Map.of(
                    "type", "room_info",
                    "status", meeting.getStatus().name(),
                    "participants", new ArrayList<>(roomManager.getProfileIds(meetingId))
            ))));
        }

        log.info("[OnlineWS] 연결 — meetingId={}, profileId={}, role={}", meetingId, profileId, role);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        String meetingId = attr(session, "meetingId");
        String profileId = attr(session, "profileId");
        if (meetingId == null || profileId == null) return;

        ByteBuffer payload = message.getPayload();
        byte[] data = new byte[payload.remaining()];
        payload.get(data);

        String bufferKey = meetingId + ":" + profileId;
        SessionBuffer buffer = audioBuffers.computeIfAbsent(bufferKey, k -> new SessionBuffer());
        buffer.addChunk(data);

        if (buffer.shouldProcess()) {
            bufferProcessor.process(meetingId, profileId, profileId, buffer);
        }
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
        String meetingId = attr(session, "meetingId");
        String profileId = attr(session, "profileId");
        if (meetingId == null || profileId == null) return;

        Map<String, Object> msg = objectMapper.readValue(message.getPayload(), new TypeReference<>() {});
        String type = (String) msg.get("type");
        if (type == null) return;

        switch (type) {
            // WebRTC 시그널링 — 대상에게 중계
            case "offer", "answer", "ice_candidate" -> {
                String to = (String) msg.get("to");
                if (to == null) return;
                msg.put("from", profileId);
                roomManager.sendToOne(meetingId, to, objectMapper.writeValueAsString(msg));
            }

            // 회의 시작 (ADMIN 전용)
            case "start_meeting" -> {
                if (!isAdmin(meetingId, profileId)) { sendError(session, "권한이 없습니다."); return; }
                meetingRepo.findById(UUID.fromString(meetingId)).ifPresent(m -> {
                    m.setStatus(MeetingStatus.LIVE);
                    meetingRepo.save(m);
                });
                roomManager.broadcast(meetingId, objectMapper.writeValueAsString(Map.of("type", "meeting_started")));
                log.info("[OnlineWS] 회의 시작 — meetingId={}", meetingId);
            }

            // 회의 종료 (ADMIN 전용)
            case "end_meeting" -> {
                if (!isAdmin(meetingId, profileId)) { sendError(session, "권한이 없습니다."); return; }
                endMeeting(meetingId);
            }

            // 강퇴 (ADMIN 전용)
            case "kick" -> {
                if (!isAdmin(meetingId, profileId)) { sendError(session, "권한이 없습니다."); return; }
                String target = (String) msg.get("profileId");
                if (target == null) return;
                roomManager.sendToOne(meetingId, target,
                        objectMapper.writeValueAsString(Map.of("type", "kicked")));
                roomManager.closeOne(meetingId, target);
                roomManager.broadcastExcept(meetingId, target,
                        objectMapper.writeValueAsString(Map.of("type", "participant_left", "profileId", target)));
                log.info("[OnlineWS] 강퇴 — meetingId={}, target={}", meetingId, target);
            }

            default -> log.debug("[OnlineWS] 알 수 없는 타입: {}", type);
        }
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        String meetingId = attr(session, "meetingId");
        String profileId = attr(session, "profileId");
        if (meetingId == null || profileId == null) return;

        // 남은 오디오 버퍼 처리
        SessionBuffer buffer = audioBuffers.remove(meetingId + ":" + profileId);
        if (buffer != null && buffer.hasInit()) {
            bufferProcessor.process(meetingId, profileId, profileId, buffer);
        }

        roomManager.leave(meetingId, profileId);
        try {
            roomManager.broadcastExcept(meetingId, profileId, objectMapper.writeValueAsString(
                    Map.of("type", "participant_left", "profileId", profileId)));
        } catch (Exception ignored) {}

        log.info("[OnlineWS] 연결 종료 — meetingId={}, profileId={}, status={}", meetingId, profileId, status);

        // 방장이 비정상 종료된 경우 grace period 후 자동 종료
        if (status.getCode() != CloseStatus.NORMAL.getCode() && isAdmin(meetingId, profileId)) {
            log.info("[OnlineWS] 방장 비정상 종료 — {}초 후 재접속 없으면 회의 자동 종료 meetingId={}", ADMIN_GRACE_SECONDS, meetingId);
            graceScheduler.schedule(() -> {
                if (!roomManager.isInRoom(meetingId, profileId)) {
                    log.info("[OnlineWS] 방장 미복귀 — 회의 자동 종료 meetingId={}", meetingId);
                    endMeeting(meetingId);
                } else {
                    log.info("[OnlineWS] 방장 재접속 확인 — 자동 종료 취소 meetingId={}", meetingId);
                }
            }, ADMIN_GRACE_SECONDS, TimeUnit.SECONDS);
        }
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable ex) {
        log.error("[OnlineWS] 전송 오류 — meetingId={}: {}", attr(session, "meetingId"), ex.getMessage());
    }

    private void endMeeting(String meetingId) {
        meetingRepo.findById(UUID.fromString(meetingId)).ifPresent(m -> {
            if (m.getStatus() == MeetingStatus.COMPLETED) return;
            m.setStatus(MeetingStatus.COMPLETED);
            meetingRepo.save(m);
        });
        try {
            roomManager.broadcast(meetingId, objectMapper.writeValueAsString(Map.of("type", "meeting_ended")));
        } catch (Exception ignored) {}
        roomManager.closeAll(meetingId);
        log.info("[OnlineWS] 회의 종료 — meetingId={}", meetingId);
    }

    private boolean isAdmin(String meetingId, String profileId) {
        UUID pId = UUID.fromString(profileId);
        return meetingRepo.findById(UUID.fromString(meetingId))
                .map(m -> m.getUserId() != null && m.getUserId().equals(pId))
                .orElse(false);
    }

    private void sendError(WebSocketSession session, String msg) {
        try {
            synchronized (session) {
                session.sendMessage(new TextMessage(
                        objectMapper.writeValueAsString(Map.of("type", "error", "message", msg))));
            }
        } catch (Exception ignored) {}
    }

    private String attr(WebSocketSession session, String key) {
        Object v = session.getAttributes().get(key);
        return v != null ? v.toString() : null;
    }
}
