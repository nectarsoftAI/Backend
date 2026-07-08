package com.nectarsoft.meetai.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.io.*;
import java.util.Map;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * OpenAI Whisper 기반 음성 인식 세션
 * - PCM 오디오를 5초 단위로 누적 후 Whisper API에 일괄 전송
 * - 한국어(ko) 지원
 */
@Slf4j
public class WhisperStreamingSession {

    public record Transcript(String text, double startSec, double endSec) {}

    private static final int FLUSH_INTERVAL_SEC = 5;
    private static final int SAMPLE_RATE = 16000;
    // 최소 1초치 오디오 미만이면 전송 생략 (노이즈/무음 방지)
    private static final int MIN_BUFFER_BYTES = SAMPLE_RATE * 2;

    private final String apiKey;
    private final String model;
    private final String language;
    private final long meetingStartMs;
    private final Consumer<Transcript> onFinal;
    private final RestTemplate restTemplate;

    private static RestTemplate buildRestTemplate() {
        var factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(10_000);
        factory.setReadTimeout(30_000);
        return new RestTemplate(factory);
    }

    private final ByteArrayOutputStream audioBuffer = new ByteArrayOutputStream();
    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "whisper-flush");
                t.setDaemon(true);
                return t;
            });
    private volatile boolean closed = false;
    private long bufferStartMs;

    public WhisperStreamingSession(String apiKey, String model, String language,
                                   long sessionOffsetMs, Consumer<Transcript> onFinal) {
        this.apiKey = apiKey;
        this.model = model;
        this.language = language;
        this.meetingStartMs = System.currentTimeMillis() - sessionOffsetMs;
        this.onFinal = onFinal;
        this.restTemplate = buildRestTemplate();
        this.bufferStartMs = System.currentTimeMillis();
        scheduler.scheduleAtFixedRate(this::flush, FLUSH_INTERVAL_SEC, FLUSH_INTERVAL_SEC, TimeUnit.SECONDS);
        log.info("[Whisper] 세션 시작 — offsetMs={}", sessionOffsetMs);
    }

    public void sendAudio(byte[] pcm) {
        if (closed) return;
        synchronized (audioBuffer) {
            audioBuffer.write(pcm, 0, pcm.length);
        }
    }

    public void close() {
        if (closed) return;
        closed = true;
        scheduler.shutdown();
        flush();
    }

    private void flush() {
        byte[] pcm;
        long startMs;
        synchronized (audioBuffer) {
            if (audioBuffer.size() < MIN_BUFFER_BYTES) {
                audioBuffer.reset();
                bufferStartMs = System.currentTimeMillis();
                return;
            }
            pcm = audioBuffer.toByteArray();
            startMs = bufferStartMs;
            audioBuffer.reset();
            bufferStartMs = System.currentTimeMillis();
        }

        try {
            byte[] wav = toWav(pcm);
            String text = transcribe(wav);
            if (text != null && !text.isBlank()) {
                double startSec = Math.max(0, (startMs - meetingStartMs) / 1000.0);
                double endSec   = (System.currentTimeMillis() - meetingStartMs) / 1000.0;
                onFinal.accept(new Transcript(text.trim(), startSec, endSec));
                log.info("[Whisper] 변환 완료 — [{}-{}s] \"{}\"",
                        String.format("%.1f", startSec), String.format("%.1f", endSec), text.trim());
            }
        } catch (Exception e) {
            log.error("[Whisper] 변환 실패: {}", e.getMessage());
        }
    }

    @SuppressWarnings("unchecked")
    private String transcribe(byte[] wav) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(wav) {
            @Override public String getFilename() { return "audio.wav"; }
        });
        body.add("model", model);
        body.add("language", language);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(apiKey);

        ResponseEntity<Map> response = restTemplate.postForEntity(
                "https://api.openai.com/v1/audio/transcriptions",
                new HttpEntity<>(body, headers),
                Map.class);

        if (response.getStatusCode() == HttpStatus.OK && response.getBody() != null) {
            return (String) response.getBody().get("text");
        }
        return null;
    }

    private byte[] toWav(byte[] pcm) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        DataOutputStream dos = new DataOutputStream(out);
        int byteRate  = SAMPLE_RATE * 2;  // mono 16-bit
        dos.writeBytes("RIFF"); writeLE32(dos, 36 + pcm.length);
        dos.writeBytes("WAVE");
        dos.writeBytes("fmt "); writeLE32(dos, 16);
        writeLE16(dos, (short) 1);           // PCM
        writeLE16(dos, (short) 1);           // mono
        writeLE32(dos, SAMPLE_RATE);
        writeLE32(dos, byteRate);
        writeLE16(dos, (short) 2);           // block align
        writeLE16(dos, (short) 16);          // bits per sample
        dos.writeBytes("data"); writeLE32(dos, pcm.length);
        dos.write(pcm);
        return out.toByteArray();
    }

    private void writeLE32(DataOutputStream d, int v) throws IOException {
        d.write(v & 0xFF); d.write((v >> 8) & 0xFF);
        d.write((v >> 16) & 0xFF); d.write((v >> 24) & 0xFF);
    }

    private void writeLE16(DataOutputStream d, short v) throws IOException {
        d.write(v & 0xFF); d.write((v >> 8) & 0xFF);
    }
}
