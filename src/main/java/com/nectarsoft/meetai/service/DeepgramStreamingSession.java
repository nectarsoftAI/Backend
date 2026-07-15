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
 * - 문장 단위 확정: is_final 조각을 화자 턴(turn)으로 누적했다가 화자 전환 /
 *   speech_final(VAD 무음 감지) / 최대 길이에서만 한 덩어리로 방출한다.
 *   Deepgram은 침묵을 감지하면 speech_final=true를 주므로 별도 idle 타이머가 필요 없다
 *   (Speechmatics의 화자 턴 누적과 동일한 규칙)
 * - partials=true면 interim_results를 켜고 말하는 중 자막을 isFinal=false 세그먼트로 전달
 *   (확정 자막만 내부 누적/저장 대상)
 * - close()는 CloseStream을 보내 서버가 잔여 final을 플러시하게 하고,
 *   awaitClose()로 그 도착을 기다린다. getSegments()는 확정 세그먼트만 반환
 */
@Slf4j
public class DeepgramStreamingSession {

    public record Segment(String speakerLabel, String text, double startSec, double endSec,
                          double confidence, boolean isFinal) {}

    private static final String WS_URL = "wss://api.deepgram.com/v1/listen";
    private static final ObjectMapper MAPPER = new ObjectMapper();
    // 한 턴이 무한히 길어지지 않도록 상한 — 쉼 없이 말해도 이 길이마다 자막이 나간다
    private static final double MAX_TURN_SEC = 15.0;

    private final WebSocket webSocket;
    private final Consumer<Segment> onSegment;
    private final List<Segment> finals = Collections.synchronizedList(new ArrayList<>());
    private final CountDownLatch closedLatch = new CountDownLatch(1);
    private volatile boolean closed = false;

    // ── 화자 턴 누적 상태 (turnLock으로 보호) ─────────────────────────
    private final Object turnLock = new Object();
    private Integer turnSpeaker;
    private final StringBuilder turnText = new StringBuilder();
    private double turnStart = -1;
    private double turnEnd = 0;
    private double turnConfidence = 1.0;

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

    /** 확정(isFinal=true) 세그먼트만 — 회의록 저장용. 미확정 턴이 남아 있으면 먼저 플러시 */
    public List<Segment> getSegments() {
        synchronized (turnLock) {
            flushTurnLocked();
        }
        synchronized (finals) {
            return new ArrayList<>(finals);
        }
    }

