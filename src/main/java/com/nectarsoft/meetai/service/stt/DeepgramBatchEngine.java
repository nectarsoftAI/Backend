package com.nectarsoft.meetai.service.stt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nectarsoft.meetai.config.MeetAiProperties;
import com.nectarsoft.meetai.core.exception.Exceptions;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Deepgram pre-recorded(배치) STT + 화자 분리 — 업로드 파일용.
 *
 * 실시간 녹음(DeepgramStreamingSession)과 같은 API 키·같은 nova-3 모델을 쓰므로
 * 업로드/실시간의 화자 라벨 체계(SPEAKER_A, SPEAKER_B, ...)가 일치한다.
 *
 * - 파일을 바이너리 본문으로 POST (업로드 후 URL을 넘기는 2단계 방식이 아니라 1회 요청)
 * - utterances=true 로 화자별 발화 단위 세그먼트를 그대로 받는다.
 *   (words[].speaker 를 직접 묶을 수도 있지만 utterances 쪽이 문장 경계까지 잡아준다)
 * - 응답이 동기라 AssemblyAI처럼 polling이 필요 없다
 */
@Slf4j
public class DeepgramBatchEngine implements SttEngine {

    private static final String BASE_URL = "https://api.deepgram.com/v1/listen";

    private final MeetAiProperties props;
    private final OkHttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public DeepgramBatchEngine(MeetAiProperties props) {
        this.props = props;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                // 장시간 녹음은 변환에 수 분이 걸린다 (2시간 상한 기준 여유 있게)
                .readTimeout(10, TimeUnit.MINUTES)
                .writeTimeout(5, TimeUnit.MINUTES)
                .build();
    }

    @Override
    public List<RawSegment> transcribe(Path audioPath) {
        log.info("[DeepgramBatch] 변환 시작 (화자 분리 활성) — {}", audioPath.getFileName());
        return request(audioPath, true);
    }

    @Override
    public List<RawSegment> transcribeSingleSpeaker(Path audioPath) {
        log.info("[DeepgramBatch] 변환 시작 (단일 화자 모드) — {}", audioPath.getFileName());
        return request(audioPath, false);
    }

