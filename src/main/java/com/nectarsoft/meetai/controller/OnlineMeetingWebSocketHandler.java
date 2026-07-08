package com.nectarsoft.meetai.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nectarsoft.meetai.core.websocket.OnlineRoomManager;
import com.nectarsoft.meetai.model.*;
import com.nectarsoft.meetai.repository.MeetingParticipantRepository;
import com.nectarsoft.meetai.repository.MeetingRepository;
import com.nectarsoft.meetai.repository.TranscriptRepository;
import com.nectarsoft.meetai.service.AssemblyAiStreamingManager;
import com.nectarsoft.meetai.service.LlmService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import java.nio.ByteBuffer;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.*;

@Slf4j
@Component
@RequiredArgsConstructor
public class OnlineMeetingWebSocketHandler extends AbstractWebSocketHandler {

    private final OnlineRoomManager roomManager;
    private final MeetingRepository meetingRepo;
    private final MeetingParticipantRepository participantRepo;
    private final TranscriptRepository transcriptRepo;
    private final LlmService llmService;
    private final AssemblyAiStreamingManager streamingManager;
    private final ObjectMapper objectMapper = new ObjectMapper();

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

        UUID pId = UUID.fromString(profileId);
        boolean isAdmin = pId.equals(meeting.getUserId());

        if (!isAdmin && (token == null || !token.equalsIgnoreCase(meeting.getInviteToken()))) {
            log.warn("[OnlineWS] 토큰 불일치 — meetingId={}, profileId={}", meetingId, profileId);
            session.close(CloseStatus.POLICY_VIOLATION);
            return;
        }

        session.setBinaryMessageSizeLimit(10 * 1024 * 1024);
        session.setTextMessageSizeLimit(64 * 1024);

        String role = isAdmin ? "ADMIN" : "GUEST";
        session.getAttributes().put("role", role);

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

        Map<String, Object> roomInfo = new LinkedHashMap<>();
        roomInfo.put("type", "room_info");
        roomInfo.put("status", meeting.getStatus().name());
        roomInfo.put("participants", new ArrayList<>(roomManager.getProfileIds(meetingId)));
        if (meeting.getMeetingDate() != null) {
            roomInfo.put("startedAt", meeting.getMeetingDate().toString());
        }
        synchronized (session) {
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(roomInfo)));
        }

        log.info("[OnlineWS] 연결 — meetingId={}, profileId={}, role={}", meetingId, profileId, role);
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        String meetingId = attr(session, "meetingId");
        String profileId = attr(session, "profileId");
        if (meetingId == null || profileId == null) return;

        ByteBuffer payload = message.getPayload();
        byte[] pcm = new byte[payload.remaining()];
        payload.get(pcm);

        try {
            streamingManager.sendAudio(meetingId, profileId, pcm);
        } catch (Exception e) {
            log.error("[OnlineWS] 오디오 스트리밍 오류 — meetingId={}, profileId={}: {}", meetingId, profileId, e.getMessage());
            // 예외를 전파하지 않음 → 1011 비정상 종료 방지
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
            case "start_meeting" -> {
                if (!isAdmin(meetingId, profileId)) { sendError(session, "권한이 없습니다."); return; }
                Meeting m = meetingRepo.findById(UUID.fromString(meetingId)).orElse(null);
                if (m != null) {
                    OffsetDateTime startedAt = OffsetDateTime.now();
                    m.setStatus(MeetingStatus.LIVE);
                    m.setMeetingDate(startedAt);
                    meetingRepo.save(m);
                    roomManager.broadcast(meetingId, objectMapper.writeValueAsString(Map.of(
                            "type", "meeting_started",
                            "startedAt", startedAt.toString()
                    )));
                    log.info("[OnlineWS] 회의 시작 — meetingId={}, startedAt={}", meetingId, startedAt);
                }
            }
            case "end_meeting" -> {
                if (!isAdmin(meetingId, profileId)) { sendError(session, "권한이 없습니다."); return; }
                endMeeting(meetingId);
            }
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

        // AssemblyAI 스트리밍 세션 종료
        streamingManager.endSession(meetingId, profileId);

        roomManager.leave(meetingId, profileId);
        try {
            roomManager.broadcastExcept(meetingId, profileId, objectMapper.writeValueAsString(
                    Map.of("type", "participant_left", "profileId", profileId)));
        } catch (Exception ignored) {}

        log.info("[OnlineWS] 연결 종료 — meetingId={}, profileId={}, status={}", meetingId, profileId, status);

        // 정상 종료는 end_meeting 이벤트만 담당 — 여기서는 비정상 종료(네트워크 단절 등) 처리
        // 마지막 참여자까지 모두 빠졌을 때 LIVE 상태 그대로 두면 안 되니 FAILED로 마감
        meetingRepo.findById(UUID.fromString(meetingId)).ifPresent(m -> {
            if (m.getStatus() == MeetingStatus.COMPLETED || m.getStatus() == MeetingStatus.FAILED) return;
            if (!roomManager.getProfileIds(meetingId).isEmpty()) return;
            streamingManager.endAllSessions(meetingId);
            if (m.getMeetingDate() != null) {
                m.setDurationSeconds((int) ChronoUnit.SECONDS.between(m.getMeetingDate(), OffsetDateTime.now()));
            }
            m.setStatus(MeetingStatus.FAILED);
            meetingRepo.save(m);
            log.warn("[OnlineWS] 비정상 종료 — 모든 참여자 이탈, meetingId={}", meetingId);
        });
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable ex) {
        log.error("[OnlineWS] 전송 오류 — meetingId={}: {}", attr(session, "meetingId"), ex.getMessage());
    }

    private void endMeeting(String meetingId) {
        UUID mid = UUID.fromString(meetingId);
        Meeting meeting = meetingRepo.findById(mid).orElse(null);
        if (meeting == null || meeting.getStatus() == MeetingStatus.COMPLETED) return;

        if (meeting.getMeetingDate() != null) {
            meeting.setDurationSeconds((int) ChronoUnit.SECONDS.between(meeting.getMeetingDate(), OffsetDateTime.now()));
        }
        meeting.setStatus(MeetingStatus.COMPLETED);
        meetingRepo.save(meeting);

        // 모든 참여자의 AssemblyAI 스트리밍 세션 종료
        streamingManager.endAllSessions(meetingId);

        try {
            roomManager.broadcast(meetingId, objectMapper.writeValueAsString(Map.of("type", "meeting_ended")));
        } catch (Exception ignored) {}
        roomManager.closeAll(meetingId);
        log.info("[OnlineWS] 회의 종료 — meetingId={}, duration={}s", meetingId, meeting.getDurationSeconds());

        List<Transcript> transcripts = transcriptRepo.findByMeetingMeetingIdOrderByStartSecAsc(mid);
        if (!transcripts.isEmpty()) llmService.summarizeAsync(mid, transcripts);
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
