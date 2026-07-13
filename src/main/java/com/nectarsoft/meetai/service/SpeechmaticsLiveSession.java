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
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * Speechmatics 실시간 화자 분리 스트리밍 세션 (한 마이크, 다중 화자)
 * - 오디오 청크(webm/ogg 컨테이너는 ffmpeg로 16kHz PCM 디코딩, raw PCM은 그대로) → WS로 즉시 스트리밍
 * - 서버가 화자 라벨(S1/S2…)이 붙은 AddTranscript(확정 자막)를 실시간 push → onSegment 콜백
 * - OpenAI 롤링 배치와 달리 전체 오디오 재전송이 없어 지연이 시간에 비례해 늘지 않음
 *
 * 프로토콜: StartRecognition → (binary audio) → AddTranscript* → EndOfStream → EndOfTranscript
 */
@Slf4j
public class SpeechmaticsLiveSession {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final int SAMPLE_RATE = 16000;

    /** 화자 라벨이 붙은 확정 자막 한 구간 */
    public record Segment(String speakerLabel, String text, double startSec, double endSec) {}

    private final String apiKey;
    private final String url;
    private final String language;
    private final double maxDelaySec;
    private final Consumer<Segment> onSegment;

    private volatile WebSocket ws;
    private final Object sendLock = new Object();
    private volatile boolean started = false;   // RecognitionStarted 수신 여부
    private volatile boolean closed = false;
    private final AtomicInteger seqNo = new AtomicInteger(0); // 전송한 오디오 청크 수 (EndOfStream last_seq_no)
    private final CountDownLatch endLatch = new CountDownLatch(1);

    // RecognitionStarted 이전 오디오 버퍼링
    private final List<byte[]> preBuffer = new ArrayList<>();

    // 확정 세그먼트 누적 (종료 시 회의록 저장용)
    private final List<Segment> finals = new CopyOnWriteArrayList<>();

    // 상주 ffmpeg (컨테이너 입력일 때만)
    private Process ffmpeg;
    private OutputStream ffmpegIn;
    private boolean containerInput;
    private boolean formatDetected = false;

    public SpeechmaticsLiveSession(String apiKey, String url, String language,
                                   double maxDelaySec, Consumer<Segment> onSegment) {
        this.apiKey = apiKey;
        this.url = url;
        this.language = language;
        this.maxDelaySec = maxDelaySec;
        this.onSegment = onSegment;

        this.ws = connectWs();
        sendStartRecognition();
        log.info("[Speechmatics] 세션 연결 — url={}, lang={}", url, language);
    }

    private WebSocket connectWs() {
        return HttpClient.newHttpClient().newWebSocketBuilder()
                .header("Authorization", "Bearer " + apiKey)
                .buildAsync(URI.create(url), new Listener())
                .join();
    }

    private void sendStartRecognition() {
        sendJson(Map.of(
                "message", "StartRecognition",
                "audio_format", Map.of(
                        "type", "raw",
                        "encoding", "pcm_s16le",
                        "sample_rate", SAMPLE_RATE),
                "transcription_config", new LinkedHashMap<>(Map.of(
                        "language", language,
                        "diarization", "speaker",
                        "max_delay", maxDelaySec,
                        "enable_partials", false))));
    }

    // ── 오디오 입력 ─────────────────────────────────────────────────
    public synchronized void sendAudio(byte[] data) {
        if (closed || data == null || data.length == 0) return;
        try {
            if (!formatDetected) {
                containerInput = isContainer(data);
                formatDetected = true;
                if (containerInput) startFfmpeg();
                log.info("[Speechmatics] 입력 포맷 — container={}", containerInput);
            }
            if (containerInput) {
                ffmpegIn.write(data);
                ffmpegIn.flush();
            } else {
                sendPcm(data); // raw 16kHz PCM 그대로 전송
            }
        } catch (IOException e) {
            log.error("[Speechmatics] 오디오 입력 실패: {}", e.getMessage());
        }
    }

    private static boolean isContainer(byte[] d) {
        if (d.length < 4) return false;
        boolean webm = (d[0] & 0xFF) == 0x1A && (d[1] & 0xFF) == 0x45
                && (d[2] & 0xFF) == 0xDF && (d[3] & 0xFF) == 0xA3;
        boolean ogg = d[0] == 'O' && d[1] == 'g' && d[2] == 'g' && d[3] == 'S';
        return webm || ogg;
    }

