package com.nectarsoft.meetai.service.stt;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nectarsoft.meetai.config.MeetAiProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class TranscriptPolishService {

    private final MeetAiProperties props;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;

    private static final String OPENAI_URL = "https://api.openai.com/v1/chat/completions";
    private static final int BATCH_SIZE = 20;

    private static final String SYSTEM_PROMPT =
            "당신은 한국어 STT(음성 인식) 텍스트 교정 도구입니다.\n\n" +
            "【절대 금지】\n" +
            "- 원본에 없는 단어, 문장, 이름, 기관명을 절대 추가하지 마세요.\n" +
            "- 불분명하거나 짧은 텍스트를 임의로 확장하지 마세요.\n" +
            "- 내용이나 의미를 바꾸지 마세요.\n" +
            "- 말투(존댓말/반말)를 바꾸지 마세요.\n\n" +
            "【허용 교정】\n" +
            "1. 명백한 맞춤법 오류만 수정 (됬어 → 됐어, 어떻해 → 어떡해)\n" +
            "2. 음성 인식이 명백히 틀린 단어 수정 (문맥상 확실한 경우만)\n" +
            "3. 어색한 조사·어미 교정\n" +
            "4. 문장 끝 구두점 추가\n\n" +
            "【불분명한 경우】\n" +
            "- 수정이 확실하지 않으면 원본 텍스트를 그대로 유지하세요.\n\n" +
            "반드시 아래 JSON 형식으로만 응답하세요:\n" +
            "{\"segments\":[{\"id\":0,\"text\":\"교정된 텍스트\"},{\"id\":1,\"text\":\"교정된 텍스트\"}]}";

    public List<RawSegment> polish(List<RawSegment> segments) {
        if (!props.getStt().isPolishEnabled() || segments.isEmpty()) return segments;

        List<RawSegment> result = new ArrayList<>(segments);
        for (int i = 0; i < segments.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, segments.size());
            List<RawSegment> batch = segments.subList(i, end);
            List<RawSegment> polished = polishBatch(batch, i);
            for (int j = 0; j < polished.size(); j++) {
                result.set(i + j, polished.get(j));
            }
        }
        return result;
    }

    private List<RawSegment> polishBatch(List<RawSegment> batch, int offset) {
        try {
            List<Map<String, Object>> input = new ArrayList<>();
            for (int i = 0; i < batch.size(); i++) {
                input.add(Map.of("id", offset + i, "text", batch.get(i).getText()));
            }

            Map<String, Object> requestBody = Map.of(
                    "model", props.getStt().getPolishModel(),
                    "messages", List.of(
                            Map.of("role", "system", "content", SYSTEM_PROMPT),
                            Map.of("role", "user", "content", objectMapper.writeValueAsString(input))
                    ),
                    "temperature", 0,
                    "response_format", Map.of("type", "json_object")
            );

            HttpHeaders headers = new HttpHeaders();
            headers.setContentType(MediaType.APPLICATION_JSON);
            headers.setBearerAuth(props.getOpenai().getApiKey());

            ResponseEntity<Map> response = restTemplate.postForEntity(
                    OPENAI_URL, new HttpEntity<>(requestBody, headers), Map.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.warn("[Polish] OpenAI 응답 실패 — status={}", response.getStatusCode());
                return batch;
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> choices = (List<Map<String, Object>>) response.getBody().get("choices");
            if (choices == null || choices.isEmpty()) return batch;

            @SuppressWarnings("unchecked")
            String content = (String) ((Map<String, Object>) choices.get(0).get("message")).get("content");

            Map<String, Object> parsed = objectMapper.readValue(content, new TypeReference<>() {});

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> polishedList = (List<Map<String, Object>>) parsed.get("segments");
            if (polishedList == null || polishedList.size() != batch.size()) {
                log.warn("[Polish] 세그먼트 수 불일치 — expected={}, got={}",
                        batch.size(), polishedList == null ? "null" : polishedList.size());
                return batch;
            }

            Map<Integer, String> textMap = new HashMap<>();
            for (Map<String, Object> s : polishedList) {
                textMap.put(((Number) s.get("id")).intValue(), (String) s.get("text"));
            }

            // 개수가 맞아도 내용이 비어 오는 경우가 있다 — LLM이 두 세그먼트를 한쪽에 합쳐
            // 넣고 남은 자리를 빈 문자열로 채우면 개수 검증을 통과한다. 이러면 자막이
            // 통째로 빈 세그먼트가 되므로, 하나라도 비어 있으면 배치 전체를 원본으로 되돌린다
            // (한 칸만 원본으로 채우면 합쳐진 쪽과 내용이 중복된다)
            long blanks = textMap.values().stream()
                    .filter(t -> t == null || t.isBlank()).count();
            if (blanks > 0) {
                log.warn("[Polish] 빈 텍스트 {}건 — 교정 결과 폐기하고 원본 유지 (offset={})", blanks, offset);
                return batch;
            }

            List<RawSegment> result = new ArrayList<>();
            for (int i = 0; i < batch.size(); i++) {
                RawSegment orig = batch.get(i);
                String polishedText = textMap.getOrDefault(offset + i, orig.getText());
                result.add(RawSegment.builder()
                        .speakerId(orig.getSpeakerId())
                        .startSec(orig.getStartSec())
                        .endSec(orig.getEndSec())
                        .text(polishedText)
                        .confidence(orig.getConfidence())
                        .lowConfidence(orig.isLowConfidence())
                        .build());
            }

            log.info("[Polish] 완료 — offset={}, count={}", offset, batch.size());
            return result;

        } catch (Exception e) {
            log.warn("[Polish] 실패, 원본 반환 — offset={}: {}", offset, e.getMessage());
            return batch;
        }
    }
}
