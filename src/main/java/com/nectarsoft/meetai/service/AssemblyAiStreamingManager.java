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
 * 온라인 회의 참여자별 Whisper STT 세션 관리
 * - 참여자 첫 오디오 수신 시 세션 생성 (lazy)
 * - 5초 단위 배치 전송 → FinalTranscript: DB 저장 + 브로드캐스트
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
    private final ConcurrentHashMap<String, SttStreamSession> sessions = new ConcurrentHashMap<>();

    public void sendAudio(String meetingId, String profileId, byte[] pcm) {
        sessions.computeIfAbsent(key(meetingId, profileId), k -> createSession(meetingId, profileId))
                .sendAudio(pcm);
    }

    public void endSession(String meetingId, String profileId) {
        SttStreamSession s = sessions.remove(key(meetingId, profileId));
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
                    SttStreamSession s = sessions.remove(k);
                    if (s != null) s.close();
                });
        log.info("[StreamingMgr] 회의 전체 세션 종료 — meetingId={}", meetingId);
    }

    private SttStreamSession createSession(String meetingId, String profileId) {
        String resolvedDisplay = profileRepo.findById(UUID.fromString(profileId))
                .map(p -> p.getDisplayName() != null ? p.getDisplayName() : profileId.substring(0, 8))
                .orElse(profileId.substring(0, 8));

        // 회의 시작 시각 기준 누적 오프셋: 재연결 시에도 타임스탬프가 리셋되지 않도록 보정
        long sessionOffsetMs = meetingRepo.findById(UUID.fromString(meetingId))
                .filter(m -> m.getMeetingDate() != null)
                .map(m -> System.currentTimeMillis() - m.getMeetingDate().toInstant().toEpochMilli())
                .orElse(0L);

        String apiKey = props.getOpenai().getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.error("[StreamingMgr] OPENAI_API_KEY 환경변수가 설정되지 않았습니다");
            throw new IllegalStateException("OPENAI_API_KEY not configured");
        }

        // OpenAI Realtime (말하는 중 자막) 우선, 연결 실패 시 Whisper 배치 폴백
        try {
            RealtimeSttSession session = new RealtimeSttSession(
                    apiKey,
                    props.getOpenai().getRealtimeModel(),
                    props.getOpenai().getWhisperLanguage(),
                    sessionOffsetMs,
                    t -> onFinal(meetingId, profileId, resolvedDisplay, t),
                    text -> onPartial(meetingId, profileId, resolvedDisplay, text)
            );
            log.info("[StreamingMgr] Realtime 세션 생성 — meetingId={}, profileId={}, offsetMs={}",
                    meetingId, profileId, sessionOffsetMs);
            return session;
        } catch (Exception e) {
            log.warn("[StreamingMgr] Realtime 연결 실패 — Whisper 배치로 폴백: {}", e.getMessage());
        }

        WhisperStreamingSession session = new WhisperStreamingSession(
                apiKey,
                props.getOpenai().getWhisperModel(),
                props.getOpenai().getWhisperLanguage(),
                sessionOffsetMs,
                t -> onFinal(meetingId, profileId, resolvedDisplay, t)
        );
        log.info("[StreamingMgr] Whisper 세션 생성 — meetingId={}, profileId={}, offsetMs={}", meetingId, profileId, sessionOffsetMs);
        return session;
    }

    /** 말하는 중(partial) 자막 — DB 저장 없이 브로드캐스트만 */
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
            log.debug("[StreamingMgr] partial 브로드캐스트 실패: {}", e.getMessage());
        }
    }

    private void onFinal(String meetingId, String profileId, String display,
                         WhisperStreamingSession.Transcript t) {
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

    private String key(String meetingId, String profileId) {
        return meetingId + ":" + profileId;
    }
}
