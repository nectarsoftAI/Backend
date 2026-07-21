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
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Slf4j
public class AssemblyAiEngine implements SttEngine {

    private static final String BASE_URL = "https://api.assemblyai.com/v2";
    private static final int POLL_INTERVAL_MS = 3000;
    private static final int MAX_POLL_ATTEMPTS = 100; // 최대 5분 대기

    private final MeetAiProperties props;
    private final OkHttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public AssemblyAiEngine(MeetAiProperties props) {
        this.props = props;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public List<RawSegment> transcribe(Path audioPath) {
        log.info("[AssemblyAI] 변환 시작 (화자 분리 활성) — {}", audioPath.getFileName());
        String uploadUrl = uploadFile(audioPath);
        String transcriptId = submitTranscription(uploadUrl, true);
        return pollAndParse(transcriptId);
    }

    @Override
    public List<RawSegment> transcribeSingleSpeaker(Path audioPath) {
        log.info("[AssemblyAI] 변환 시작 (단일 화자 모드) — {}", audioPath.getFileName());
        String uploadUrl = uploadFile(audioPath);
        String transcriptId = submitTranscription(uploadUrl, false);
        return pollAndParse(transcriptId);
    }

    // ── 1단계: 파일 업로드 ────────────────────────────────────────────────

    private String uploadFile(Path audioPath) {
        try {
            byte[] bytes = Files.readAllBytes(audioPath);
            RequestBody body = RequestBody.create(bytes, MediaType.parse("application/octet-stream"));
            Request request = new Request.Builder()
                    .url(BASE_URL + "/upload")
                    .header("Authorization", props.getAssemblyai().getApiKey())
                    .post(body)
                    .build();

            try (Response response = http.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    throw new Exceptions.SttFailedError(
                            "AssemblyAI 파일 업로드 실패 " + response.code());
                }
                String uploadUrl = mapper.readTree(response.body().string())
                        .path("upload_url").asText();
                log.debug("[AssemblyAI] 업로드 완료 — upload_url={}", uploadUrl);
                return uploadUrl;
            }
        } catch (IOException ex) {
            throw new Exceptions.SttFailedError("AssemblyAI 파일 업로드 중 IO 오류", ex);
        }
    }

    // ── 2단계: 변환 작업 제출 ─────────────────────────────────────────────

    private String submitTranscription(String uploadUrl, boolean speakerLabels) {
        try {
            MeetAiProperties.AssemblyAi cfg = props.getAssemblyai();

            Map<String, Object> payload = new java.util.LinkedHashMap<>();
            payload.put("audio_url", uploadUrl);
            payload.put("language_code", cfg.getLanguageCode());
            if (speakerLabels) {
                payload.put("speaker_labels", true);
                // 화자 분리 지원 모델(universal-2 / universal-3-5-pro)을 명시.
                // 생략하면 계정 기본 모델이 쓰이는데, 그 모델이 speaker_labels를
                // 지원하지 않으면 요청은 200으로 성공하고 utterances만 비어서 온다
                if (cfg.getSpeechModel() != null && !cfg.getSpeechModel().isBlank()) {
                    payload.put("speech_model", cfg.getSpeechModel());
                }
                if (cfg.getSpeakersExpected() > 0) {
                    // 정확한 화자 수를 아는 경우 — 틀리면 정확도가 오히려 떨어진다
                    payload.put("speakers_expected", cfg.getSpeakersExpected());
                } else if (cfg.getMinSpeakers() > 0 || cfg.getMaxSpeakers() > 0) {
                    // 범위 힌트. min은 하한을 강제하므로 전부 한 화자로 뭉치는 것을 막는다.
                    // 주의: 2분 미만 오디오에서는 이 옵션이 적용되지 않는 이슈가 보고돼 있다
                    Map<String, Object> speakerOptions = new java.util.LinkedHashMap<>();
                    if (cfg.getMinSpeakers() > 0) {
                        speakerOptions.put("min_speakers_expected", cfg.getMinSpeakers());
                    }
                    if (cfg.getMaxSpeakers() > 0) {
                        speakerOptions.put("max_speakers_expected", cfg.getMaxSpeakers());
                    }
                    payload.put("speaker_options", speakerOptions);
                }
                log.info("[AssemblyAI] 화자 분리 요청 — speech_model={}, speakers_expected={}, min={}, max={}",
                        cfg.getSpeechModel(), cfg.getSpeakersExpected(),
                        cfg.getMinSpeakers(), cfg.getMaxSpeakers());
            }

            RequestBody body = RequestBody.create(
                    mapper.writeValueAsString(payload),
                    MediaType.parse("application/json"));

            Request request = new Request.Builder()
                    .url(BASE_URL + "/transcript")
                    .header("Authorization", cfg.getApiKey())
                    .post(body)
                    .build();

            try (Response response = http.newCall(request).execute()) {
                if (!response.isSuccessful()) {
                    String err = response.body() != null ? response.body().string() : "(empty)";
                    throw new Exceptions.SttFailedError(
                            "AssemblyAI 변환 요청 실패 " + response.code() + ": " + err);
                }
                String id = mapper.readTree(response.body().string()).path("id").asText();
                log.info("[AssemblyAI] 작업 제출 완료 — id={}", id);
                return id;
            }
        } catch (IOException ex) {
            throw new Exceptions.SttFailedError("AssemblyAI 변환 요청 중 IO 오류", ex);
        }
    }

