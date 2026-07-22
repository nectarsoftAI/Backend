package com.nectarsoft.meetai.service.stt;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nectarsoft.meetai.config.MeetAiProperties;
import com.nectarsoft.meetai.core.exception.Exceptions;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * OpenAI gpt-4o-transcribe-diarize 배치 엔진 — 업로드 파일 전사 + 화자 분리.
 *
 * 같은 이름의 OpenAiGpt4oEngine과 혼동하지 말 것. 그쪽은 채팅 API(/chat/completions)에
 * 오디오를 base64로 실어 보내는 구현이라 화자 분리가 없고(SPEAKER_00 고정) 동작하지도 않는다.
 * 이 클래스는 전사 전용 API(/audio/transcriptions)에 diarize 모델을 쓰는 정식 경로이며,
 * 실시간 녹음 폴백(LiveService.diarize)에서 검증된 호출 방식을 그대로 옮겼다.
 */
@Slf4j
public class OpenAiDiarizeEngine implements SttEngine {

    private static final String TRANSCRIBE_URL = "https://api.openai.com/v1/audio/transcriptions";
    private static final String DIARIZE_MODEL = "gpt-4o-transcribe-diarize";

    private final MeetAiProperties props;
    private final OkHttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public OpenAiDiarizeEngine(MeetAiProperties props) {
        this.props = props;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.MINUTES) // 장시간 녹음 대비
                .writeTimeout(5, TimeUnit.MINUTES)
                .build();
    }

    @Override
    public List<RawSegment> transcribe(Path audioPath) {
        log.info("[OpenAI-diarize] 변환 시작 (화자 분리 활성) — {}", audioPath.getFileName());
        return request(audioPath, true);
    }

    /**
     * 화자 분리 없이 전사만. diarize 모델은 화자 분리가 기본이라 끌 수가 없어,
     * 폴백 시에는 일반 전사 모델로 내려간다 (회의록이 통째로 비는 것보다 낫다).
     */
    @Override
    public List<RawSegment> transcribeSingleSpeaker(Path audioPath) {
        log.info("[OpenAI-diarize] 변환 시작 (단일 화자 폴백) — {}", audioPath.getFileName());
        return request(audioPath, false);
    }

    private List<RawSegment> request(Path audioPath, boolean diarize) {
        String apiKey = props.getOpenai().getApiKey();
        if (apiKey == null || apiKey.isBlank()) {
            throw new Exceptions.SttFailedError("OPENAI_API_KEY 미설정 — 업로드 STT 불가");
        }

        try {
            byte[] audio = Files.readAllBytes(audioPath);
            MultipartBody.Builder form = new MultipartBody.Builder()
                    .setType(MultipartBody.FORM)
                    .addFormDataPart("file", audioPath.getFileName().toString(),
                            RequestBody.create(audio, MediaType.parse("application/octet-stream")));

            if (diarize) {
                form.addFormDataPart("model", DIARIZE_MODEL)
                        .addFormDataPart("response_format", "diarized_json")
                        // diarize 모델은 일정 길이 이상이면 chunking_strategy가 필수 (없으면 400)
                        .addFormDataPart("chunking_strategy", "auto");
            } else {
                form.addFormDataPart("model", props.getOpenai().getWhisperModel())
                        .addFormDataPart("response_format", "verbose_json")
                        .addFormDataPart("temperature", "0.0"); // 환각 억제
            }
            form.addFormDataPart("language", props.getOpenai().getWhisperLanguage());

            Request request = new Request.Builder()
                    .url(TRANSCRIBE_URL)
                    .header("Authorization", "Bearer " + apiKey)
                    .post(form.build())
                    .build();

            try (Response response = http.newCall(request).execute()) {
                String raw = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    // diarize 모델은 프로젝트 Limits 허용 목록에 없으면 403(model_not_found)이 난다
                    throw new Exceptions.SttFailedError(
                            "OpenAI diarize 변환 실패 " + response.code() + ": " + raw);
                }
                return diarize ? parseDiarized(mapper.readTree(raw))
                               : parseVerbose(mapper.readTree(raw));
            }
        } catch (IOException e) {
            throw new Exceptions.SttFailedError("OpenAI diarize 변환 중 오류", e);
        }
    }

    /** diarized_json — segments[].speaker 로 화자가 실려 온다 */
    private List<RawSegment> parseDiarized(JsonNode root) {
        List<RawSegment> result = new ArrayList<>();
        for (JsonNode s : root.path("segments")) {
            String text = s.path("text").asText("").strip();
            if (text.isEmpty()) continue;
            result.add(RawSegment.builder()
                    .speakerId(normalizeSpeaker(s.path("speaker").asText("A")))
                    .startSec(s.path("start").asDouble(0))
                    .endSec(s.path("end").asDouble(0))
                    .text(text)
                    .confidence(1.0)   // diarized_json은 세그먼트 신뢰도를 주지 않는다
                    .lowConfidence(false)
                    .build());
        }
        log.info("[OpenAI-diarize] 파싱 완료 — {}개 발화, 화자 {}명",
                result.size(), result.stream().map(RawSegment::getSpeakerId).distinct().count());
        return result;
    }

    /** 폴백(verbose_json) — 화자 정보가 없으므로 단일 화자로 표기 */
    private List<RawSegment> parseVerbose(JsonNode root) {
        List<RawSegment> result = new ArrayList<>();
        for (JsonNode s : root.path("segments")) {
            String text = s.path("text").asText("").strip();
            if (text.isEmpty()) continue;
            result.add(RawSegment.builder()
                    .speakerId("SPEAKER_00")
                    .startSec(s.path("start").asDouble(0))
                    .endSec(s.path("end").asDouble(0))
                    .text(text)
                    .confidence(1.0)
                    .lowConfidence(false)
                    .build());
        }
        log.info("[OpenAI-diarize] 단일 화자 파싱 완료 — {}개 세그먼트", result.size());
        return result;
    }

    /** 응답의 speaker 표기(A, B / 0, 1 등)를 다른 엔진과 같은 SPEAKER_A 형식으로 맞춘다 */
    private static String normalizeSpeaker(String raw) {
        String s = raw.strip();
        if (s.isEmpty()) return "SPEAKER_A";
        if (s.toUpperCase().startsWith("SPEAKER")) return s.toUpperCase().replace(' ', '_');
        try {
            int n = Integer.parseInt(s);
            return (n >= 0 && n < 26) ? "SPEAKER_" + (char) ('A' + n) : "SPEAKER_" + n;
        } catch (NumberFormatException ignored) {
            return "SPEAKER_" + s.toUpperCase();
        }
    }
}
