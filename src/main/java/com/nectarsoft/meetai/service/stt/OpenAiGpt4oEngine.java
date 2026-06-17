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
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

@Slf4j
public class OpenAiGpt4oEngine implements SttEngine {

    private static final String CHAT_URL = "https://api.openai.com/v1/chat/completions";

    private final MeetAiProperties props;
    private final OkHttpClient http;
    private final ObjectMapper mapper = new ObjectMapper();

    public OpenAiGpt4oEngine(MeetAiProperties props) {
        this.props = props;
        this.http = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(120, TimeUnit.SECONDS)
                .build();
    }

    @Override
    public List<RawSegment> transcribe(Path audioPath) {
        log.info("[GPT-4o] 변환 시작 — {}", audioPath.getFileName());

        String base64Audio;
        try {
            base64Audio = Base64.getEncoder().encodeToString(Files.readAllBytes(audioPath));
        } catch (IOException e) {
            throw new Exceptions.SttFailedError("오디오 파일 읽기 실패", e);
        }

        String format = extension(audioPath);
        String body = String.format("""
                {
                  "model": "gpt-4o",
                  "messages": [
                    {
                      "role": "user",
                      "content": [
                        {
                          "type": "input_audio",
                          "input_audio": { "data": "%s", "format": "%s" }
                        },
                        {
                          "type": "text",
                          "text": "이 오디오를 한국어로 정확히 전사해주세요. 말이 없는 구간은 생략하고, 말한 내용만 그대로 적어주세요."
                        }
                      ]
                    }
                  ]
                }
                """, base64Audio, format);

        Request request = new Request.Builder()
                .url(CHAT_URL)
                .header("Authorization", "Bearer " + props.getOpenai().getApiKey())
                .header("Content-Type", "application/json")
                .post(RequestBody.create(body, MediaType.parse("application/json")))
                .build();

        try (Response response = http.newCall(request).execute()) {
            if (!response.isSuccessful()) {
                String err = response.body() != null ? response.body().string() : "(empty)";
                throw new Exceptions.SttFailedError("GPT-4o API 오류 " + response.code() + ": " + err);
            }

            JsonNode root = mapper.readTree(response.body().string());
            String text = root.path("choices").path(0).path("message").path("content").asText("").strip();

            log.info("[GPT-4o] 변환 완료 — 텍스트 길이={}", text.length());

            if (text.isEmpty()) return List.of();

            return List.of(RawSegment.builder()
                    .speakerId("SPEAKER_00")
                    .startSec(0.0)
                    .endSec(0.0)
                    .text(text)
                    .confidence(1.0)
                    .lowConfidence(false)
                    .build());

        } catch (IOException ex) {
            throw new Exceptions.SttFailedError("GPT-4o API 연결 실패", ex);
        }
    }

    private String extension(Path path) {
        String name = path.getFileName().toString();
        int dot = name.lastIndexOf('.');
        return dot >= 0 ? name.substring(dot + 1).toLowerCase() : "wav";
    }
}
