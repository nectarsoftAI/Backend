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
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * OpenAI Whisper 기반 음성 인식 세션
 * - webm/ogg 청크 → 클러스터 경계 복원 → ffmpeg 디코딩(16kHz PCM)
 * - 에너지 VAD로 발화(utterance) 단위 분리: 무음 구간은 Whisper 호출 자체를 차단 (환각 원천 방지 + 비용 절감)
 * - ffmpeg 없는 환경에서는 컨테이너 직접 전송 + no_speech_prob 등 메타데이터 필터로 폴백
 * - 한국어(ko) 지원
 */
@Slf4j
public class WhisperStreamingSession implements SttStreamSession {

    public record Transcript(String text, double startSec, double endSec) {}

    private static final int FLUSH_INTERVAL_SEC = 5;
    private static final int SAMPLE_RATE = 16000;

    // ── 에너지 기반 VAD ────────────────────────────────────────────
    // 역할 분리: VAD = 무음 구간 Whisper 호출 원천 차단(환각 방지), no_speech_prob 필터 = 인식 민감도
    private static final int FRAME_BYTES = 960;                          // 30ms @ 16kHz 16-bit mono
    private static final int VAD_RMS_THRESHOLD = 500;                    // 무음 판정 RMS 경계 (0~32767)
    private static final int SILENCE_END_FRAMES = 24;                    // 720ms 침묵 → 발화 종료
    private static final int MIN_VOICED_FRAMES = 8;                      // 240ms 미만 발화는 잡음으로 폐기
    private static final int MAX_UTTERANCE_BYTES = 25 * SAMPLE_RATE * 2; // 발화 최대 25초 (강제 분할)
    private static final int PRE_ROLL_FRAMES = 5;                        // 발화 시작 전 150ms 포함 (첫 음절 보존)

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

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "whisper-flush");
                t.setDaemon(true);
                return t;
            });
    private volatile boolean closed = false;

    // ── VAD/발화 상태 (whisper-flush 단일 스레드에서만 접근) ──────
    private final ByteArrayOutputStream frameRemainder = new ByteArrayOutputStream();
    private final ArrayDeque<byte[]> preRoll = new ArrayDeque<>();
    private final ByteArrayOutputStream utteranceBuf = new ByteArrayOutputStream();
    private boolean inUtterance = false;
    private int silentFrames = 0;
    private int voicedFrames = 0;
    private volatile long lastAudioMs = System.currentTimeMillis();

    public WhisperStreamingSession(String apiKey, String model, String language,
                                   long sessionOffsetMs, Consumer<Transcript> onFinal) {
        this.apiKey = apiKey;
        this.model = model;
        this.language = language;
        this.meetingStartMs = System.currentTimeMillis() - sessionOffsetMs;
        this.onFinal = onFinal;
        this.restTemplate = buildRestTemplate();
        scheduler.scheduleAtFixedRate(this::flush, FLUSH_INTERVAL_SEC, FLUSH_INTERVAL_SEC, TimeUnit.SECONDS);
        log.info("[Whisper] 세션 시작 — offsetMs={}, ffmpeg={}", sessionOffsetMs, FfmpegPcmDecoder.isAvailable());
    }

    // MediaRecorder timeslice 연속 청크 복원용: 첫 webm 청크의 init segment(EBML 헤더+Tracks)만 캐시
    // 주의: 첫 청크 전체를 init으로 쓰면 그 안의 실제 음성이 매 세그먼트마다 반복 변환됨
    private volatile byte[] webmInit;
    private volatile boolean webmMode = false;
    // 미처리 클러스터 누적 버퍼 — flush 시 클러스터 경계에 맞춰 잘라 init과 결합
    private final ByteArrayOutputStream webmClusterBuf = new ByteArrayOutputStream();

    @Override
    public void sendAudio(byte[] data) {
        if (closed) return;
        long endMs = System.currentTimeMillis();

        // 헤더 있는 webm 청크 — 진짜 헤더(첫 Cluster 이전)만 init으로 저장하고
        // 첫 Cluster부터는 일반 청크로 누적 (첫 발화 반복 방지)
        if (isWebm(data)) {
            webmMode = true;
            int clusterPos = indexOfCluster(data, 0);
            if (clusterPos > 0) {
                if (webmInit == null) {
                    byte[] init = new byte[clusterPos];
                    System.arraycopy(data, 0, init, 0, clusterPos);
                    webmInit = init;
                }
                synchronized (webmClusterBuf) {
                    webmClusterBuf.write(data, clusterPos, data.length - clusterPos);
                }
            } else {
                // Cluster 경계를 못 찾으면 기존 방식으로 안전 폴백: 자체 완결 파일로 처리
                log.warn("[Whisper] webm 헤더 청크에서 Cluster 경계 미발견 — 단독 변환 폴백 ({}바이트)", data.length);
                scheduler.execute(() -> decodeAndProcess(data, endMs));
            }
            return;
        }
        if (isOgg(data)) {
            scheduler.execute(() -> decodeAndProcess(data, endMs));
            return;
        }
        // 헤더 없는 webm 연속 청크 (MediaRecorder timeslice) — 누적 후 flush에서 경계 정렬 처리
        if (webmMode) {
            if (webmInit == null) {
                log.warn("[Whisper] init segment 없음 — 연속 청크 {}바이트 폐기", data.length);
                return;
            }
            synchronized (webmClusterBuf) {
                webmClusterBuf.write(data, 0, data.length);
            }
            return;
        }
        // raw PCM 경로 — 바로 VAD 파이프라인 투입
        scheduler.execute(() -> processPcm(data));
    }

    /**
     * 누적된 연속 클러스터를 첫 Cluster 경계부터 잘라 init segment을 붙여 완결 webm으로 변환.
     * 청크가 클러스터 중간에서 잘려 온 경우 경계 이전 파편은 버림 (한 번의 짧은 유실로 정렬 복구).
     */
    private void flushWebmClusters() {
        if (webmInit == null) return;
        byte[] clusters;
        synchronized (webmClusterBuf) {
            if (webmClusterBuf.size() == 0) return;
            clusters = webmClusterBuf.toByteArray();
            webmClusterBuf.reset();
        }
        int start = indexOfCluster(clusters, 0);
        if (start < 0) {
            log.warn("[Whisper] 클러스터 경계 없음 — {}바이트 폐기", clusters.length);
            return;
        }
        byte[] file = new byte[webmInit.length + clusters.length - start];
        System.arraycopy(webmInit, 0, file, 0, webmInit.length);
        System.arraycopy(clusters, start, file, webmInit.length, clusters.length - start);
        decodeAndProcess(file, System.currentTimeMillis());
    }

    /**
     * webm/ogg 세그먼트 → ffmpeg 디코딩 → VAD 파이프라인.
     * ffmpeg 불가 시 기존 방식(컨테이너 그대로 Whisper 전송 + 메타데이터 필터)으로 폴백.
     */
    private void decodeAndProcess(byte[] container, long endMs) {
        byte[] pcm = FfmpegPcmDecoder.decode(container);
        if (pcm == null) {
            transcribeSegment(container, endMs);
            return;
        }
        processPcm(pcm);
    }

    // ── VAD: 30ms 프레임 에너지 분석 → 발화(utterance) 단위 분리 ──
    private void processPcm(byte[] pcm) {
        lastAudioMs = System.currentTimeMillis();
        frameRemainder.write(pcm, 0, pcm.length);
        byte[] all = frameRemainder.toByteArray();
        int full = (all.length / FRAME_BYTES) * FRAME_BYTES;
        for (int off = 0; off < full; off += FRAME_BYTES) {
            byte[] frame = new byte[FRAME_BYTES];
            System.arraycopy(all, off, frame, 0, FRAME_BYTES);
            handleFrame(frame);
        }
        frameRemainder.reset();
        frameRemainder.write(all, full, all.length - full);
    }

    private void handleFrame(byte[] frame) {
        boolean voiced = rms(frame) > VAD_RMS_THRESHOLD;
        if (inUtterance) {
            utteranceBuf.write(frame, 0, frame.length);
            if (voiced) {
                voicedFrames++;
                silentFrames = 0;
            } else if (++silentFrames >= SILENCE_END_FRAMES) {
                endUtterance();
                return;
            }
            if (utteranceBuf.size() >= MAX_UTTERANCE_BYTES) {
                endUtterance();
            }
        } else if (voiced) {
            inUtterance = true;
            voicedFrames = 1;
            silentFrames = 0;
            for (byte[] pre : preRoll) utteranceBuf.write(pre, 0, pre.length);
            preRoll.clear();
            utteranceBuf.write(frame, 0, frame.length);
        } else {
            preRoll.addLast(frame);
            if (preRoll.size() > PRE_ROLL_FRAMES) preRoll.removeFirst();
        }
    }

    private static double rms(byte[] f) {
        long sum = 0;
        int n = f.length / 2;
        for (int i = 0; i + 1 < f.length; i += 2) {
            int s = (short) ((f[i] & 0xFF) | (f[i + 1] << 8));
            sum += (long) s * s;
        }
        return Math.sqrt((double) sum / n);
    }

    private void endUtterance() {
        byte[] pcm = utteranceBuf.toByteArray();
        utteranceBuf.reset();
        inUtterance = false;
        silentFrames = 0;
        int voiced = voicedFrames;
        voicedFrames = 0;

        if (voiced < MIN_VOICED_FRAMES) {
            log.debug("[VAD] 짧은 발화 폐기 — voicedFrames={}", voiced);
            return;
        }
        try {
            double durationSec = pcm.length / (double) (SAMPLE_RATE * 2);
            double endSec = Math.max(0, (lastAudioMs - meetingStartMs) / 1000.0);
            double startSec = Math.max(0, endSec - durationSec);
            String text = transcribe(toWav(pcm), "audio.wav");
            if (text != null && !text.isBlank()) {
                onFinal.accept(new Transcript(text.trim(), startSec, endSec));
                log.info("[Whisper] 발화 변환 완료 — [{}-{}s, {}s분량] \"{}\"",
                        String.format("%.1f", startSec), String.format("%.1f", endSec),
                        String.format("%.1f", durationSec), text.trim());
            }
        } catch (Exception e) {
            log.error("[Whisper] 발화 변환 실패: {}", e.getMessage());
        }
    }

    /** Cluster 엘리먼트 ID(0x1F43B675) 위치 탐색 */
    private static int indexOfCluster(byte[] d, int from) {
        for (int i = from; i + 3 < d.length; i++) {
            if ((d[i] & 0xFF) == 0x1F && (d[i + 1] & 0xFF) == 0x43
                    && (d[i + 2] & 0xFF) == 0xB6 && (d[i + 3] & 0xFF) == 0x75) {
                return i;
            }
        }
        return -1;
    }

    private static boolean isWebm(byte[] d) {
        return d.length >= 4 && (d[0] & 0xFF) == 0x1A && (d[1] & 0xFF) == 0x45
                && (d[2] & 0xFF) == 0xDF && (d[3] & 0xFF) == 0xA3;
    }

    private static boolean isOgg(byte[] d) {
        return d.length >= 4 && d[0] == 'O' && d[1] == 'g' && d[2] == 'g' && d[3] == 'S';
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

    @Override
    public void close() {
        if (closed) return;
        closed = true;
        // VAD 상태는 whisper-flush 스레드 전용이므로 마지막 정리도 그 스레드에서 실행
        scheduler.execute(() -> {
            flushWebmClusters();
            if (inUtterance) endUtterance();
        });
        scheduler.shutdown();
    }

    private void flush() {
        flushWebmClusters();
        // 오디오 유입이 끊긴 채 발화가 열려 있으면 종료 처리 (마이크 OFF 등)
        if (inUtterance && System.currentTimeMillis() - lastAudioMs > 1500) {
            endUtterance();
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
        // 주의: prompt 파라미터는 무음 구간에서 프롬프트 문장이 자막으로 유출되는 부작용이 있어 사용 금지
        // (실측: "음성이 없는 구간은 침묵하고..."가 transcript로 브로드캐스트됨)

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
