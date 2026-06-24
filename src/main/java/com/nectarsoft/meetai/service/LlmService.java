package com.nectarsoft.meetai.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nectarsoft.meetai.config.MeetAiProperties;
import com.nectarsoft.meetai.model.Meeting;
import com.nectarsoft.meetai.model.MeetingSummary;
import com.nectarsoft.meetai.model.SttProcessingStatus;
import com.nectarsoft.meetai.model.Transcript;
import com.nectarsoft.meetai.repository.MeetingSummaryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.time.OffsetDateTime;
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
    private final MeetingSummaryRepository meetingSummaryRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Async
    public void summarizeAsync(Meeting meeting, List<Transcript> transcripts) {
        try {
            summarize(meeting, transcripts);
        } catch (Exception e) {
            log.error("[LLM] 비동기 요약 실패 — meetingId={}: {}", meeting.getMeetingId(), e.getMessage());
        }
    }

    public MeetingSummary summarize(Meeting meeting, List<Transcript> transcripts) {
        UUID meetingId = meeting.getMeetingId();
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

            Map<String, Object> body = response.getBody();
            MeetingSummary summary = buildSummary(meeting, body);
            meetingSummaryRepo.save(summary);
            log.info("[LLM] 요약 저장 완료 — meetingId={}", meetingId);
            return summary;

        } catch (Exception e) {
            log.error("[LLM] 요약 중 오류 — meetingId={}: {}", meetingId, e.getMessage());
            return null;
        }
    }

    private MeetingSummary buildSummary(Meeting meeting, Map<String, Object> body) throws JsonProcessingException {
        return MeetingSummary.builder()
                .meeting(meeting)
                .llmModel("gpt-4o")
                .processingStatus(SttProcessingStatus.COMPLETED)
                .keyPoints(toJson(body.get("summary")))
                .decisions(toJson(body.get("decisions")))
                .actionItems(toJson(body.get("action_items")))
                .keywords(toJson(body.get("keywords")))
                .rawResponse(objectMapper.writeValueAsString(body))
                .processedAt(OffsetDateTime.now())
                .build();
    }

    private String toJson(Object value) throws JsonProcessingException {
        if (value == null) return null;
        return objectMapper.writeValueAsString(value);
    }
}