    private List<RawSegment> request(Path audioPath, boolean diarize) {
        MeetAiProperties.Deepgram cfg = props.getDeepgram();
        if (cfg.getApiKey() == null || cfg.getApiKey().isBlank()) {
            throw new Exceptions.SttFailedError("DEEPGRAM_API_KEY 미설정 — 업로드 STT 불가");
        }

        // 컨테이너 포맷(mp3/m4a/wav 등)은 Deepgram이 알아서 판별하므로 encoding 지정을 하지 않는다.
        // 지정하면 오히려 raw PCM으로 오인해 전사가 깨진다
        HttpUrl url = HttpUrl.parse(BASE_URL).newBuilder()
                .addQueryParameter("model", cfg.getModel())
                .addQueryParameter("language", cfg.getLanguage())
                .addQueryParameter("smart_format", "true")
                .addQueryParameter("punctuate", "true")
                .addQueryParameter("diarize", String.valueOf(diarize))
                .addQueryParameter("utterances", String.valueOf(diarize))
                .build();

        // 업로드 파일은 코덱이 제각각이라(통화 녹음 m4a 등) Deepgram이 400
        // "corrupt or unsupported data"를 반환하는 경우가 있다.
        // ffmpeg로 16kHz mono WAV로 정규화해 보내면 컨테이너·코덱 문제를 원천 차단한다
        Path converted = transcodeToWav(audioPath);
        Path toSend = converted != null ? converted : audioPath;
        MediaType type = converted != null
                ? MediaType.parse("audio/wav")
                : guessMediaType(audioPath);

        try {
            byte[] audio = Files.readAllBytes(toSend);
            Request request = new Request.Builder()
                    .url(url)
                    .header("Authorization", "Token " + cfg.getApiKey())
                    .post(RequestBody.create(audio, type))
                    .build();

            try (Response response = http.newCall(request).execute()) {
                String raw = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    throw new Exceptions.SttFailedError(
                            "Deepgram 배치 변환 실패 " + response.code() + ": " + raw);
                }
                return parseResult(mapper.readTree(raw));
            }
        } catch (IOException e) {
            throw new Exceptions.SttFailedError("Deepgram 배치 변환 중 오류", e);
        } finally {
            if (converted != null) {
                try {
                    Files.deleteIfExists(converted);
                } catch (IOException ignored) {
                    // 임시 파일 정리 실패는 변환 결과에 영향 없음
                }
            }
        }
    }

    /**
     * ffmpeg로 16kHz mono WAV 변환. ffmpeg가 없거나 변환에 실패하면 null을 반환해
     * 호출부가 원본을 그대로 전송하도록 폴백한다.
     * -vn: 영상 파일(mp4/mov) 업로드 시 영상 트랙을 버리고 오디오만 남긴다
     */
    private Path transcodeToWav(Path source) {
        if (!com.nectarsoft.meetai.service.FfmpegPcmDecoder.isAvailable()) {
            log.warn("[DeepgramBatch] ffmpeg 없음 — 원본 그대로 전송 (코덱에 따라 400 가능)");
            return null;
        }
        Path out = null;
        try {
            out = Files.createTempFile("dg-batch-", ".wav");
            Process p = new ProcessBuilder(
                    "ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                    "-i", source.toString(),
                    "-vn", "-ac", "1", "-ar", "16000", "-c:a", "pcm_s16le",
                    out.toString())
                    .redirectErrorStream(true)
                    .start();
            String err = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            boolean done = p.waitFor(5, TimeUnit.MINUTES);
            if (!done || p.exitValue() != 0 || Files.size(out) == 0) {
                if (!done) p.destroyForcibly();
                log.warn("[DeepgramBatch] ffmpeg 변환 실패 — 원본 전송으로 폴백: {}", err.strip());
                Files.deleteIfExists(out);
                return null;
            }
            log.info("[DeepgramBatch] ffmpeg 정규화 완료 — {} → 16kHz mono WAV ({}KB)",
                    source.getFileName(), Files.size(out) / 1024);
            return out;
        } catch (Exception e) {
            log.warn("[DeepgramBatch] ffmpeg 실행 오류 — 원본 전송으로 폴백: {}", e.getMessage());
            if (out != null) {
                try {
                    Files.deleteIfExists(out);
                } catch (IOException ignored) {
                    // 정리 실패는 무시
                }
            }
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            return null;
        }
    }

    /** ffmpeg 폴백 시 확장자로 Content-Type 추정 — octet-stream이면 Deepgram이 포맷을 못 읽을 수 있다 */
    private MediaType guessMediaType(Path path) {
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        String mime =
                name.endsWith(".wav") ? "audio/wav" :
                name.endsWith(".mp3") ? "audio/mpeg" :
                name.endsWith(".m4a") || name.endsWith(".mp4") ? "audio/mp4" :
                name.endsWith(".aac") ? "audio/aac" :
                name.endsWith(".ogg") ? "audio/ogg" :
                name.endsWith(".flac") ? "audio/flac" :
                name.endsWith(".webm") ? "audio/webm" : "application/octet-stream";
        return MediaType.parse(mime);
    }

    /**
     * utterances(화자별 발화)를 우선 파싱하고, 없으면 words[].speaker로 직접 묶는다.
     * 둘 다 없으면 전체 transcript를 단일 화자로 반환해 회의록이 통째로 비는 것을 막는다.
     */
    private List<RawSegment> parseResult(JsonNode root) {
        JsonNode utterances = root.path("results").path("utterances");
        if (utterances.isArray() && !utterances.isEmpty()) {
            List<RawSegment> result = new ArrayList<>();
            for (JsonNode u : utterances) {
                String text = u.path("transcript").asText("").strip();
                if (text.isEmpty()) continue;
                double conf = u.path("confidence").asDouble(1.0);
                result.add(RawSegment.builder()
                        .speakerId(speakerLabel(u.path("speaker").asInt(0)))
                        .startSec(u.path("start").asDouble(0))
                        .endSec(u.path("end").asDouble(0))
                        .text(text)
                        .confidence(conf)
                        .lowConfidence(conf < props.getStt().getConfidenceThreshold())
                        .build());
            }
            log.info("[DeepgramBatch] 파싱 완료 — {}개 발화, 화자 {}명",
                    result.size(),
                    result.stream().map(RawSegment::getSpeakerId).distinct().count());
            return result;
        }

        JsonNode alt = root.path("results").path("channels").path(0).path("alternatives").path(0);
        List<RawSegment> byWords = groupWordsBySpeaker(alt);
        if (!byWords.isEmpty()) {
            log.info("[DeepgramBatch] utterances 없음 — words[].speaker로 묶음: {}개 발화, 화자 {}명",
                    byWords.size(),
                    byWords.stream().map(RawSegment::getSpeakerId).distinct().count());
            return byWords;
        }

        List<RawSegment> result = new ArrayList<>();
        String text = alt.path("transcript").asText("").strip();
        if (!text.isEmpty()) {
            result.add(RawSegment.builder()
                    .speakerId("SPEAKER_00")
                    .startSec(0.0).endSec(0.0)
                    .text(text).confidence(1.0).lowConfidence(false).build());
        }
        log.warn("[DeepgramBatch] 화자 정보 없음 — 단일 화자로 반환 (diarize 미적용 의심)");
        return result;
    }

    /** words[].speaker를 화자가 바뀔 때까지 이어붙여 발화 단위로 만든다 (실시간 턴 누적과 같은 규칙) */
    private List<RawSegment> groupWordsBySpeaker(JsonNode alt) {
        List<RawSegment> result = new ArrayList<>();
        JsonNode words = alt.path("words");
        if (!words.isArray() || words.isEmpty()) return result;

        Integer curSpeaker = null;
        StringBuilder buf = new StringBuilder();
        double start = 0, end = 0, confSum = 0;
        int count = 0;

        for (JsonNode w : words) {
            String token = w.path("punctuated_word").asText("");
            if (token.isEmpty()) token = w.path("word").asText("");
            if (token.isEmpty()) continue;

            int sp = w.path("speaker").asInt(0);
            if (curSpeaker != null && sp != curSpeaker) {
                addGrouped(result, curSpeaker, buf, start, end, confSum, count);
                buf.setLength(0);
                confSum = 0;
                count = 0;
                curSpeaker = null;
            }
            if (curSpeaker == null) {
                curSpeaker = sp;
                start = w.path("start").asDouble(0);
            }
            if (buf.length() > 0) buf.append(' ');
            buf.append(token);
            end = w.path("end").asDouble(0);
            confSum += w.path("confidence").asDouble(1.0);
            count++;
        }
        addGrouped(result, curSpeaker, buf, start, end, confSum, count);
        return result;
    }

    private void addGrouped(List<RawSegment> out, Integer speaker, StringBuilder buf,
                            double start, double end, double confSum, int count) {
        String text = buf.toString().strip();
        if (speaker == null || text.isEmpty() || count == 0) return;
        double conf = confSum / count;
        out.add(RawSegment.builder()
                .speakerId(speakerLabel(speaker))
                .startSec(start).endSec(end)
                .text(text).confidence(conf)
                .lowConfidence(conf < props.getStt().getConfidenceThreshold())
                .build());
    }

    /** 실시간(DeepgramStreamingSession)과 동일한 라벨 규칙 — 업로드/실시간 회의록 표기를 맞춘다 */
    private static String speakerLabel(int n) {
        return (n >= 0 && n < 26) ? "SPEAKER_" + (char) ('A' + n) : "SPEAKER_" + n;
    }
}