    // ── 3단계: 폴링 → 완료 대기 ──────────────────────────────────────────

    private List<RawSegment> pollAndParse(String transcriptId) {
        String url = BASE_URL + "/transcript/" + transcriptId;
        Request request = new Request.Builder()
                .url(url)
                .header("Authorization", props.getAssemblyai().getApiKey())
                .get()
                .build();

        for (int attempt = 1; attempt <= MAX_POLL_ATTEMPTS; attempt++) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);

                try (Response response = http.newCall(request).execute()) {
                    if (!response.isSuccessful()) {
                        throw new Exceptions.SttFailedError(
                                "AssemblyAI 결과 조회 실패 " + response.code());
                    }
                    JsonNode node = mapper.readTree(response.body().string());
                    String status = node.path("status").asText();
                    log.debug("[AssemblyAI] 폴링 중 — attempt={}/{}, status={}", attempt, MAX_POLL_ATTEMPTS, status);

                    switch (status) {
                        case "completed" -> { return parseResult(node); }
                        case "error" -> throw new Exceptions.SttFailedError(
                                "AssemblyAI 변환 오류: " + node.path("error").asText());
                        // "queued", "processing" → 계속 대기
                    }
                }
            } catch (IOException ex) {
                throw new Exceptions.SttFailedError("AssemblyAI 폴링 중 IO 오류", ex);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new Exceptions.SttFailedError("AssemblyAI 폴링 인터럽트");
            }
        }
        throw new Exceptions.SttFailedError("AssemblyAI 변환 타임아웃 (최대 대기 시간 초과)");
    }

    // ── 결과 파싱 ─────────────────────────────────────────────────────────

    private List<RawSegment> parseResult(JsonNode root) {
        List<RawSegment> result = new ArrayList<>();
        JsonNode utterances = root.path("utterances");

        if (!utterances.isMissingNode() && !utterances.isEmpty()) {
            // 화자 분리 결과: utterances 배열 파싱
            for (JsonNode u : utterances) {
                String text = u.path("text").asText("").strip();
                if (text.isEmpty()) continue;

                String speakerId = "SPEAKER_" + u.path("speaker").asText("A");
                double startSec = u.path("start").asDouble(0) / 1000.0; // ms → 초
                double endSec   = u.path("end").asDouble(0)   / 1000.0;
                double confidence = u.path("confidence").asDouble(1.0);

                result.add(RawSegment.builder()
                        .speakerId(speakerId)
                        .startSec(startSec)
                        .endSec(endSec)
                        .text(text)
                        .confidence(confidence)
                        .lowConfidence(confidence < props.getStt().getConfidenceThreshold())
                        .build());
            }
            log.info("[AssemblyAI] 파싱 완료 — {}개 발화, 화자 {}명",
                    result.size(),
                    result.stream().map(RawSegment::getSpeakerId).distinct().count());
        } else {
            // 단일 화자 폴백: 전체 텍스트 반환
            String text = root.path("text").asText("").strip();
            if (!text.isEmpty()) {
                result.add(RawSegment.builder()
                        .speakerId("SPEAKER_00")
                        .startSec(0.0).endSec(0.0)
                        .text(text).confidence(1.0).lowConfidence(false).build());
            }
            log.info("[AssemblyAI] 단일 화자 파싱 완료 — 전체 텍스트 반환");
        }
        return result;
    }
}
