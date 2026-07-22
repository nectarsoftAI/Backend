package com.nectarsoft.meetai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nectarsoft.meetai.config.MeetAiProperties;
import com.nectarsoft.meetai.core.exception.Exceptions;
import com.nectarsoft.meetai.core.websocket.OnlineRoomManager;
import com.nectarsoft.meetai.model.*;
import com.nectarsoft.meetai.repository.MeetingRepository;
import com.nectarsoft.meetai.repository.SttResultRepository;
import com.nectarsoft.meetai.repository.TranscriptRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 실시간 녹음 세션 (한 마이크, 다중 화자)
 * - 실시간 자막 엔진 우선순위: Deepgram 스트리밍(deepgram.enabled) >
 *   Speechmatics 스트리밍(speechmatics.enabled) > OpenAI 롤링 배치(기본, ~15초 지연)
 * - 오디오는 항상 파일로도 누적 (스트리밍 실패 시 배치 폴백 + 보관용)
 * - 종료 시 스트리밍 결과가 있으면 그대로 회의록 저장, 없으면 전체 오디오 배치 변환
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LiveService {

    private static final int SAMPLE_RATE = 16000;
    // 짧을수록 자막 지연이 줄지만 전체 오디오를 매번 재처리하므로 API 비용 증가
    private static final int ROLLING_INTERVAL_SEC = 6;
    private static final String TRANSCRIBE_URL = "https://api.openai.com/v1/audio/transcriptions";
    private static final String DIARIZE_MODEL = "gpt-4o-transcribe-diarize";

    private final MeetAiProperties props;
    private final MeetingRepository meetingRepo;
    private final SttResultRepository sttResultRepo;
    private final TranscriptRepository transcriptRepo;
    private final OnlineRoomManager roomManager;
    private final ObjectMapper objectMapper;
    private final LlmService llmService;
    private final JdbcTemplate jdbcTemplate;

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build();

    private final Map<String, Recording> recordings = new ConcurrentHashMap<>();
    // Speechmatics 스트리밍 세션 (speechmatics.enabled일 때만 사용) — meetingId별 1개
    private final Map<String, SpeechmaticsLiveSession> liveSessions = new ConcurrentHashMap<>();
    // Deepgram 스트리밍 세션 (deepgram.enabled일 때 Speechmatics보다 우선) — meetingId별 1개.
    // 연결 실패한 회의는 dgFailed에 기록해 청크마다 재연결을 시도하지 않는다
    private final Map<String, DeepgramStreamingSession> dgSessions = new ConcurrentHashMap<>();
    private final java.util.Set<String> dgFailed = ConcurrentHashMap.newKeySet();

    private final ScheduledExecutorService diarizeExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "live-diarize");
        t.setDaemon(true);
        return t;
    });

    private record DiarizedSegment(String speaker, String text, double start, double end) {}

    private static class Recording {
        OutputStream out;
        Path path;
        boolean pcm;
        long bytes;
        long lastDiarizedBytes;
        double lastBroadcastEnd;
    }

    @PostConstruct
    void startRollingDiarize() {
        diarizeExecutor.scheduleWithFixedDelay(this::rollingPass,
                ROLLING_INTERVAL_SEC, ROLLING_INTERVAL_SEC, TimeUnit.SECONDS);
    }

    public Meeting createSession(String title, UUID profileId) {
        Meeting meeting = Meeting.builder()
                .userId(profileId)
                .title(title)
                .meetingType(MeetingType.REALTIME)
                .status(MeetingStatus.LIVE)
                .meetingDate(OffsetDateTime.now())
                .build();
        meetingRepo.save(meeting);
        log.info("[Live] 세션 생성 — meetingId={}", meeting.getMeetingId());
        return meeting;
    }

    /** WS로 수신한 오디오 청크 누적 (webm은 그대로, raw PCM은 WAV 래핑해 사용) */
    public void appendAudio(String meetingId, byte[] data) {
        long t1 = System.nanoTime(); // 오디오 청크 수신 시각(계측 기준점)
        try {
            Recording r = recordings.computeIfAbsent(meetingId, k -> openRecording(k, data));
            synchronized (r) {
                r.out.write(data);
                r.bytes += data.length;
            }
        } catch (Exception e) {
            log.warn("[Live] 녹음 누적 실패 — meetingId={}: {}", meetingId, e.getMessage());
        }
        // 실시간 스트리밍 STT (우선순위: Deepgram > Speechmatics) —
        // 파일 누적과 별개로 같은 청크를 전송, 파일은 종료 시 폴백/보관용
        Recording rec = recordings.get(meetingId);
        boolean pcm = rec != null && rec.pcm;
        if (deepgramActive() && pcm) {
            if (!dgFailed.contains(meetingId)) {
                DeepgramStreamingSession dg = dgSessions.computeIfAbsent(meetingId, this::openDeepgramSession);
                if (dg != null) {
                    dg.sendAudio(data);
                    logIngestLatency(meetingId, t1);
                }
            }
        } else if (speechmaticsActive()) {
            try {
                liveSessionFor(meetingId).sendAudio(data);
            } catch (Exception e) {
                log.warn("[Live] Speechmatics 스트리밍 실패 — meetingId={}: {}", meetingId, e.getMessage());
            }
        }
    }

    private boolean speechmaticsActive() {
        MeetAiProperties.Speechmatics s = props.getSpeechmatics();
        return s.isEnabled() && s.getApiKey() != null && !s.getApiKey().isBlank();
    }

    private boolean deepgramActive() {
        MeetAiProperties.Deepgram d = props.getDeepgram();
        return d.isEnabled() && d.getApiKey() != null && !d.getApiKey().isBlank();
    }

    private DeepgramStreamingSession openDeepgramSession(String meetingId) {
        try {
            MeetAiProperties.Deepgram cfg = props.getDeepgram();
            log.info("[Live] Deepgram 스트리밍 세션 시작 — meetingId={}", meetingId);
            return new DeepgramStreamingSession(cfg.getApiKey(), cfg.getModel(), cfg.getLanguage(),
                    SAMPLE_RATE, cfg.getEndpointingMs(), cfg.isPartials(), true, // 오프라인은 화자분리 필요
                    seg -> broadcastDeepgramSegment(meetingId, seg));
        } catch (Exception e) {
            dgFailed.add(meetingId);
            log.error("[Live] Deepgram 연결 실패 — Speechmatics/롤링으로 폴백 불가(이 회의는 종료 시 배치 처리), meetingId={}: {}",
                    meetingId, e.getMessage());
            return null;
        }
    }

    /** Deepgram 자막을 프론트(LiveSTTService.ts) segment 스펙으로 브로드캐스트 */
    private void broadcastDeepgramSegment(String meetingId, DeepgramStreamingSession.Segment seg) {
        long t5 = System.nanoTime(); // 브로드캐스트 진입(콜백 도착) 시각
        try {
            roomManager.broadcast(meetingId, objectMapper.writeValueAsString(Map.of(
                    "type", "segment",
                    "speaker_label", seg.speakerLabel(),
                    "start_sec", seg.startSec(),
                    "end_sec", seg.endSec(),
                    "text", seg.text(),
                    "confidence", seg.confidence(),
                    "is_final", seg.isFinal())));   // false=말하는 중 미리보기(교체), true=확정
            logSegmentLatency(meetingId, seg, t5);
        } catch (Exception e) {
            log.debug("[Live] Deepgram 자막 브로드캐스트 실패: {}", e.getMessage());
        }
    }

    // ── STT 지연 계측 ────────────────────────────────────────────────
    // 구간: t1 청크 수신 → t2 Deepgram 전송 → t3 결과 수신 → t4 턴 확정 → t5 브로드캐스트.
    // t2→t3 안에는 Deepgram 내부 추론이 포함되며 우리가 분리할 수 없다(블랙박스).
    // 총 지연은 벽시계가 아니라 "수신한 오디오 길이 − 세그먼트 종료 지점"으로 구한다.
    // 샘플 수는 절대 기준이라 프론트/서버 시계 오차와 무관하다.
    private final Map<String, java.util.concurrent.atomic.AtomicLong> ingestCounters = new ConcurrentHashMap<>();
    private final Map<String, java.util.concurrent.atomic.AtomicLong> partialCounters = new ConcurrentHashMap<>();

    /** t1→t2: 청크 수신 → Deepgram 전송 완료. 250ms마다 오므로 40개(약 10초)에 한 번만 남긴다 */
    private void logIngestLatency(String meetingId, long t1) {
        if (!props.getDeepgram().isLatencyLog()) return;
        long n = ingestCounters.computeIfAbsent(meetingId, k -> new java.util.concurrent.atomic.AtomicLong())
                .incrementAndGet();
        if (n % 40 != 0) return;
        log.info("[STT계측] t1→t2 청크 수신·전송 {}ms (누적 {}청크)",
                String.format("%.2f", (System.nanoTime() - t1) / 1_000_000.0), n);
    }

    /** t2→t5 구간별 + 오디오 시계 기준 총 지연. final은 전부, partial은 10개당 1개만 남긴다 */
    private void logSegmentLatency(String meetingId, DeepgramStreamingSession.Segment seg, long t5) {
        if (!props.getDeepgram().isLatencyLog() || seg.timing() == null) return;
        if (!seg.isFinal()) {
            long n = partialCounters.computeIfAbsent(meetingId, k -> new java.util.concurrent.atomic.AtomicLong())
                    .incrementAndGet();
            if (n % 10 != 0) return;
        }

        DeepgramStreamingSession.Timing tm = seg.timing();
        long callbackMs = (t5 - tm.emitNanos()) / 1_000_000;      // t4→t5
        long sendMs = (System.nanoTime() - t5) / 1_000_000;       // 직렬화 + WS 전송

        // 총 지연 = 지금까지 받은 오디오 길이 − 이 세그먼트의 마지막 단어 지점
        String total = "n/a";
        Recording r = recordings.get(meetingId);
        if (r != null && seg.endSec() > 0) {
            double audioSec = r.bytes / (double) (SAMPLE_RATE * 2); // 16kHz × 16bit mono
            long ms = Math.round((audioSec - seg.endSec()) * 1000);
            if (ms >= 0) total = ms + "ms";
        }

        if (seg.isFinal()) {
            log.info("[STT계측] final   [{}] t2→t3 {}ms | t3→t4 턴대기 {}ms (턴전체 {}ms) | t4→t5 {}ms | 전송 {}ms | 총지연 {} | \"{}\"",
                    seg.speakerLabel(), tm.deepgramGapMs(), tm.turnHoldMs(), tm.turnSpanMs(),
                    callbackMs, sendMs, total, seg.text());
        } else {
            log.info("[STT계측] partial [{}] t2→t3 {}ms | t4→t5 {}ms | 전송 {}ms | 총지연 {}",
                    seg.speakerLabel(), tm.deepgramGapMs(), callbackMs, sendMs, total);
        }
    }

    private SpeechmaticsLiveSession liveSessionFor(String meetingId) {
        return liveSessions.computeIfAbsent(meetingId, k -> {
            MeetAiProperties.Speechmatics cfg = props.getSpeechmatics();
            log.info("[Live] Speechmatics 스트리밍 세션 시작 — meetingId={}", k);
            return new SpeechmaticsLiveSession(cfg.getApiKey(), cfg.getUrl(), cfg.getLanguage(),
                    cfg.getMaxDelaySec(), cfg.getOperatingPoint(), cfg.getSpeakerSensitivity(),
                    cfg.isNoiseGate(), cfg.getNoiseGateThreshold(), cfg.isPartials(),
                    seg -> broadcastLiveSegment(k, seg));
        });
    }

    /** Speechmatics 확정 자막을 프론트(LiveSTTService.ts) segment 스펙으로 브로드캐스트 */
    private void broadcastLiveSegment(String meetingId, SpeechmaticsLiveSession.Segment seg) {
        try {
            roomManager.broadcast(meetingId, objectMapper.writeValueAsString(Map.of(
                    "type", "segment",
                    "speaker_label", seg.speakerLabel(),
                    "start_sec", seg.startSec(),
                    "end_sec", seg.endSec(),
                    "text", seg.text(),
                    "confidence", 1.0,
                    "is_final", seg.isFinal())));   // false=말하는 중 미리보기(교체), true=확정
        } catch (Exception e) {
            log.debug("[Live] Speechmatics 자막 브로드캐스트 실패: {}", e.getMessage());
        }
    }

    public void endSession(String meetingId) {
        UUID mid = UUID.fromString(meetingId);
        Meeting meeting = meetingRepo.findById(mid)
                .orElseThrow(() -> new Exceptions.SessionNotFoundError(meetingId));

        // COMPLETED = 이미 종료 / PROCESSING = 최종 변환 중 — 중복 종료 방지
        if (meeting.getStatus() == MeetingStatus.COMPLETED
                || meeting.getStatus() == MeetingStatus.PROCESSING) return;

        if (meeting.getMeetingDate() != null) {
            meeting.setDurationSeconds(
                    (int) ChronoUnit.SECONDS.between(meeting.getMeetingDate(), OffsetDateTime.now()));
        }

        Path recording = finishRecording(meetingId);
        SpeechmaticsLiveSession live = liveSessions.remove(meetingId);
        DeepgramStreamingSession dg = dgSessions.remove(meetingId);
        dgFailed.remove(meetingId);
        ingestCounters.remove(meetingId);   // 계측 카운터도 회의와 함께 정리
        partialCounters.remove(meetingId);
        if (recording != null || live != null || dg != null) {
            // A안: 화면은 즉시 종료(session_ended + WS close), 최종 화자 분리는 백그라운드로.
            // 프론트는 GET /meetings/{id}의 status가 PROCESSING→COMPLETED 될 때 완성 회의록을 폴링한다.
            meeting.setStatus(MeetingStatus.PROCESSING);
            meetingRepo.save(meeting);
            notifySessionEnded(meetingId);
            diarizeExecutor.submit(() -> finalizeTranscripts(mid, recording, live, dg));
            log.info("[Live] 세션 종료(즉시) — 최종 화자 분리는 백그라운드 진행, meetingId={}", meetingId);
            return;
        }

        // 녹음본이 없으면 바로 완료 처리
        meeting.setStatus(MeetingStatus.COMPLETED);
        meetingRepo.save(meeting);
        notifySessionEnded(meetingId);
        log.info("[Live] 세션 종료 완료 — meetingId={}", meetingId);
    }

    /** 프론트 스펙: 서버가 session_ended를 보낸 뒤 WS를 닫는다 (프론트는 이걸 기다림) */
    private void notifySessionEnded(String meetingId) {
        try {
            roomManager.broadcast(meetingId, objectMapper.writeValueAsString(Map.of("type", "session_ended")));
        } catch (Exception ignored) {}
        roomManager.closeAll(meetingId);
    }

    // ── 준실시간: 주기적으로 누적 오디오를 화자 분리해 새 구간만 브로드캐스트 ──

    private void rollingPass() {
        // 스트리밍 엔진(Deepgram/Speechmatics)이 켜져 있으면 실시간 자막은 스트리밍 세션이 담당 → 롤링 배치 생략
        if (deepgramActive() || speechmaticsActive()) return;
        for (Map.Entry<String, Recording> entry : recordings.entrySet()) {
            String meetingId = entry.getKey();
            Recording r = entry.getValue();
            try {
                byte[] snapshot;
                synchronized (r) {
                    if (r.bytes < SAMPLE_RATE || r.bytes == r.lastDiarizedBytes) continue;
                    r.out.flush();
                    r.lastDiarizedBytes = r.bytes;
                }
                snapshot = snapshotAudio(r);
                if (snapshot == null) continue;

                List<DiarizedSegment> segments = diarize(snapshot, r.pcm ? "audio.wav" : "audio.webm");
                broadcastNewSegments(meetingId, r, segments);
            } catch (Exception e) {
                log.warn("[Live] 롤링 화자 분리 실패 — meetingId={}: {}", meetingId, e.getMessage());
            }
        }
    }

    private void broadcastNewSegments(String meetingId, Recording r, List<DiarizedSegment> segments) {
        // 마지막 세그먼트는 발화가 진행 중일 수 있으므로 보류 (다음 패스나 최종 변환에서 확정)
        List<DiarizedSegment> stable = segments.size() > 1
                ? segments.subList(0, segments.size() - 1) : List.of();
        for (DiarizedSegment seg : stable) {
            if (seg.start() < r.lastBroadcastEnd - 0.3) continue; // 이미 방송된 구간
            String text = seg.text().strip();
            if (text.isEmpty()) continue;
            String label = "SPEAKER_" + seg.speaker();
            try {
                // 프론트(LiveSTTService.ts) 스펙: type=segment + snake_case 필드
                roomManager.broadcast(meetingId, objectMapper.writeValueAsString(Map.of(
                        "type", "segment",
                        "speaker_label", label,
                        "start_sec", seg.start(),
                        "end_sec", seg.end(),
                        "text", text,
                        "confidence", 1.0
                )));
                r.lastBroadcastEnd = Math.max(r.lastBroadcastEnd, seg.end());
                log.info("[Live] 화자 자막 — [{}] \"{}\"", label, text);
            } catch (Exception e) {
                log.debug("[Live] 자막 브로드캐스트 실패: {}", e.getMessage());
            }
        }
    }

    // ── 종료: 전체 오디오 최종 변환 → 회의록 저장 ──────────────────────

    private void finalizeTranscripts(UUID mid, Path audio, SpeechmaticsLiveSession live,
                                     DeepgramStreamingSession dg) {
        Meeting meeting = meetingRepo.findById(mid).orElse(null);
        if (meeting == null) return;
        try {
            List<DiarizedSegment> segments = collectFinalSegments(mid, audio, live, dg);
            if (segments.isEmpty()) {
                log.warn("[Live] 변환 결과 없음 — meetingId={}", mid);
                return;
            }

            SttResult sttResult = SttResult.builder()
                    .meeting(meeting)
                    .processingStatus(SttProcessingStatus.COMPLETED)
                    .processedAt(OffsetDateTime.now())
                    .build();
            sttResultRepo.save(sttResult);

            transcriptRepo.deleteAll(transcriptRepo.findByMeetingMeetingIdOrderByStartSecAsc(mid));
            List<Transcript> transcripts = new ArrayList<>();
            for (DiarizedSegment seg : segments) {
                String text = seg.text().strip();
                if (text.isEmpty()) continue;
                String label = "SPEAKER_" + seg.speaker();
                transcripts.add(Transcript.builder()
                        .meeting(meeting)
                        .sttResult(sttResult)
                        .speakerLabel(label)
                        .speakerDisplay(label)
                        .startSec(seg.start())
                        .endSec(seg.end())
                        .content(text)
                        .build());
            }
            batchInsertTranscripts(mid, sttResult.getSttId(), transcripts);

            long speakers = segments.stream().map(DiarizedSegment::speaker).distinct().count();
            log.info("[Live] 최종 변환 완료 — meetingId={}, segments={}, 화자 {}명",
                    mid, transcripts.size(), speakers);

            // 종료 즉시 최종 요약을 백그라운드로 생성(@Async) — 프론트 /summarize 폴링 전에 미리 시작해
            // 대기 시간을 줄인다. (온라인 회의의 endMeeting → summarizeAsync와 동일한 패턴)
            if (!transcripts.isEmpty()) {
                llmService.refreshSummaryAsync(mid, transcripts);
                log.info("[Live] 종료 직후 최종 요약 생성 시작 — meetingId={}", mid);
            }
        } catch (Exception e) {
            log.error("[Live] 최종 변환 실패 — meetingId={}: {}", mid, e.getMessage());
        } finally {
            // WS는 endSession에서 이미 닫음. 여기서는 상태만 COMPLETED로 전환 (프론트가 폴링으로 감지)
            meeting.setStatus(MeetingStatus.COMPLETED);
            meetingRepo.save(meeting);
            log.info("[Live] 백그라운드 처리 완료 — status=COMPLETED, meetingId={}", mid);
        }
    }

    /**
     * transcript 벌크 저장 — JPA saveAll은 IDENTITY 키라 행마다 INSERT+생성키 조회로 왕복해
     * 원격 DB(Supabase)에서 127행 ≈ 19초 병목이었다. JDBC 배치로 묶어 왕복을 수 회로 줄인다(≈1초).
     * 저장 후 생성 ID는 쓰지 않으므로(수정은 이후 재조회로 처리) 키 조회를 생략해도 무방하다.
     */
    private void batchInsertTranscripts(UUID meetingId, Long sttId, List<Transcript> transcripts) {
        if (transcripts.isEmpty()) return;
        jdbcTemplate.batchUpdate(
                "INSERT INTO transcripts "
                        + "(meeting_id, stt_id, speaker_label, speaker_display, start_sec, end_sec, content) "
                        + "VALUES (?, ?, ?, ?, ?, ?, ?)",
                transcripts, 100,
                (ps, t) -> {
                    ps.setObject(1, meetingId);
                    ps.setLong(2, sttId);
                    ps.setString(3, t.getSpeakerLabel());
                    ps.setString(4, t.getSpeakerDisplay());
                    if (t.getStartSec() != null) ps.setDouble(5, t.getStartSec()); else ps.setNull(5, Types.DOUBLE);
                    if (t.getEndSec() != null) ps.setDouble(6, t.getEndSec()); else ps.setNull(6, Types.DOUBLE);
                    ps.setString(7, t.getContent());
                });
    }

    /**
     * 최종 세그먼트 확보 — 우선순위:
     * 1) Deepgram 스트리밍 결과(있으면 그대로 사용, 추가 API 호출 없음)
     * 2) Speechmatics 스트리밍 결과(위와 동일)
     * 3) OpenAI gpt-4o-transcribe-diarize 배치(스트리밍 미사용 또는 결과 없음)
     * 4) 일반 전사(단일 화자) — diarize 실패 시 DB 공백 방지
     */
    private List<DiarizedSegment> collectFinalSegments(UUID mid, Path audio,
                                                       SpeechmaticsLiveSession live,
                                                       DeepgramStreamingSession dg) {
        if (dg != null) {
            try {
                dg.close();          // CloseStream → 서버가 잔여 확정 자막 플러시
                dg.awaitClose(3000); // 플러시 도착 대기
                List<DiarizedSegment> segs = dg.getSegments().stream()
                        .map(s -> new DiarizedSegment(
                                s.speakerLabel().replaceFirst("^SPEAKER_", ""), // 저장 루프가 다시 SPEAKER_ 접두
                                s.text(), s.startSec(), s.endSec()))
                        .collect(Collectors.toList());
                if (!segs.isEmpty()) {
                    log.info("[Live] Deepgram 스트리밍 결과로 회의록 확정 — meetingId={}, segments={}",
                            mid, segs.size());
                    return segs;
                }
                log.warn("[Live] Deepgram 결과 없음 — 다음 폴백, meetingId={}", mid);
            } catch (Exception e) {
                log.error("[Live] Deepgram 종료 처리 실패 — 다음 폴백, meetingId={}: {}", mid, e.getMessage());
            }
        }
        if (live != null) {
            try {
                live.close(); // EndOfStream 후 남은 확정 자막까지 수신
                List<DiarizedSegment> segs = live.getSegments().stream()
                        .map(s -> new DiarizedSegment(
                                s.speakerLabel().replaceFirst("^SPEAKER_", ""), // 저장 루프가 다시 SPEAKER_ 접두
                                s.text(), s.startSec(), s.endSec()))
                        .collect(Collectors.toList());
                if (!segs.isEmpty()) {
                    log.info("[Live] Speechmatics 스트리밍 결과로 회의록 확정 — meetingId={}, segments={}",
                            mid, segs.size());
                    return segs;
                }
                log.warn("[Live] Speechmatics 결과 없음 — 배치 폴백, meetingId={}", mid);
            } catch (Exception e) {
                log.error("[Live] Speechmatics 종료 처리 실패 — 배치 폴백, meetingId={}: {}", mid, e.getMessage());
            }
        }
        if (audio == null) return List.of();
        try {
            byte[] bytes = Files.readAllBytes(audio);
            String filename = audio.getFileName().toString();
            try {
                return diarize(bytes, filename);
            } catch (Exception e) {
                // 화자 분리 실패해도 회의록 텍스트는 반드시 남긴다 (DB 공백 방지)
                log.error("[Live] 화자 분리 실패 — 단일 화자 전사로 폴백, meetingId={}: {}", mid, e.getMessage());
                return plainTranscribe(bytes, filename);
            }
        } catch (IOException e) {
            log.error("[Live] 최종 오디오 읽기 실패 — meetingId={}: {}", mid, e.getMessage());
            return List.of();
        }
    }

    /** 화자 분리 실패 시 폴백: 일반 전사(gpt-4o-transcribe)로 텍스트만 확보 → 단일 화자로 저장 */
    private List<DiarizedSegment> plainTranscribe(byte[] audio, String filename) {
        try {
            RequestBody body = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", filename,
                            RequestBody.create(audio, MediaType.parse("application/octet-stream")))
                    .addFormDataPart("model", "gpt-4o-transcribe")
                    .addFormDataPart("response_format", "json")
                    .build();
            Request request = new Request.Builder()
                    .url(TRANSCRIBE_URL)
                    .header("Authorization", "Bearer " + props.getOpenai().getApiKey())
                    .post(body)
                    .build();
            try (Response response = http.newCall(request).execute()) {
                String raw = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    log.error("[Live] 폴백 전사도 실패 {}: {}", response.code(), raw);
                    return List.of();
                }
                String text = objectMapper.readTree(raw).path("text").asText("").strip();
                return text.isEmpty() ? List.of() : List.of(new DiarizedSegment("A", text, 0, 0));
            }
        } catch (Exception e) {
            log.error("[Live] 폴백 전사 오류: {}", e.getMessage());
            return List.of();
        }
    }

    // ── OpenAI gpt-4o-transcribe-diarize 호출 ─────────────────────────

    private List<DiarizedSegment> diarize(byte[] audio, String filename) throws IOException {
        RequestBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", filename,
                        RequestBody.create(audio, MediaType.parse("application/octet-stream")))
                .addFormDataPart("model", DIARIZE_MODEL)
                .addFormDataPart("response_format", "diarized_json")
                // diarize 모델은 일정 길이 이상이면 chunking_strategy 필수 (없으면 400)
                .addFormDataPart("chunking_strategy", "auto")
                .build();
        Request request = new Request.Builder()
                .url(TRANSCRIBE_URL)
                .header("Authorization", "Bearer " + props.getOpenai().getApiKey())
                .post(body)
                .build();

        try (Response response = http.newCall(request).execute()) {
            String raw = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("diarize 요청 실패 " + response.code() + ": " + raw);
            }
            JsonNode root = objectMapper.readTree(raw);
            List<DiarizedSegment> result = new ArrayList<>();
            for (JsonNode s : root.path("segments")) {
                result.add(new DiarizedSegment(
                        s.path("speaker").asText("A"),
                        s.path("text").asText(""),
                        s.path("start").asDouble(0),
                        s.path("end").asDouble(0)));
            }
            return result;
        }
    }

    // ── 녹음 파일 관리 ────────────────────────────────────────────────

    private Recording openRecording(String meetingId, byte[] first) {
        try {
            Path dir = Path.of(props.getStorage().getUploadDir(), "live");
            Files.createDirectories(dir);
            Recording r = new Recording();
            r.pcm = !isContainer(first);
            r.path = dir.resolve(meetingId + (r.pcm ? ".pcm" : ".webm"));
            r.out = new BufferedOutputStream(Files.newOutputStream(
                    r.path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING));
            log.info("[Live] 녹음 시작 — {} (pcm={})", r.path.getFileName(), r.pcm);
            return r;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 진행 중 녹음의 디코딩 가능한 스냅샷 (PCM → WAV 래핑, webm → 마지막 클러스터 경계까지) */
    private byte[] snapshotAudio(Recording r) throws IOException {
        byte[] raw = Files.readAllBytes(r.path);
        if (r.pcm) return pcmToWav(raw);

        int first = indexOfCluster(raw, 0);
        if (first < 0) return null;
        int last = lastIndexOfCluster(raw);
        if (last <= first) return null; // 완결된 클러스터가 아직 없음
        byte[] cut = new byte[last];
        System.arraycopy(raw, 0, cut, 0, last);
        return cut;
    }

    /** 녹음 확정 — 최종 파일 경로 반환 (raw PCM은 WAV로 변환). 유효 녹음 없으면 null */
    private Path finishRecording(String meetingId) {
        Recording r = recordings.remove(meetingId);
        if (r == null) return null;
        try {
            synchronized (r) { r.out.close(); }
            if (r.bytes < SAMPLE_RATE) { // 0.5초 미만이면 의미 없는 녹음
                Files.deleteIfExists(r.path);
                return null;
            }
            if (!r.pcm) return r.path;

            Path wav = r.path.resolveSibling(meetingId + ".wav");
            Files.write(wav, pcmToWav(Files.readAllBytes(r.path)));
            Files.deleteIfExists(r.path);
            log.info("[Live] 녹음 확정 — {} ({}KB)", wav.getFileName(), r.bytes / 1024);
            return wav;
        } catch (IOException e) {
            log.warn("[Live] 녹음 확정 실패 — meetingId={}: {}", meetingId, e.getMessage());
            return null;
        }
    }

    private static boolean isContainer(byte[] d) {
        if (d == null || d.length < 4) return false;
        boolean webm = (d[0] & 0xFF) == 0x1A && (d[1] & 0xFF) == 0x45
                && (d[2] & 0xFF) == 0xDF && (d[3] & 0xFF) == 0xA3;
        boolean ogg = d[0] == 'O' && d[1] == 'g' && d[2] == 'g' && d[3] == 'S';
        return webm || ogg;
    }

    private static int indexOfCluster(byte[] d, int from) {
        for (int i = from; i + 3 < d.length; i++) {
            if ((d[i] & 0xFF) == 0x1F && (d[i + 1] & 0xFF) == 0x43
                    && (d[i + 2] & 0xFF) == 0xB6 && (d[i + 3] & 0xFF) == 0x75) return i;
        }
        return -1;
    }

    private static int lastIndexOfCluster(byte[] d) {
        for (int i = d.length - 4; i >= 0; i--) {
            if ((d[i] & 0xFF) == 0x1F && (d[i + 1] & 0xFF) == 0x43
                    && (d[i + 2] & 0xFF) == 0xB6 && (d[i + 3] & 0xFF) == 0x75) return i;
        }
        return -1;
    }

    /** raw PCM(16kHz/16bit/mono) 바이트에 WAV 헤더를 붙여 반환 */
    private static byte[] pcmToWav(byte[] pcm) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(pcm.length + 44);
        DataOutputStream dos = new DataOutputStream(out);
        int byteRate = SAMPLE_RATE * 2;
        dos.writeBytes("RIFF"); writeLE32(dos, 36 + pcm.length);
        dos.writeBytes("WAVE");
        dos.writeBytes("fmt "); writeLE32(dos, 16);
        writeLE16(dos, (short) 1);
        writeLE16(dos, (short) 1);
        writeLE32(dos, SAMPLE_RATE);
        writeLE32(dos, byteRate);
        writeLE16(dos, (short) 2);
        writeLE16(dos, (short) 16);
        dos.writeBytes("data"); writeLE32(dos, pcm.length);
        dos.write(pcm);
        return out.toByteArray();
    }

    private static void writeLE32(DataOutputStream d, int v) throws IOException {
        d.write(v & 0xFF); d.write((v >> 8) & 0xFF);
        d.write((v >> 16) & 0xFF); d.write((v >> 24) & 0xFF);
    }

    private static void writeLE16(DataOutputStream d, short v) throws IOException {
        d.write(v & 0xFF); d.write((v >> 8) & 0xFF);
    }
}
