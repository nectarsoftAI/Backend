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

@Slf4j
public class OpenAiWhisperEngine implements SttEngine {

    private static final String WHISPER_URL = "https://api.openai.com/v1/audio/transcriptions";

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

        RequestBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("model", cfg.getWhisperModel())
                .addFormDataPart("language", cfg.getWhisperLanguage())
                .addFormDataPart("response_format", "verbose_json")
                .addFormDataPart("timestamp_granularities[]", "segment")
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
            // verbose_json 없이 text만 있는 경우
            String text = root.path("text").asText("");
            result.add(RawSegment.builder()
                    .speakerId("SPEAKER_00")
                    .startSec(0.0).endSec(0.0)
                    .text(text).confidence(1.0).lowConfidence(false).build());
            return result;
        }

        for (int i = 0; i < segs.size(); i++) {
            JsonNode s = segs.get(i);
            double conf = s.path("avg_logprob").isMissingNode() ? 1.0
                    : logprobToConfidence(s.path("avg_logprob").asDouble());
            result.add(RawSegment.builder()
                    .speakerId("SPEAKER_00")
                    .startSec(s.path("start").asDouble())
                    .endSec(s.path("end").asDouble())
                    .text(s.path("text").asText("").strip())
                    .confidence(conf)
                    .lowConfidence(conf < props.getStt().getConfidenceThreshold())
                    .build());
        }
        log.info("[Whisper] 파싱 완료 — {} 구간", result.size());
        return result;
    }

    /** avg_logprob (-∞ ~ 0) → confidence (0 ~ 1) */
    private double logprobToConfidence(double logprob) {
        return Math.exp(Math.max(logprob, -10.0));
    }

    private String extension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase() : "wav";
    }
}
