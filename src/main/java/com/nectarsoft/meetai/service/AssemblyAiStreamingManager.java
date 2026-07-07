package com.nectarsoft.meetai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nectarsoft.meetai.config.MeetAiProperties;
import com.nectarsoft.meetai.core.websocket.OnlineRoomManager;
import com.nectarsoft.meetai.model.*;
import com.nectarsoft.meetai.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 온라인 회의 참여자별 AssemblyAI 실시간 스트리밍 세션 관리
 * - 참여자 첫 오디오 수신 시 세션 생성 (lazy)
 * - FinalTranscript: DB 저장 + 브로드캐스트
 * - PartialTranscript: 브로드캐스트 only (DB 저장 안 함)
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AssemblyAiStreamingManager {

    private final MeetAiProperties props;
    private final MeetingRepository meetingRepo;
    private final ProfileRepository profileRepo;
    private final SttResultRepository sttResultRepo;
    private final TranscriptRepository transcriptRepo;
    private final OnlineRoomManager roomManager;
    private final ObjectMapper objectMapper;

    // key: "meetingId:profileId"
    private final ConcurrentHashMap<String, AssemblyAiStreamingSession> sessions = new ConcurrentHashMap<>();

    public void sendAudio(String meetingId, String profileId, byte[] pcm) {
        sessions.computeIfAbsent(key(meetingId, profileId), k -> createSession(meetingId, profileId))
                .sendAudio(pcm);
    }

    public void endSession(String meetingId, String profileId) {
        AssemblyAiStreamingSession s = sessions.remove(key(meetingId, profileId));
        if (s != null) {
            s.close();
            log.info("[StreamingMgr] 세션 종료 — meetingId={}, profileId={}", meetingId, profileId);
        }
    }

    public void endAllSessions(String meetingId) {
        sessions.keySet().stream()
                .filter(k -> k.startsWith(meetingId + ":"))
                .toList()
                .forEach(k -> {
                    AssemblyAiStreamingSession s = sessions.remove(k);
                    if (s != null) s.close();
                });
        log.info("[StreamingMgr] 회의 전체 세션 종료 — meetingId={}", meetingId);
    }

    private AssemblyAiStreamingSession createSession(String meetingId, String profileId) {
        String resolvedDisplay = profileRepo.findById(UUID.fromString(profileId))
                .map(p -> p.getDisplayName() != null ? p.getDisplayName() : profileId.substring(0, 8))
                .orElse(profileId.substring(0, 8));

        try {
            AssemblyAiStreamingSession session = new AssemblyAiStreamingSession(
                    props.getAssemblyai().getApiKey(),
                    16000,
                    t -> onFinal(meetingId, profileId, resolvedDisplay, t),
                    text -> onPartial(meetingId, profileId, resolvedDisplay, text)
            );
            log.info("[StreamingMgr] 세션 생성 — meetingId={}, profileId={}", meetingId, profileId);
            return session;
        } catch (Exception e) {
            log.error("[StreamingMgr] 세션 생성 실패 — profileId={}: {}", profileId, e.getMessage());
            throw new RuntimeException(e);
        }
    }

    private void onFinal(String meetingId, String profileId, String display,
                         AssemblyAiStreamingSession.Transcript t) {
        try {
            Meeting meeting = meetingRepo.findById(UUID.fromString(meetingId)).orElse(null);
            if (meeting == null) return;

            SttResult sttResult = SttResult.builder()
                    .meeting(meeting)
                    .processingStatus(SttProcessingStatus.COMPLETED)
                    .processedAt(OffsetDateTime.now())
                    .build();
            sttResultRepo.save(sttResult);

            transcriptRepo.save(Transcript.builder()
                    .meeting(meeting)
                    .sttResult(sttResult)
                    .speakerLabel(profileId)
                    .speakerDisplay(display)
                    .startSec(t.startSec())
                    .endSec(t.endSec())
                    .content(t.text())
                    .build());

            roomManager.broadcast(meetingId, objectMapper.writeValueAsString(Map.of(
                    "type", "transcript",
                    "profileId", profileId,
                    "speakerDisplay", display,
                    "text", t.text(),
                    "startSec", t.startSec(),
                    "endSec", t.endSec(),
                    "isFinal", true
            )));
            log.info("[StreamingMgr] FinalTranscript — [{}] \"{}\"", display, t.text());
        } catch (Exception e) {
            log.error("[StreamingMgr] FinalTranscript 처리 실패: {}", e.getMessage());
        }
    }

    private void onPartial(String meetingId, String profileId, String display, String text) {
        try {
            roomManager.broadcast(meetingId, objectMapper.writeValueAsString(Map.of(
                    "type", "transcript",
                    "profileId", profileId,
                    "speakerDisplay", display,
                    "text", text,
                    "isFinal", false
            )));
        } catch (Exception e) {
            log.warn("[StreamingMgr] PartialTranscript 브로드캐스트 실패: {}", e.getMessage());
        }
    }

    private String key(String meetingId, String profileId) {
        return meetingId + ":" + profileId;
    }
}
