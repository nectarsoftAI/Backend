package com.nectarsoft.meetai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
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

    // 발화 타임스탬프 추적 (audio_start_ms/audio_end_ms는 세션 오디오 타임라인 기준)
    private final Queue<long[]> speechTimes = new ConcurrentLinkedQueue<>();
    private volatile long currentSpeechStartMs = 0;
    private final Map<String, StringBuilder> partials = new ConcurrentHashMap<>();

    public RealtimeSttSession(String apiKey, String model, String language,
                              long sessionOffsetMs,
                              Consumer<WhisperStreamingSession.Transcript> onFinal,
                              Consumer<String> onPartial) {
        this.apiKey = apiKey;
        this.model = model;
        this.language = language;
        this.meetingStartMs = System.currentTimeMillis() - sessionOffsetMs;
        this.sessionStartMs = System.currentTimeMillis();
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

    /** ffmpeg stdout(24kHz PCM)을 읽어 Realtime WS로 즉시 전송 */
    private void pumpPcm() {
        try (InputStream out = ffmpeg.getInputStream()) {
            byte[] buf = new byte[4800]; // 100ms @ 24kHz 16-bit mono
            int n;
            while ((n = out.read(buf)) != -1) {
                if (closed) break;
                byte[] chunk = (n == buf.length) ? buf : Arrays.copyOf(buf, n);
                sendJson(Map.of(
                        "type", "input_audio_buffer.append",
                        "audio", Base64.getEncoder().encodeToString(chunk)));
            }
        } catch (Exception e) {
            if (!closed) log.error("[Realtime] PCM 펌프 종료: {}", e.getMessage());
        }
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

    private void handleEvent(String raw) {
        try {
            JsonNode node = MAPPER.readTree(raw);
            String type = node.path("type").asText("");
            switch (type) {
                case "input_audio_buffer.speech_started" ->
                        currentSpeechStartMs = node.path("audio_start_ms").asLong(0);
                case "input_audio_buffer.speech_stopped" ->
                        speechTimes.add(new long[]{currentSpeechStartMs, node.path("audio_end_ms").asLong(0)});
                case "conversation.item.input_audio_transcription.delta" -> {
                    String itemId = node.path("item_id").asText("");
                    String acc = partials.computeIfAbsent(itemId, k -> new StringBuilder())
                            .append(node.path("delta").asText("")).toString();
                    if (!acc.isBlank()) onPartial.accept(acc);
                }
                case "conversation.item.input_audio_transcription.completed" -> {
                    partials.remove(node.path("item_id").asText(""));
                    String text = node.path("transcript").asText("").trim();
                    if (text.isEmpty() || HALLUCINATION_PATTERN.matcher(text).find()) return;
                    long[] t = speechTimes.poll();
                    double startSec, endSec;
                    if (t != null) {
                        startSec = Math.max(0, (sessionStartMs - meetingStartMs + t[0]) / 1000.0);
                        endSec   = Math.max(0, (sessionStartMs - meetingStartMs + t[1]) / 1000.0);
                    } else {
                        endSec   = Math.max(0, (System.currentTimeMillis() - meetingStartMs) / 1000.0);
                        startSec = Math.max(0, endSec - 3.0);
                    }
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
