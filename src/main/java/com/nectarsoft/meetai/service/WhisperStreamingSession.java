package com.nectarsoft.meetai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.*;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestTemplate;

import java.io.*;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;

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

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // OpenAiWhisperEngine과 동일한 유튜브 자막용 환각 멘트 블랙리스트
    private static final Pattern HALLUCINATION_PATTERN = Pattern.compile(
            "시청해\\s*주셔서 감사합니다|" +
                    "구독과 좋아요|" +
                    "MBC 뉴스|" +
                    "KBS 뉴스|" +
                    "다음에 또 만나요|" +
                    "다음 영상에서|" +
                    "Thank you for watching"
    );

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

    // MediaRecorder timeslice 연속 청크 복원용: 첫 webm 청크의 init segment(EBML 헤더+Tracks) 캐시
    private volatile byte[] webmInit;

    public void sendAudio(byte[] data) {
        if (closed) return;
        long endMs = System.currentTimeMillis();

        // 헤더 있는 webm 청크 — init segment 추출 후 즉시 변환
        if (isWebm(data)) {
            byte[] init = extractWebmInit(data);
            if (init != null) webmInit = init;
            scheduler.execute(() -> transcribeSegment(data, endMs));
            return;
        }
        if (isOgg(data)) {
            scheduler.execute(() -> transcribeSegment(data, endMs));
            return;
        }
        // 헤더 없는 webm 연속 청크 (MediaRecorder timeslice) — init segment을 붙여 완결 파일로 복원
        if (webmInit != null) {
            byte[] full = new byte[webmInit.length + data.length];
            System.arraycopy(webmInit, 0, full, 0, webmInit.length);
            System.arraycopy(data, 0, full, webmInit.length, data.length);
            scheduler.execute(() -> transcribeSegment(full, endMs));
            return;
        }
        // raw PCM 경로 (5초 버퍼 후 WAV 변환)
        synchronized (audioBuffer) {
            audioBuffer.write(data, 0, data.length);
        }
    }

    private static boolean isWebm(byte[] d) {
        return d.length >= 4 && (d[0] & 0xFF) == 0x1A && (d[1] & 0xFF) == 0x45
                && (d[2] & 0xFF) == 0xDF && (d[3] & 0xFF) == 0xA3;
    }

    private static boolean isOgg(byte[] d) {
        return d.length >= 4 && d[0] == 'O' && d[1] == 'g' && d[2] == 'g' && d[3] == 'S';
    }

    /** 첫 Cluster(0x1F43B675) 직전까지가 init segment — 못 찾으면 복원 불가(null) */
    private static byte[] extractWebmInit(byte[] chunk) {
        for (int i = 0; i + 3 < chunk.length; i++) {
            if ((chunk[i] & 0xFF) == 0x1F && (chunk[i + 1] & 0xFF) == 0x43
                    && (chunk[i + 2] & 0xFF) == 0xB6 && (chunk[i + 3] & 0xFF) == 0x75) {
                byte[] init = new byte[i];
                System.arraycopy(chunk, 0, init, 0, i);
                return init;
            }
        }
        return null;
    }

    private void transcribeSegment(byte[] webm, long endMs) {
        try {
            String text = transcribe(webm, "audio.webm");
            if (text != null && !text.isBlank()) {
                double endSec   = (endMs - meetingStartMs) / 1000.0;
                double startSec = Math.max(0, endSec - FLUSH_INTERVAL_SEC);
                onFinal.accept(new Transcript(text.trim(), startSec, endSec));
                log.info("[Whisper] webm 변환 완료 — [{}-{}s] \"{}\"",
                        String.format("%.1f", startSec), String.format("%.1f", endSec), text.trim());
            }
        } catch (Exception e) {
            log.error("[Whisper] webm 변환 실패: {}", e.getMessage());
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
            String text = transcribe(wav, "audio.wav");
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

    private String transcribe(byte[] audio, String filename) throws IOException {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(audio) {
            @Override public String getFilename() { return filename; }
        });
        body.add("model", model);
        body.add("language", language);
        body.add("response_format", "verbose_json");
        body.add("temperature", "0.0");
        body.add("prompt", "한국어 회의 내용입니다. 음성이 없는 구간은 침묵하고, 절대 말을 지어내지 마세요.");

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);
        headers.setBearerAuth(apiKey);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "https://api.openai.com/v1/audio/transcriptions",
                new HttpEntity<>(body, headers),
                String.class);

        if (response.getStatusCode() != HttpStatus.OK || response.getBody() == null) return null;
        return filterSegments(MAPPER.readTree(response.getBody()));
    }

    /** OpenAiWhisperEngine과 동일 기준의 환각 필터링 후 통과한 구간 텍스트만 이어붙임 */
    private String filterSegments(JsonNode root) {
        JsonNode segs = root.path("segments");
        if (segs.isMissingNode() || segs.isEmpty()) {
            String text = root.path("text").asText("").strip();
            return isHallucinationText(text) ? null : text;
        }

        List<String> kept = new ArrayList<>();
        for (JsonNode s : segs) {
            String text = s.path("text").asText("").strip();
            if (text.isEmpty()) continue;

            double noSpeechProb     = s.path("no_speech_prob").asDouble(0.0);
            double avgLogprob       = s.path("avg_logprob").asDouble(0.0);
            double compressionRatio = s.path("compression_ratio").asDouble(1.0);

            if (noSpeechProb > 0.5 && avgLogprob < -1.0) {
                log.debug("[Whisper] 묵음 구간 제외 — no_speech_prob={}", noSpeechProb);
                continue;
            }
            if (compressionRatio > 2.4 || compressionRatio < 0.5) {
                log.debug("[Whisper] 비정상 압축률(환각 의심) 제외 — ratio={}, text={}", compressionRatio, text);
                continue;
            }
            if (isHallucinationText(text)) {
                log.debug("[Whisper] 블랙리스트 환각 제외 — text={}", text);
                continue;
            }
            double duration = s.path("end").asDouble() - s.path("start").asDouble();
            if (duration > 0 && (text.length() / duration) > 15) {
                log.debug("[Whisper] 시간 대비 과도한 텍스트(환각) 제외 — duration={}, len={}", duration, text.length());
                continue;
            }
            kept.add(text);
        }
        return kept.isEmpty() ? null : String.join(" ", kept);
    }

    /** 환각 키워드 및 동일 단어 3회 이상 연속 반복 검사 */
    private boolean isHallucinationText(String text) {
        if (text.isEmpty()) return true;
        if (HALLUCINATION_PATTERN.matcher(text).find()) return true;
        String[] words = text.split("\\s+");
        int repeat = 1;
        for (int i = 1; i < words.length; i++) {
            if (words[i].equals(words[i - 1])) {
                if (++repeat >= 3) return true;
            } else {
                repeat = 1;
            }
        }
        return false;
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
