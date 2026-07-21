package com.nectarsoft.meetai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * OpenAI Realtime API 기반 실시간 음성 인식 세션 (말하는 중 자막)
 * - 오디오 청크를 상주 ffmpeg로 스트림 디코딩(24kHz PCM) → Realtime WS로 즉시 전송
 * - 서버 VAD(turn detection)가 발화 감지 → delta(partial 자막) + completed(확정 자막) 수신
 * - OpenAI WS가 끊기면 ffmpeg는 유지한 채 WS만 재접속 (스로틀 5초)
 */
@Slf4j
public class RealtimeSttSession implements SttStreamSession {

    // GA 프로토콜 (실측 검증): intent=transcription + 베타 헤더 없이 접속, 설정은 session.update로.
    // 주의: OpenAI-Beta 헤더를 붙이면 beta_api_shape_disabled로 거부됨
    private static final String REALTIME_URL = "wss://api.openai.com/v1/realtime?intent=transcription";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    // 유튜브 자막용 환각 멘트 블랙리스트 (확정 자막에만 적용)
    private static final Pattern HALLUCINATION_PATTERN = Pattern.compile(
            "시청해\\s*주셔서 감사합니다|구독과 좋아요|MBC 뉴스|KBS 뉴스|다음에 또 만나요|다음 영상에서|Thank you for watching");

    private final String apiKey;
    private final String model;
    private final String language;
    private final long meetingStartMs;
    private final long sessionStartMs;
    private final Consumer<WhisperStreamingSession.Transcript> onFinal;
    private final Consumer<String> onPartial;

    private volatile WebSocket ws;
    private final Object sendLock = new Object();
    private volatile long lastReconnectMs = 0;
    private volatile boolean closed = false;

    // 상주 ffmpeg 스트림 디코더 (첫 청크 포맷 감지 후 lazy 시작)
    private Process ffmpeg;
    private OutputStream ffmpegIn;

    // 발화 타임스탬프: item_id로 매칭 — 전사 완료(completed)는 발화 순서와 다르게 도착할 수 있음
    private final Map<String, long[]> speechTimesByItem = new ConcurrentHashMap<>();
    private final Map<String, StringBuilder> partials = new ConcurrentHashMap<>();

    // ── 지연 계측 ────────────────────────────────────────────────────
    // 구간: t1 청크 수신 → (ffmpeg 디코딩) → t2 OpenAI 전송 → t3 이벤트 수신.
    // t1→t2는 ffmpeg가 별도 프로세스·별도 스레드라 1:1 대응이 아니므로
    // "마지막 입력 이후 경과"로 근사한다.
    // 총 지연은 OpenAI가 주는 audio_end_ms(오디오 시계)와 우리가 보낸 오디오 길이의 차이라
    // 벽시계 오차와 무관하다.
    private final boolean latencyLog;
    private volatile long lastAudioInNanos = 0;                    // sendAudio 진입 시각
    private volatile long ffmpegLagMs = 0;                         // t1→t2 근사
    private final java.util.concurrent.atomic.AtomicLong sentAudioBytes =
            new java.util.concurrent.atomic.AtomicLong();          // OpenAI로 보낸 24kHz PCM 누적
    private final Map<String, Long> stopWallByItem = new ConcurrentHashMap<>();   // speech_stopped 도착 시각
    private final Map<String, Long> firstDeltaByItem = new ConcurrentHashMap<>(); // 첫 partial 도착 시각

    public RealtimeSttSession(String apiKey, String model, String language,
                              long sessionOffsetMs, boolean latencyLog,
                              Consumer<WhisperStreamingSession.Transcript> onFinal,
                              Consumer<String> onPartial) {
        this.apiKey = apiKey;
        this.model = model;
        this.language = language;
        this.meetingStartMs = System.currentTimeMillis() - sessionOffsetMs;
        this.sessionStartMs = System.currentTimeMillis();
        this.latencyLog = latencyLog;
        this.onFinal = onFinal;
        this.onPartial = onPartial;

        this.ws = connectWs();
        sendSessionConfig();
        log.info("[Realtime] 세션 연결 — model={}, offsetMs={}", model, sessionOffsetMs);
    }

