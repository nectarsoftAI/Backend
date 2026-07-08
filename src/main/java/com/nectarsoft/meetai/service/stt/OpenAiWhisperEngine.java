package com.nectarsoft.meetai.service.stt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nectarsoft.meetai.config.MeetAiProperties;
import com.nectarsoft.meetai.core.exception.Exceptions;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

@Slf4j
public class OpenAiWhisperEngine implements SttEngine {

    private static final String WHISPER_URL = "https://api.openai.com/v1/audio/transcriptions";

    // 대표적인 Whisper 한국어 환각/반복 패턴 정의 (회의록 상황에 맞춰 추가 가능)
    private static final Pattern HALLUCINATION_PATTERN = Pattern.compile(
            "시청해 주셔서 감사합니다|" +
                    "구독과 좋아요|" +
                    "MBC 뉴스|" +
                    "KBS 뉴스|" +
                    "다음에 또 만나요|" +
                    "Thank you for watching"
    );

    private final MeetAiProperties props;
    private final OkHttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public OpenAiWhisperEngine(MeetAiProperties props) {
        this.props = props;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public List<RawSegment> transcribe(Path audioPath) {
        MeetAiProperties.Openai cfg = props.getOpenai();
        log.info("[Whisper] 변환 시작 — {}", audioPath.getFileName());

        // 주의: prompt 파라미터는 무음 구간에서 프롬프트 문장이 결과로 유출되는 부작용이 있어 사용 금지
        // (실측: "음성이 없는 구간은 침묵하고..."가 transcript에 그대로 섞여 나옴)
        RequestBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", cfg.getWhisperModel())
                .addFormDataPart("language", cfg.getWhisperLanguage())
                .addFormDataPart("response_format", "verbose_json")
                .addFormDataPart("timestamp_granularities[]", "segment")
                .addFormDataPart("temperature", "0.0") // 실시간/정밀 변환에서 환각을 줄이기 위해 온도를 0으로 고정
                .addFormDataPart("file",
                        audioPath.getFileName().toString(),
                        RequestBody.create(audioPath.toFile(),
                                MediaType.parse("audio/" + extension(audioPath))))
                .build();

        Request request = new Request.Builder()
                .url(WHISPER_URL)
                .header("Authorization", "Bearer " + cfg.getApiKey())
                .post(body)
                .build();

        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String err = response.body() != null ? response.body().string() : "(empty)";
                throw new Exceptions.SttFailedError("Whisper API 오류 " + response.code() + ": " + err);
            }
            return parseResponse(response.body().string());
        } catch (IOException ex) {
            throw new Exceptions.SttFailedError("Whisper API 연결 실패", ex);
        }
    }

    private List<RawSegment> parseResponse(String json) throws IOException {
        JsonNode root = mapper.readTree(json);
        JsonNode segs = root.path("segments");
        List<RawSegment> result = new ArrayList<>();

        if (segs.isMissingNode() || segs.isEmpty()) {
            String text = root.path("text").asText("").strip();
            if (!text.isEmpty() && !isHallucinationText(text)) {
                result.add(RawSegment.builder()
                        .speakerId("SPEAKER_00")
                        .startSec(0.0).endSec(0.0)
                        .text(text).confidence(1.0).lowConfidence(false).build());
            }
            return result;
        }

        for (int i = 0; i < segs.size(); i++) {
            JsonNode s = segs.get(i);
            String text = s.path("text").asText("").strip();
            if (text.isEmpty()) continue;

            // 1. no_speech_prob 기준 완화 및 복합 필터링 적용
            double noSpeechProb = s.path("no_speech_prob").asDouble(0.0);
            double avgLogprob = s.path("avg_logprob").asDouble(0.0);
            double compressionRatio = s.path("compression_ratio").asDouble(1.0);

            // 묵음일 확률이 높거나, 소리가 너무 작아서 신뢰도가 심각하게 낮은 경우 (-1.5 미만은 대개 환각)
            if (noSpeechProb > 0.5 && avgLogprob < -1.0) {
                log.debug("[Whisper] 묵음 구간 확정 제외 — no_speech_prob={}, avg_logprob={}", noSpeechProb, avgLogprob);
                continue;
            }

            // 2. compression_ratio 기반 반복구 환각 필터링
            // Whisper는 환각이 시작되면 같은 말을 무한 반복하여 압축률이 비정상적으로 높아지거나(대개 2.4 이상),
            // 반대로 너무 무작위 문자열이 나와 압축률이 극도로 낮아집니다.
            if (compressionRatio > 2.4 || compressionRatio < 0.5) {
                log.debug("[Whisper] 비정상 압축률(환각 의심) 제외 — compression_ratio={}, text={}", compressionRatio, text);
                continue;
            }

            // 3. 텍스트 패턴 기반 환각 필터링
            if (isHallucinationText(text)) {
                log.debug("[Whisper] 블랙리스트 패턴 환각 제외 — text={}", text);
                continue;
            }

            // 4. 글자 수 대비 오디오 길이 검증 (예: 0.5초 동안 30글자가 찍히는 환각 방지)
            double start = s.path("start").asDouble();
            double end = s.path("end").asDouble();
            double duration = end - start;
            if (duration > 0 && (text.length() / duration) > 15) { // 1초당 15자 이상은 현실적으로 불가능
                log.debug("[Whisper] 시간 대비 과도한 텍스트 생성(환각) 제외 — duration={}, textLength={}", duration, text.length());
                continue;
            }

            double conf = s.path("avg_logprob").isMissingNode() ? 1.0 : logprobToConfidence(avgLogprob);

            result.add(RawSegment.builder()
                    .speakerId("SPEAKER_00")
                    .startSec(start)
                    .endSec(end)
                    .text(text)
                    .confidence(conf)
                    .lowConfidence(conf < props.getStt().getConfidenceThreshold())
                    .build());
        }
        log.info("[Whisper] 파싱 완료 — 정제 후 {}개 구간 남음", result.size());
        return result;
    }

    /** 대표적인 환각 키워드 및 단어 반복 패턴 검사 */
    private boolean isHallucinationText(String text) {
        // 정규식 매칭 (유튜브 자막용 환각 멘트 차단)
        if (HALLUCINATION_PATTERN.matcher(text).find()) {
            return true;
        }

        // 단어 단위 3회 이상 연속 반복 검사 (예: "네 네 네", "오오오")
        String[] words = text.split("\\s+");
        if (words.length >= 3) {
            int repeatCount = 1;
            for (int i = 1; i < words.length; i++) {
                if (words[i].equals(words[i - 1])) {
                    repeatCount++;
                    if (repeatCount >= 3) return true;
                } else {
                    repeatCount = 1;
                }
            }
        }
        return false;
    }

    private double logprobToConfidence(double logprob) {
        return Math.exp(Math.max(logprob, -10.0));
    }

    private String extension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase() : "wav";
    }
}