    private void startFfmpeg() throws IOException {
        List<String> cmd = List.of("ffmpeg", "-hide_banner", "-loglevel", "error",
                "-fflags", "nobuffer", "-analyzeduration", "0", "-probesize", "8192",
                "-i", "pipe:0",
                "-f", "s16le", "-ac", "1", "-ar", String.valueOf(SAMPLE_RATE),
                "-flush_packets", "1", "pipe:1");
        ffmpeg = new ProcessBuilder(cmd).start();
        ffmpegIn = ffmpeg.getOutputStream();

        Thread pump = new Thread(this::pumpPcm, "speechmatics-pcm-pump");
        pump.setDaemon(true);
        pump.start();
        Thread errLog = new Thread(() -> {
            try (InputStream err = ffmpeg.getErrorStream()) {
                String msg = new String(err.readAllBytes());
                if (!msg.isBlank() && !closed) log.warn("[Speechmatics] ffmpeg: {}", msg.strip());
            } catch (IOException ignored) {}
        }, "speechmatics-ffmpeg-err");
        errLog.setDaemon(true);
        errLog.start();
    }

    /** ffmpeg stdout(16kHz PCM)을 읽어 WS로 전송 — 100ms 배치 */
    private void pumpPcm() {
        final int BATCH_BYTES = 3_200; // 100ms @ 16kHz 16-bit mono
        try (InputStream out = ffmpeg.getInputStream()) {
            byte[] buf = new byte[3_200];
            byte[] acc = new byte[BATCH_BYTES];
            int accLen = 0, n;
            while ((n = out.read(buf)) != -1) {
                if (closed) break;
                int off = 0;
                while (off < n) {
                    int take = Math.min(BATCH_BYTES - accLen, n - off);
                    System.arraycopy(buf, off, acc, accLen, take);
                    accLen += take; off += take;
                    if (accLen == BATCH_BYTES) {
                        sendPcm(acc.clone());
                        accLen = 0;
                    }
                }
            }
            if (accLen > 0 && !closed) {
                byte[] tail = new byte[accLen];
                System.arraycopy(acc, 0, tail, 0, accLen);
                sendPcm(tail);
            }
        } catch (Exception e) {
            if (!closed) log.error("[Speechmatics] PCM 펌프 종료: {}", e.getMessage());
        }
    }

    /** PCM 바이너리 전송 — RecognitionStarted 이전이면 버퍼링 */
    private void sendPcm(byte[] pcm) {
        if (closed) return;
        synchronized (sendLock) {
            if (!started) {
                preBuffer.add(pcm);
                return;
            }
        }
        sendBinary(pcm);
    }

    private void sendBinary(byte[] pcm) {
        try {
            synchronized (sendLock) {
                ws.sendBinary(ByteBuffer.wrap(pcm), true).join();
            }
            seqNo.incrementAndGet();
        } catch (Exception e) {
            if (!closed) log.warn("[Speechmatics] 오디오 전송 실패: {}", e.getMessage());
        }
    }

    private void sendJson(Map<String, Object> msg) {
        if (closed) return;
        try {
            String json = MAPPER.writeValueAsString(msg);
            synchronized (sendLock) {
                ws.sendText(json, true).join();
            }
        } catch (Exception e) {
            if (!closed) log.warn("[Speechmatics] 전송 실패: {}", e.getMessage());
        }
    }

    // ── WS 수신 ─────────────────────────────────────────────────────
    private class Listener implements WebSocket.Listener {
        private final StringBuilder buf = new StringBuilder();

        @Override
        public void onOpen(WebSocket w) { w.request(1); }

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
            if (!closed) log.error("[Speechmatics] WS 오류: {}", error.getMessage());
        }