    /** 누적 중인 화자 턴을 확정 세그먼트로 방출 (turnLock 보유 상태에서 호출) */
    private void flushTurnLocked() {
        String text = turnText.toString().strip();
        if (turnSpeaker != null && !text.isEmpty()) {
            Segment seg = new Segment(speakerLabel(turnSpeaker), text,
                    turnStart, turnEnd, turnConfidence, true);
            finals.add(seg);
            try {
                onSegment.accept(seg);
            } catch (Exception e) {
                log.debug("[Deepgram] 세그먼트 콜백 오류: {}", e.getMessage());
            }
        }
        turnSpeaker = null;
        turnText.setLength(0);
        turnStart = -1;
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
                        double conf = alt.path("confidence").asDouble(1.0);
                        if (node.path("is_final").asBoolean(false)) {
                            appendFinalWords(words, conf, node.path("speech_final").asBoolean(false));
                        } else {
                            emitInterimPreview(words, conf); // partials=true일 때만 수신
                        }
                    }
                    case "SpeechStarted" -> log.debug("[Deepgram] 발화 시작 감지");
                    case "UtteranceEnd", "Metadata" -> { /* 무시 */ }
                    default -> { }
                }
            } catch (Exception e) {
                log.warn("[Deepgram] 메시지 파싱 오류: {}", e.getMessage());
            }
        }

        /**
         * 확정(is_final) 단어들을 화자 턴에 누적.
         * 플러시 조건: 화자 전환 / speech_final(무음 감지) / 턴 최대 길이 초과.
         * is_final 조각은 문장 중간에서도 끊겨 오므로 바로 방출하면 자막이 잘게 쪼개진다.
         */
        private void appendFinalWords(JsonNode words, double confidence, boolean speechFinal) {
            synchronized (turnLock) {
                for (JsonNode w : words) {
                    String token = w.path("punctuated_word").asText("");
                    if (token.isEmpty()) token = w.path("word").asText("");
                    if (token.isEmpty()) continue;

                    int sp = w.path("speaker").asInt(0);
                    if (turnSpeaker != null && sp != turnSpeaker) {
                        flushTurnLocked(); // 진짜 화자 전환 — 이전 화자 턴 확정
                    }
                    if (turnSpeaker == null) {
                        turnSpeaker = sp;
                        turnStart = w.path("start").asDouble(0);
                    }
                    if (turnText.length() > 0) turnText.append(' ');
                    turnText.append(token);
                    turnEnd = w.path("end").asDouble(0);
                    turnConfidence = confidence;

                    // 문장 끝 부호(smart_format)가 오면 그 지점에서 확정 — 연속 발화에서도 문장 단위 분리.
                    // (침묵/speech_final만으로는 문장 사이 무음이 없으면 여러 문장이 한 덩어리로 뭉침)
                    char last = token.charAt(token.length() - 1);
                    if (last == '.' || last == '?' || last == '!' || last == '。' || last == '…') {
                        flushTurnLocked();
                        continue;
                    }

                    if (turnEnd - turnStart >= MAX_TURN_SEC) {
                        flushTurnLocked(); // 쉼 없는 장광설도 주기적으로 자막 방출
                    }
                }
                if (speechFinal) {
                    flushTurnLocked(); // VAD가 무음(endpointing) 감지 — 문장 경계로 확정
                }
            }
        }

        /**
         * 말하는 중 미리보기 — partial 경계를 final(턴)과 일치시킨다.
         * partial = "현재 턴의 확정 텍스트(turnText) + 진행 중 interim". 시작점도 turnStart로 맞춰,
         * 문장부호/무음에서 final이 flush되면 같은 화자·같은 시작의 확정본이 partial을 그대로 대체한다.
         * (예전엔 partial=Deepgram interim 구간, final=누적 턴이라 경계가 달라 화면이 꼬였음)
         */
        private void emitInterimPreview(JsonNode words, double confidence) {
            Integer interimSpk = null;
            StringBuilder interim = new StringBuilder();
            double interimStart = -1, end = 0;
            for (JsonNode w : words) {
                String token = w.path("punctuated_word").asText("");
                if (token.isEmpty()) token = w.path("word").asText("");
                if (token.isEmpty()) continue;
                if (interimSpk == null) {
                    interimSpk = w.path("speaker").asInt(0);
                    interimStart = w.path("start").asDouble(0);
                }
                if (interim.length() > 0) interim.append(' ');
                interim.append(token);
                end = w.path("end").asDouble(0);
            }
            if (interim.length() == 0) return;

            synchronized (turnLock) {
                boolean sameSpeaker = turnSpeaker != null && turnSpeaker.equals(interimSpk);
                int spk = sameSpeaker ? turnSpeaker : (interimSpk != null ? interimSpk : 0);
                double start;
                String full;
                if (sameSpeaker && turnText.length() > 0) {
                    start = turnStart >= 0 ? turnStart : interimStart;   // 턴과 같은 시작점
                    full = turnText + " " + interim;                     // 확정분 + 진행 중
                } else {
                    start = interimStart;                                // 새 화자/새 턴 미리보기
                    full = interim.toString();
                }
                previewCallback(spk, full.strip(), start, end, confidence);
            }
        }

        private void previewCallback(int speaker, String text, double start, double end, double confidence) {
            String t = text.strip();
            if (t.isEmpty()) return;
            try {
                onSegment.accept(new Segment(speakerLabel(speaker), t, start, end, confidence, false));
            } catch (Exception e) {
                log.debug("[Deepgram] 세그먼트 콜백 오류: {}", e.getMessage());
            }
        }

        @Override
        public CompletionStage<?> onClose(WebSocket ws, int statusCode, String reason) {
            closed = true;
            synchronized (turnLock) {
                flushTurnLocked(); // 서버가 닫기 전 마지막 조각까지 확정
            }
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
