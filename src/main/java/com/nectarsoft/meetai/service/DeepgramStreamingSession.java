package com.nectarsoft.meetai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Deepgram 실시간 STT + 화자 분리 WebSocket 세션 (라이브 녹음용)
 * - linear16 PCM(16kHz mono)을 그대로 스트리밍하고, is_final 결과의 word.speaker로
 *   화자 라벨(SPEAKER_A/B/…)을 실시간 부여한다 (diarize=true → v1 스트리밍 diarizer)
 * - partials=true면 interim_results를 켜고 말하는 중 자막을 isFinal=false 세그먼트로 전달
 *   (Speechmatics partial과 동일 규칙 — 확정 자막만 내부 누적/저장 대상)
 * - close()는 CloseStream을 보내 서버가 잔여 final을 플러시하게 하고,
 *   awaitClose()로 그 도착을 기다린다. getSegments()는 확정 세그먼트만 반환
 */
@Slf4j
public class DeepgramStreamingSession {

    public record Segment(String speakerLabel, String text, double startSec, double endSec,
                          double confidence, boolean isFinal) {}

    private static final String WS_URL = "wss://api.deepgram.com/v1/listen";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WebSocket webSocket;
    private final Consumer<Segment> onSegment;
    private final List<Segment> finals = Collections.synchronizedList(new ArrayList<>());
    private final CountDownLatch closedLatch = new CountDownLatch(1);
    private volatile boolean closed = false;

    public DeepgramStreamingSession(String apiKey, String model, String language, int sampleRate,
                                    int endpointingMs, boolean partials,
                                    Consumer<Segment> onSegment) {
        this.onSegment = onSegment;
        String url = WS_URL + "?model=" + model
                + "&language=" + language
                + "&encoding=linear16&sample_rate=" + sampleRate + "&channels=1"
                + "&diarize=true&smart_format=true"
                + "&endpointing=" + endpointingMs
                + (partials ? "&interim_results=true&vad_events=true" : "");
        this.webSocket = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .header("Authorization", "Token " + apiKey)
                .buildAsync(URI.create(url), new Listener())
                .join();
        log.info("[Deepgram] 세션 연결 — model={}, language={}, endpointing={}ms, partials={}",
                model, language, endpointingMs, partials);
    }

    public void sendAudio(byte[] pcm) {
        if (closed) return;
        try {
            webSocket.sendBinary(ByteBuffer.wrap(pcm), true);
        } catch (Exception e) {
            log.warn("[Deepgram] 오디오 전송 실패: {}", e.getMessage());
        }
    }

    /** CloseStream 전송 — 서버가 잔여 결과를 플러시한 뒤 연결을 닫는다 */
    public void close() {
        if (closed) return;
        closed = true;
        try {
            webSocket.sendText("{\"type\":\"CloseStream\"}", true);
        } catch (Exception ignored) {}
    }

    /** close() 후 잔여 final 결과가 도착할 시간을 준다 */
    public void awaitClose(long timeoutMs) {
        try {
            closedLatch.await(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** 확정(isFinal=true) 세그먼트만 — 회의록 저장용 */
    public List<Segment> getSegments() {
        synchronized (finals) {
            return new ArrayList<>(finals);
        }
    }

    private class Listener implements WebSocket.Listener {
        private final StringBuilder buf = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buf.append(data);
            if (last) {
                handle(buf.toString());
                buf.setLength(0);
            }
            ws.request(1);
            return null;
        }

        private void handle(String raw) {
            try {
                JsonNode node = MAPPER.readTree(raw);
                switch (node.path("type").asText("")) {
                    case "Results" -> {
                        JsonNode alt = node.path("channel").path("alternatives").path(0);
                        JsonNode words = alt.path("words");
                        if (!words.isArray() || words.isEmpty()) return;
                        boolean isFinal = node.path("is_final").asBoolean(false);
                        emitBySpeaker(words, alt.path("confidence").asDouble(1.0), isFinal);
                    }
                    case "SpeechStarted" -> log.debug("[Deepgram] 발화 시작 감지");
                    case "UtteranceEnd", "Metadata" -> { /* 무시 */ }
                    default -> { }
                }
            } catch (Exception e) {
                log.warn("[Deepgram] 메시지 파싱 오류: {}", e.getMessage());
            }
        }

        /** 연속된 같은 화자의 단어들을 하나의 세그먼트로 묶어 방출 */
        private void emitBySpeaker(JsonNode words, double confidence, boolean isFinal) {
            Integer speaker = null;
            StringBuilder text = new StringBuilder();
            double start = 0, end = 0;
            for (JsonNode w : words) {
                String token = w.path("punctuated_word").asText("");
                if (token.isEmpty()) token = w.path("word").asText("");
                if (token.isEmpty()) continue;

                int sp = w.path("speaker").asInt(0);
                if (speaker == null || sp != speaker) {
                    if (speaker != null) emit(speaker, text.toString(), start, end, confidence, isFinal);
                    speaker = sp;
                    text.setLength(0);
                    start = w.path("start").asDouble(0);
                }
                if (text.length() > 0) text.append(' ');
                text.append(token);
                end = w.path("end").asDouble(0);
            }
            if (speaker != null) emit(speaker, text.toString(), start, end, confidence, isFinal);
        }

        private void emit(int speaker, String text, double start, double end,
                          double confidence, boolean isFinal) {
            String t = text.strip();
            if (t.isEmpty()) return;
            Segment seg = new Segment(speakerLabel(speaker), t, start, end, confidence, isFinal);
            if (isFinal) finals.add(seg);
            try {
                onSegment.accept(seg);
            } catch (Exception e) {
                log.debug("[Deepgram] 세그먼트 콜백 오류: {}", e.getMessage());
            }
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            closed = true;
            closedLatch.countDown();
            log.info("[Deepgram] 세션 종료 — code={}, reason={}", statusCode, reason);
            return null;
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            closed = true;
            closedLatch.countDown();
            log.error("[Deepgram] 오류: {}", error.getMessage());
        }
    }

    private static String speakerLabel(int n) {
        return (n >= 0 && n < 26) ? "SPEAKER_" + (char) ('A' + n) : "SPEAKER_" + n;
    }
}