        @Override
        public CompletionStage<?> onClose(WebSocket w, int statusCode, String reason) {
            if (!closed) log.warn("[Speechmatics] WS 종료 — code={}, reason={}", statusCode, reason);
            endLatch.countDown();
            return null;
        }
    }

    private void handleEvent(String raw) {
        try {
            JsonNode node = MAPPER.readTree(raw);
            switch (node.path("message").asText("")) {
                case "RecognitionStarted" -> flushPreBuffer();
                case "AddTranscript" -> emitSegments(node);
                case "EndOfTranscript" -> endLatch.countDown();
                case "Warning" -> log.warn("[Speechmatics] 경고: {}", node.path("reason").asText(raw));
                case "Error" -> {
                    log.error("[Speechmatics] 서버 오류: {} — {}",
                            node.path("type").asText(""), node.path("reason").asText(raw));
                    endLatch.countDown();
                }
                default -> { /* AudioAdded, Info 등 무시 */ }
            }
        } catch (Exception e) {
            log.warn("[Speechmatics] 이벤트 파싱 오류: {}", e.getMessage());
        }
    }

    /** RecognitionStarted 수신 후 버퍼링된 오디오를 순서대로 전송 */
    private void flushPreBuffer() {
        List<byte[]> pending;
        synchronized (sendLock) {
            started = true;
            pending = new ArrayList<>(preBuffer);
            preBuffer.clear();
        }
        for (byte[] pcm : pending) sendBinary(pcm);
        log.info("[Speechmatics] 인식 시작 — 버퍼 {}청크 전송", pending.size());
    }

    /** AddTranscript results[]를 화자 단위로 그룹핑해 세그먼트 생성 */
    private void emitSegments(JsonNode node) {
        JsonNode results = node.path("results");
        if (!results.isArray() || results.isEmpty()) return;

        String curSpeaker = null;
        StringBuilder text = new StringBuilder();
        double segStart = -1, segEnd = 0;

        for (JsonNode r : results) {
            JsonNode alt = r.path("alternatives").path(0);
            String content = alt.path("content").asText("");
            if (content.isEmpty()) continue;
            String speaker = alt.path("speaker").asText("UU");
            boolean isPunct = "punctuation".equals(r.path("type").asText(""));
            double start = r.path("start_time").asDouble(segEnd);
            double end = r.path("end_time").asDouble(start);

            // 구두점은 현재 화자에 그대로 붙임 (화자 전환으로 취급하지 않음)
            if (!isPunct && curSpeaker != null && !speaker.equals(curSpeaker)) {
                flushSegment(curSpeaker, text, segStart, segEnd);
                text.setLength(0);
                segStart = -1;
            }
            if (!isPunct) curSpeaker = speaker;
            if (segStart < 0) segStart = start;
            segEnd = end;
            if (isPunct) text.append(content);
            else text.append(text.length() > 0 ? " " : "").append(content);
        }
        flushSegment(curSpeaker, text, segStart, segEnd);
    }

    private void flushSegment(String speaker, StringBuilder text, double start, double end) {
        String t = text.toString().strip();
        if (t.isEmpty() || speaker == null) return;
        Segment seg = new Segment(mapSpeaker(speaker), t, Math.max(0, start), Math.max(0, end));
        finals.add(seg);
        try {
            onSegment.accept(seg);
        } catch (Exception e) {
            log.debug("[Speechmatics] onSegment 콜백 오류: {}", e.getMessage());
        }
        log.info("[Speechmatics] 자막 — [{}] \"{}\"", seg.speakerLabel(), t);
    }

    /** Speechmatics 화자 라벨(S1/S2, UU=미상)을 프론트 SPEAKER_* 규격으로 변환 */
    private static String mapSpeaker(String s) {
        if (s == null || s.isBlank() || "UU".equals(s)) return "SPEAKER_?";
        return s.replaceFirst("^S", "SPEAKER_");
    }

    /** 종료 시점까지 확정된 전체 세그먼트 (회의록 저장용) */
    public List<Segment> getSegments() {
        return new ArrayList<>(finals);
    }

    /** EndOfStream 전송 → EndOfTranscript 대기 → 정리. 남은 확정 자막까지 받고 닫는다. */
    public void close() {
        if (closed) return;
        try {
            if (ffmpegIn != null) ffmpegIn.close(); // ffmpeg가 남은 PCM을 flush하도록
        } catch (IOException ignored) {}
        // ffmpeg stdout이 EOF 날 때까지 잠깐 대기 (pump가 마지막 배치 전송)
        if (ffmpeg != null) {
            try { ffmpeg.waitFor(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}
            ffmpeg.destroy();
        }
        sendJson(Map.of("message", "EndOfStream", "last_seq_no", seqNo.get()));
        try {
            endLatch.await(5, TimeUnit.SECONDS); // 마지막 AddTranscript / EndOfTranscript 수신
        } catch (InterruptedException ignored) {}
        closed = true;
        try { ws.sendClose(WebSocket.NORMAL_CLOSURE, "done"); } catch (Exception ignored) {}
        log.info("[Speechmatics] 세션 종료 — 확정 세그먼트 {}개", finals.size());
    }
}