    private WebSocket connectWs() {
        return HttpClient.newHttpClient().newWebSocketBuilder()
                .header("Authorization", "Bearer " + apiKey)
                .buildAsync(URI.create(REALTIME_URL), new Listener())
                .join();
    }

    /** GA 프로토콜: session.update + session.type=transcription, 전사 모델은 audio.input.transcription */
    private void sendSessionConfig() {
        sendJson(Map.of(
                "type", "session.update",
                "session", Map.of(
                        "type", "transcription",
                        "audio", Map.of(
                                "input", Map.of(
                                        "format", Map.of("type", "audio/pcm", "rate", 24000),
                                        "transcription", Map.of(
                                                "model", model,
                                                "language", language),
                                        "turn_detection", Map.of(
                                                "type", "server_vad",
                                                "threshold", 0.5,
                                                "prefix_padding_ms", 300,
                                                "silence_duration_ms", 500))))));
    }

    // ── 오디오 입력: WS 청크 → ffmpeg stdin ─────────────────────────
    @Override
    public synchronized void sendAudio(byte[] data) {
        if (closed) return;
        lastAudioInNanos = System.nanoTime(); // t1 — ffmpeg 통과 지연 계측 기준점
        try {
            if (ffmpeg == null) startFfmpeg(isContainer(data));
            ffmpegIn.write(data);
            ffmpegIn.flush();
        } catch (IOException e) {
            log.error("[Realtime] ffmpeg 입력 실패: {}", e.getMessage());
        }
    }

    private static boolean isContainer(byte[] d) {
        if (d.length < 4) return false;
        boolean webm = (d[0] & 0xFF) == 0x1A && (d[1] & 0xFF) == 0x45
                && (d[2] & 0xFF) == 0xDF && (d[3] & 0xFF) == 0xA3;
        boolean ogg = d[0] == 'O' && d[1] == 'g' && d[2] == 'g' && d[3] == 'S';
        return webm || ogg;
    }

    private void startFfmpeg(boolean container) throws IOException {
        // analyzeduration 기본값(5초)이 스트림 시작을 통째로 버퍼링하므로 반드시 0으로 —
        // 빠뜨리면 partial 자막이 수 초 뒤에 몰아서 도착함 (실측)
        List<String> cmd = new ArrayList<>(List.of("ffmpeg", "-hide_banner", "-loglevel", "error",
                "-fflags", "nobuffer", "-analyzeduration", "0"));
        if (container) {
            // 스트리밍 webm/ogg — 헤더만으로 즉시 디코딩 시작하도록 probe 최소화
            cmd.addAll(List.of("-probesize", "8192"));
        } else {
            // raw PCM 클라이언트 (16kHz mono s16le 가정)
            cmd.addAll(List.of("-probesize", "32", "-f", "s16le", "-ar", "16000", "-ac", "1"));
        }
        cmd.addAll(List.of("-i", "pipe:0", "-f", "s16le", "-ac", "1", "-ar", "24000", "-flush_packets", "1", "pipe:1"));

        ffmpeg = new ProcessBuilder(cmd).start();
        ffmpegIn = ffmpeg.getOutputStream();

        Thread pump = new Thread(this::pumpPcm, "realtime-pcm-pump");
        pump.setDaemon(true);
        pump.start();
        Thread errLog = new Thread(() -> {
            try (InputStream err = ffmpeg.getErrorStream()) {
                String msg = new String(err.readAllBytes());
                if (!msg.isBlank() && !closed) log.warn("[Realtime] ffmpeg: {}", msg.strip());
            } catch (IOException ignored) {}
        }, "realtime-ffmpeg-err");
        errLog.setDaemon(true);
        errLog.start();
        log.info("[Realtime] ffmpeg 스트림 디코더 시작 — container={}", container);
    }

