package com.nectarsoft.meetai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nectarsoft.meetai.config.MeetAiProperties;
import com.nectarsoft.meetai.dto.TranscribeResponse;
import com.nectarsoft.meetai.model.Transcript;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LlmService {

    private final RestTemplate restTemplate;
    private final MeetAiProperties props;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Async
    public void summarizeAsync(UUID meetingId, List<Transcript> transcripts) {
        try {
            summarize(meetingId, transcripts);
        } catch (Exception e) {
            log.error("[LLM] 비동기 요약 실패 — meetingId={}: {}", meetingId, e.getMessage());
        }
    }

    public TranscribeResponse.SummaryDto summarize(UUID meetingId, List<Transcript> transcripts) {
        log.info("[LLM] 요약 시작 — meetingId={}, segments={}", meetingId, transcripts.size());
        try {
            List<Map<String, Object>> transcriptList = transcripts.stream()
                    .map(t -> Map.<String, Object>of(
                            "speakerLabel", t.getSpeakerLabel(),
                            "speakerDisplay", t.getSpeakerDisplay(),
                            "startSec", t.getStartSec(),
                            "endSec", t.getEndSec(),
                            "content", t.getContent(),
                            "confidence", 1.0,
                            "lowConfidence", false
                    ))
                    .collect(Collectors.toList());

            Map<String, Object> payload = Map.of(
                    "meetingId", meetingId.toString(),
                    "engineUsed", "assemblyai",
                    "segmentCount", transcripts.size(),
                    "transcripts", transcriptList
            );

            String url = props.getLlm().getUrl() + "/api/summary";
            ResponseEntity<Map> response = restTemplate.postForEntity(url, payload, Map.class);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.warn("[LLM] 요약 실패 — status={}", response.getStatusCode());
                return null;
            }

            // Python이 DB 저장 처리 — Java는 결과 반환만
            Map<String, Object> body = response.getBody();
            log.info("[LLM] 요약 완료 — meetingId={}", meetingId);
            return toSummaryDto(body);

        } catch (Exception e) {
            log.error("[LLM] 요약 중 오류 — meetingId={}: {}", meetingId, e.getMessage());
            return null;
        }
    }

    private TranscribeResponse.SummaryDto toSummaryDto(Map<String, Object> body) {
        try {
            return TranscribeResponse.SummaryDto.builder()
                    .keyPoints(objectMapper.writeValueAsString(body.get("summary")))
                    .decisions(objectMapper.writeValueAsString(body.get("decisions")))
                    .actionItems(objectMapper.writeValueAsString(body.get("action_items")))
                    .keywords(objectMapper.writeValueAsString(body.get("keywords")))
                    .processingStatus("COMPLETED")
                    .processedAt(null)
                    .build();
        } catch (Exception e) {
            log.warn("[LLM] SummaryDto 변환 실패: {}", e.getMessage());
            return null;
        }
    }
}
