package com.nectarsoft.meetai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.function.Consumer;

/**
 * 참여자 1인의 AssemblyAI Real-time Streaming WebSocket 세션
 * - SessionBegins 수신 전 도착한 PCM은 큐에 보관 후 즉시 플러시
 * - FinalTranscript: DB 저장 + 브로드캐스트 (onFinal 콜백)
 * - PartialTranscript: 실시간 UI 표시 (onPartial 콜백)
 */
@Slf4j
public class AssemblyAiStreamingSession {

    public record Transcript(String text, double startSec, double endSec) {}

    private static final String WS_URL = "wss://streaming.assemblyai.com/v3/ws";
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final WebSocket webSocket;
    private final Consumer<Transcript> onFinal;
    private final Consumer<String> onPartial;
    private final Queue<byte[]> earlyBuffer = new ConcurrentLinkedQueue<>();
    private volatile boolean sessionReady = false;
    private volatile boolean closed = false;
    // AssemblyAI audio_start/audio_end는 이 세션 열린 시점 기준 ms이므로
    // 회의 시작 시각 기준 누적 시간으로 보정하기 위한 오프셋
    private final long sessionOffsetMs;

    public AssemblyAiStreamingSession(String apiKey, int sampleRate, long sessionOffsetMs,
                                      Consumer<Transcript> onFinal,
                                      Consumer<String> onPartial) throws Exception {
        this.onFinal = onFinal;
        this.onPartial = onPartial;
        this.sessionOffsetMs = sessionOffsetMs;

        URI uri = URI.create(WS_URL + "?sample_rate=" + sampleRate + "&language_code=multi");
        this.webSocket = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .header("Authorization", apiKey)
                .buildAsync(uri, new Listener())
                .join();
    }

    public void sendAudio(byte[] pcm) {
        if (closed) return;
        if (!sessionReady) {
            earlyBuffer.offer(pcm);
            return;
        }
        flushEarlyBuffer();
        webSocket.sendBinary(ByteBuffer.wrap(pcm), true);
    }

    public void close() {
        if (closed) return;
        closed = true;
        try {
            webSocket.sendText("{\"terminate_session\": true}", true);
            webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "done");
        } catch (Exception ignored) {}
    }

    private void flushEarlyBuffer() {
        byte[] chunk;
        while ((chunk = earlyBuffer.poll()) != null) {
            webSocket.sendBinary(ByteBuffer.wrap(chunk), true);
        }
    }

    private class Listener implements WebSocket.Listener {
        private final StringBuilder buf = new StringBuilder();

        @Override
        public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
            buf.append(data);
            if (last) {
                handleMessage(buf.toString());
                buf.setLength(0);
            }
            ws.request(1);
            return null;
        }

        private void handleMessage(String raw) {
            try {
                JsonNode node = MAPPER.readTree(raw);
                // v3 API는 "type" 필드 사용, v2 호환을 위해 fallback
                String type = node.has("message_type")
                        ? node.path("message_type").asText("")
                        : node.path("type").asText("");
                switch (type) {
                    case "SessionBegins" -> {
                        sessionReady = true;
                        flushEarlyBuffer();
                        log.info("[AssemblyAI Stream] 세션 시작 — sessionId={}", node.path("session_id").asText());
                    }
                    case "PartialTranscript" -> {
                        String text = node.path("text").asText("").trim();
                        if (!text.isEmpty()) onPartial.accept(text);
                    }
                    case "FinalTranscript" -> {
                        String text = node.path("text").asText("").trim();
                        if (!text.isEmpty()) {
                            double startSec = (node.path("audio_start").asLong(0) + sessionOffsetMs) / 1000.0;
                            double endSec   = (node.path("audio_end").asLong(0)   + sessionOffsetMs) / 1000.0;
                            onFinal.accept(new Transcript(text, startSec, endSec));
                        }
                    }
                    case "SessionTerminated", "session_terminated" ->
                            log.info("[AssemblyAI Stream] 세션 종료");
                    case "Error" ->
                            log.error("[AssemblyAI Stream] 서버 오류 — code={}, msg={}",
                                    node.path("error_code").asText(), node.path("error").asText());
                    default ->
                            log.warn("[AssemblyAI Stream] 알 수 없는 메시지 타입: {} / raw={}", type, raw);
                }
            } catch (Exception e) {
                log.warn("[AssemblyAI Stream] 메시지 파싱 오류: {}", e.getMessage());
            }
        }

        @Override
        public void onError(WebSocket ws, Throwable error) {
            log.error("[AssemblyAI Stream] 오류: {}", error.getMessage());
        }
    }
}