    /** ffmpeg stdout(24kHz PCM)을 읽어 Realtime WS로 전송 — 250ms 배치로 왕복 지연 누적 방지 */
    private void pumpPcm() {
        final int BATCH_BYTES = 12_000; // 250ms @ 24kHz 16-bit mono
        try (InputStream out = ffmpeg.getInputStream()) {
            byte[] buf = new byte[4800];
            ByteArrayOutputStream acc = new ByteArrayOutputStream();
            int n;
            while ((n = out.read(buf)) != -1) {
                if (closed) break;
                acc.write(buf, 0, n);
                if (acc.size() >= BATCH_BYTES) {
                    appendToOpenAi(acc.toByteArray());
                    acc.reset();
                }
            }
            if (acc.size() > 0 && !closed) {
                appendToOpenAi(acc.toByteArray());
            }
        } catch (Exception e) {
            if (!closed) log.error("[Realtime] PCM 펌프 종료: {}", e.getMessage());
        }
    }

    /**
     * t2 — 디코딩된 24kHz PCM을 OpenAI 버퍼에 append.
     * 보낸 누적 바이트가 곧 오디오 시계(48바이트 = 1ms)이며,
     * OpenAI가 주는 audio_end_ms와 같은 원점을 공유하므로 총 지연 계산에 쓴다.
     */
    private void appendToOpenAi(byte[] pcm24k) {
        sentAudioBytes.addAndGet(pcm24k.length);
        long in = lastAudioInNanos;
        if (in > 0) ffmpegLagMs = (System.nanoTime() - in) / 1_000_000;
        sendJson(Map.of(
                "type", "input_audio_buffer.append",
                "audio", Base64.getEncoder().encodeToString(pcm24k)));
    }

    /** OpenAI 버퍼에 보낸 오디오 길이(ms) — 24kHz × 16bit mono = 48바이트/ms */
    private long sentAudioMs() {
        return sentAudioBytes.get() / 48;
    }

    // ── Realtime WS 송신 (단일화 + 끊김 시 재접속) ──────────────────
    private void sendJson(Map<String, Object> msg) {
        if (closed) return;
        try {
            String json = MAPPER.writeValueAsString(msg);
            synchronized (sendLock) {
                ws.sendText(json, true).join();
            }
        } catch (Exception e) {
            if (closed) return;
            log.warn("[Realtime] 전송 실패 — 재접속 시도: {}", e.getMessage());
            reconnect();
        }
    }

    private void reconnect() {
        synchronized (sendLock) {
            long now = System.currentTimeMillis();
            if (now - lastReconnectMs < 5000) return; // 스로틀
            lastReconnectMs = now;
            try {
                this.ws = connectWs();
            } catch (Exception e) {
                log.error("[Realtime] 재접속 실패: {}", e.getMessage());
                return;
            }
        }
        sendSessionConfig();
        log.info("[Realtime] WS 재접속 완료");
    }

    // ── Realtime WS 수신 이벤트 처리 ────────────────────────────────
    private class Listener implements WebSocket.Listener {
        private final StringBuilder buf = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket w, CharSequence data, boolean last) {
            buf.append(data);
            if (last) {
                handleEvent(buf.toString());
                buf.setLength(0);
            }
            w.request(1);
            return null;
        }

        @Override
        public void onError(WebSocket w, Throwable error) {
            if (!closed) log.error("[Realtime] WS 오류: {}", error.getMessage());
        }

