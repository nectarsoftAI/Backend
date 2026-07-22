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
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

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
    // A/B 지연 통계 — 세션과 같은 키. 세션 종료 시 요약을 남기고 정리한다
    private final ConcurrentHashMap<String, SttLatencyStats> latencyStats = new ConcurrentHashMap<>();

    // 자막 저장을 브로드캐스트에서 떼어내 비동기로 돌린다. 참가자별 세션이 동시에
    // 저장하므로 소규모 풀로 처리하고, 회의 종료 시 남은 작업을 드레인한다
    private final ExecutorService persistExecutor = Executors.newFixedThreadPool(4, r -> {
        Thread th = new Thread(r, "online-transcript-persist");
        th.setDaemon(true);
        return th;
    });
    private final ConcurrentHashMap<String, Queue<Future<?>>> pendingSaves = new ConcurrentHashMap<>();

    public void sendAudio(String meetingId, String profileId, byte[] pcm) {
        sessions.computeIfAbsent(key(meetingId, profileId), k -> createSession(meetingId, profileId))
                .sendAudio(pcm);
    }

    public void endSession(String meetingId, String profileId) {
        String k = key(meetingId, profileId);
        SttStreamSession s = sessions.remove(k);
        if (s != null) {
            s.close();
            log.info("[StreamingMgr] 세션 종료 — meetingId={}, profileId={}", meetingId, profileId);
        }
        flushStats(k);
    }

    public void endAllSessions(String meetingId) {
        sessions.keySet().stream()
                .filter(k -> k.startsWith(meetingId + ":"))
                .toList()
                .forEach(k -> {
                    SttStreamSession s = sessions.remove(k);
                    if (s != null) s.close();
                    flushStats(k);
                });
        // close()가 잔여 final을 플러시하며 저장 작업을 더 만들 수 있으므로 그 뒤에 드레인한다.
        // 호출부(endMeeting)가 이 직후 transcript를 읽어 요약하므로 여기서 기다려야 누락이 없다
        awaitPendingSaves(meetingId);
        log.info("[StreamingMgr] 회의 전체 세션 종료 — meetingId={}", meetingId);
    }

    /** 세션 종료 시 A/B 지연 요약 1회 출력 후 정리 */
    private void flushStats(String key) {
        SttLatencyStats s = latencyStats.remove(key);
        if (s != null) s.logSummary();
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

        // 온라인 STT를 Deepgram으로 (DEEPGRAM_ONLINE_ENABLED=true) — 참가자별 스트림이라 화자분리 불필요(diarize=false).
        // 온라인 오디오는 raw PCM 16kHz mono라 ffmpeg 없이 Deepgram(linear16)에 직송. 실패 시 아래 OpenAI로 폴백.
        MeetAiProperties.Deepgram dg = props.getDeepgram();
        if (dg.isOnlineEnabled() && dg.getApiKey() != null && !dg.getApiKey().isBlank()) {
            try {
                double offsetSec = sessionOffsetMs / 1000.0; // Deepgram 타임스탬프(스트림 기준) → 회의 기준 보정
                SttLatencyStats dgStats = newStats("deepgram", meetingId, profileId, resolvedDisplay);
                java.util.concurrent.atomic.AtomicReference<DeepgramStreamingSession> ref =
                        new java.util.concurrent.atomic.AtomicReference<>();
                DeepgramStreamingSession session = new DeepgramStreamingSession(
                        dg.getApiKey(), dg.getModel(), dg.getLanguage(), 16000,
                        dg.getEndpointingMs(), dg.isPartials(), false, // 화자분리 off
                        seg -> {
                            recordDeepgramLatency(dgStats, seg, ref.get());
                            if (seg.isFinal()) {
                                onFinal(meetingId, profileId, resolvedDisplay,
                                        new WhisperStreamingSession.Transcript(
                                                seg.text(), seg.startSec() + offsetSec, seg.endSec() + offsetSec));
                            } else {
                                onPartial(meetingId, profileId, resolvedDisplay, seg.text());
                            }
                        });
                ref.set(session); // 콜백에서 세션의 오디오 시계를 읽기 위한 순환 참조 해소
                log.info("[StreamingMgr] Deepgram 온라인 세션 생성 — meetingId={}, profileId={}", meetingId, profileId);
                return session;
            } catch (Exception e) {
                log.warn("[StreamingMgr] Deepgram 온라인 연결 실패 — OpenAI로 폴백: {}", e.getMessage());
            }
        }

        String apiKey = props.getOpenai().getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            log.error("[StreamingMgr] OPENAI_API_KEY 환경변수가 설정되지 않았습니다");
            throw new IllegalStateException("OPENAI_API_KEY not configured");
        }

        // OpenAI Realtime (말하는 중 자막) — realtime-enabled=true이고 ffmpeg가 있을 때만.
        // ffmpeg 없는 환경(로컬 개발 등)은 Whisper 배치로 — webm 직접 업로드/순수 Java VAD 경로라 ffmpeg 불필요
        if (props.getOpenai().isRealtimeEnabled() && FfmpegPcmDecoder.isAvailable()) try {
            RealtimeSttSession session = new RealtimeSttSession(
                    apiKey,
                    props.getOpenai().getRealtimeModel(),
                    props.getOpenai().getWhisperLanguage(),
                    sessionOffsetMs,
                    newStats("openai-realtime", meetingId, profileId, resolvedDisplay),
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

    /** 세션과 같은 키로 A/B 통계를 만들어 보관 — 종료 시 요약을 남긴다 */
    private SttLatencyStats newStats(String engine, String meetingId, String profileId, String display) {
        SttLatencyStats s = new SttLatencyStats(engine, display, props.getOpenai().isLatencyLog());
        latencyStats.put(key(meetingId, profileId), s);
        return s;
    }

    /**
     * Deepgram 세그먼트를 A/B 공통 지표로 환산.
     * 총 지연은 벽시계가 아니라 오디오 시계(보낸 오디오 길이 − 세그먼트 종료 지점) 기준이라
     * 프론트/서버 시계 오차와 무관하고, OpenAI 경로의 finalMs와 같은 의미가 된다.
     */
    private void recordDeepgramLatency(SttLatencyStats stats, DeepgramStreamingSession.Segment seg,
                                       DeepgramStreamingSession session) {
        if (stats == null || session == null || seg.timing() == null) return;

        if (seg.isFinal()) {
            // finalMs: 발화 종료 → 확정 방출 / engineMs: Deepgram 왕복(추론 포함) 근사
            long finalMs = seg.endSec() > 0
                    ? Math.round((session.sentAudioSec() - seg.endSec()) * 1000) : -1;
            if (finalMs >= 60_000) finalMs = -1; // 재접속 등으로 원점이 어긋난 값은 버린다
            stats.recordFinal(finalMs, seg.timing().deepgramGapMs(), seg.text());
        } else {
            // firstMs: 발화 시작 → 첫 partial.
            // Deepgram은 한 턴에 partial을 여러 번 보내므로 턴당 첫 건만 기록한다
            // (매번 기록하면 같은 턴의 후속 partial이 큰 값으로 쌓여 분포가 왜곡된다)
            if (seg.startSec() > 0) {
                stats.recordFirstOnce(seg.startSec(),
                        Math.round((session.sentAudioSec() - seg.startSec()) * 1000));
            }
        }
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

    /**
     * 확정 자막 처리 — 브로드캐스트 먼저, DB 저장은 비동기.
     *
     * 이전에는 SttResult + Transcript 저장을 끝낸 뒤에야 브로드캐스트해서,
     * 자막이 Supabase 왕복만큼 늦게 화면에 떴다. 실측 P50이 945ms로
     * STT 지연(finalMs P50 643ms)보다 컸다 — 엔진을 바꿔 얻는 이득보다 큰 손실이라
     * 순서를 뒤집는다. 자막 내용은 이미 메모리에 있으므로 저장을 기다릴 이유가 없다.
     *
     * 대신 회의 종료 시 요약이 아직 저장되지 않은 세그먼트를 놓칠 수 있어,
     * endAllSessions에서 대기 중인 저장을 모두 드레인한 뒤 반환한다.
     */
    private void onFinal(String meetingId, String profileId, String display,
                         WhisperStreamingSession.Transcript t) {
        long t5 = System.nanoTime();
        try {
            roomManager.broadcast(meetingId, objectMapper.writeValueAsString(Map.of(
                    "type", "transcript",
                    "profileId", profileId,
                    "speakerDisplay", display,
                    "text", t.text(),
                    "startSec", t.startSec(),
                    "endSec", t.endSec(),
                    "isFinal", true
            )));
        } catch (Exception e) {
            log.error("[StreamingMgr] 확정 자막 브로드캐스트 실패: {}", e.getMessage());
        }
        long bcMs = (System.nanoTime() - t5) / 1_000_000;
        log.info("[StreamingMgr] FinalTranscript — [{}] \"{}\"", display, t.text());

        Future<?> f = persistExecutor.submit(() -> persistTranscript(meetingId, profileId, display, t, bcMs));
        pendingSaves.computeIfAbsent(meetingId, k -> new ConcurrentLinkedQueue<>()).add(f);
    }

    /** 비동기 저장 — 사용자는 이미 자막을 받았으므로 여기서 걸리는 시간은 체감에 영향이 없다 */
    private void persistTranscript(String meetingId, String profileId, String display,
                                   WhisperStreamingSession.Transcript t, long bcMs) {
        long start = System.nanoTime();
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

            if (props.getOpenai().isLatencyLog()) {
                log.info("[STT계측-온라인] t5 [{}] 브로드캐스트 {}ms | DB저장 {}ms (비동기, 체감 지연 아님)",
                        display, bcMs, (System.nanoTime() - start) / 1_000_000);
            }
        } catch (Exception e) {
            log.error("[StreamingMgr] 자막 저장 실패 — meetingId={}: {}", meetingId, e.getMessage());
        }
    }

    /**
     * 대기 중인 비동기 저장을 모두 기다린다.
     * 회의 종료 직후 요약이 transcript를 읽으므로, 여기서 드레인하지 않으면
     * 아직 저장되지 않은 마지막 발화들이 요약에서 빠진다.
     */
    private void awaitPendingSaves(String meetingId) {
        Queue<Future<?>> q = pendingSaves.remove(meetingId);
        if (q == null || q.isEmpty()) return;
        long deadline = System.currentTimeMillis() + 15_000;
        int done = 0;
        for (Future<?> f : q) {
            try {
                f.get(Math.max(0, deadline - System.currentTimeMillis()), TimeUnit.MILLISECONDS);
                done++;
            } catch (Exception e) {
                log.warn("[StreamingMgr] 자막 저장 대기 실패 — meetingId={}: {}", meetingId, e.toString());
            }
        }
        log.info("[StreamingMgr] 자막 저장 드레인 완료 — meetingId={}, {}/{}건", meetingId, done, q.size());
    }

    private String key(String meetingId, String profileId) {
        return meetingId + ":" + profileId;
    }
}