        @Override
        public CompletionStage<?> onClose(WebSocket w, int statusCode, String reason) {
            if (!closed) log.warn("[Realtime] WS 종료 — code={}, reason={}", statusCode, reason);
            return null;
        }
    }

    /**
     * 확정 자막 지연 로그.
     * - 전사: OpenAI가 무음을 감지한 시점(speech_stopped) → 전사 완료. OpenAI 처리 시간
     * - 총지연: 발화 종료(audio_end_ms) → 지금까지 보낸 오디오 지점. 오디오 시계 기준이라
     *          벽시계 오차와 무관하다. 재접속으로 버퍼 원점이 바뀌면 음수/과대값이 나올 수
     *          있어 비정상 범위는 n/a로 남긴다
     */
    private void logFinalLatency(String itemId, long[] speech, String text) {
        Long stopNanos = stopWallByItem.remove(itemId);
        firstDeltaByItem.remove(itemId);
        if (!latencyLog) return;

        String transcribe = stopNanos != null
                ? (System.nanoTime() - stopNanos) / 1_000_000 + "ms" : "n/a";
        String total = "n/a";
        if (speech != null && speech[1] > 0) {
            long ms = sentAudioMs() - speech[1];
            if (ms >= 0 && ms < 60_000) total = ms + "ms";
        }
        log.info("[STT계측-온라인] final — 전사 {} (무음감지 후) | 총지연 {} | ffmpeg {}ms | \"{}\"",
                transcribe, total, ffmpegLagMs, text);
    }

    private void handleEvent(String raw) {
        try {
            JsonNode node = MAPPER.readTree(raw);
            String type = node.path("type").asText("");
            switch (type) {
                case "input_audio_buffer.speech_started" ->
                        speechTimesByItem.put(node.path("item_id").asText(""),
                                new long[]{node.path("audio_start_ms").asLong(0), -1});
                case "input_audio_buffer.speech_stopped" -> {
                    String itemId = node.path("item_id").asText("");
                    long[] t = speechTimesByItem.get(itemId);
                    if (t != null) t[1] = node.path("audio_end_ms").asLong(0);
                    stopWallByItem.put(itemId, System.nanoTime()); // 무음 감지 시각 — 전사 소요 기준점
                }
                case "conversation.item.input_audio_transcription.delta" -> {
                    String itemId = node.path("item_id").asText("");
                    String acc = partials.computeIfAbsent(itemId, k -> new StringBuilder())
                            .append(node.path("delta").asText("")).toString();
                    if (firstDeltaByItem.putIfAbsent(itemId, System.nanoTime()) == null && latencyLog) {
                        long[] t = speechTimesByItem.get(itemId);
                        // 첫 partial이 뜨기까지 = 발화 시작 후 화면에 글자가 처음 보이는 시점
                        String since = (t != null && t[0] > 0) ? (sentAudioMs() - t[0]) + "ms" : "n/a";
                        log.info("[STT계측-온라인] partial 첫 델타 — 발화시작 후 {} | ffmpeg {}ms", since, ffmpegLagMs);
                    }
                    if (!acc.isBlank()) onPartial.accept(acc);
                }
                case "conversation.item.input_audio_transcription.completed" -> {
                    String itemId = node.path("item_id").asText("");
                    partials.remove(itemId);
                    String text = node.path("transcript").asText("").trim();
                    if (text.isEmpty() || HALLUCINATION_PATTERN.matcher(text).find()) {
                        stopWallByItem.remove(itemId);   // 버려지는 발화의 계측 상태도 정리
                        firstDeltaByItem.remove(itemId);
                        return;
                    }
                    long[] t = speechTimesByItem.remove(itemId);
                    double startSec, endSec;
                    if (t != null && t[1] > 0) {
                        startSec = Math.max(0, (sessionStartMs - meetingStartMs + t[0]) / 1000.0);
                        endSec   = Math.max(0, (sessionStartMs - meetingStartMs + t[1]) / 1000.0);
                    } else {
                        endSec   = Math.max(0, (System.currentTimeMillis() - meetingStartMs) / 1000.0);
                        startSec = Math.max(0, endSec - 3.0);
                    }
                    logFinalLatency(itemId, t, text);
                    onFinal.accept(new WhisperStreamingSession.Transcript(text, startSec, endSec));
                    log.info("[Realtime] 자막 확정 — [{}-{}s] \"{}\"",
                            String.format("%.1f", startSec), String.format("%.1f", endSec), text);
                }
                case "error" ->
                        log.error("[Realtime] 서버 오류: {}", node.path("error").path("message").asText(raw));
                case "session.created", "session.updated",
                     "transcription_session.created", "transcription_session.updated",
                     "input_audio_buffer.committed", "conversation.item.created",
                     "conversation.item.added", "conversation.item.done" -> { /* 무시 */ }
                default -> log.debug("[Realtime] 이벤트: {}", type);
            }
        } catch (Exception e) {
            log.warn("[Realtime] 이벤트 파싱 오류: {}", e.getMessage());
        }
    }

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        try { if (ffmpegIn != null) ffmpegIn.close(); } catch (IOException ignored) {}
        if (ffmpeg != null) ffmpeg.destroy();
        try { ws.sendClose(WebSocket.NORMAL_CLOSURE, "done"); } catch (Exception ignored) {}
        log.info("[Realtime] 세션 종료");
    }
}
