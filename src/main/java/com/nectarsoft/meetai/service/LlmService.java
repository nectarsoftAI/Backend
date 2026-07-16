package com.nectarsoft.meetai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nectarsoft.meetai.config.MeetAiProperties;
import com.nectarsoft.meetai.core.util.Keywords;
import com.nectarsoft.meetai.dto.TranscribeResponse;
import com.nectarsoft.meetai.model.MeetingSummary;
import com.nectarsoft.meetai.model.SttProcessingStatus;
import com.nectarsoft.meetai.model.Transcript;
import com.nectarsoft.meetai.repository.MeetingSummaryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import com.nectarsoft.meetai.core.retry.RetryHelper;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class LlmService {

    private final RestTemplate restTemplate;
    private final MeetAiProperties props;
    private final MeetingSummaryRepository meetingSummaryRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // meetingId별 in-flight 가드 — 같은 회의에 대한 동시 요약 요청을 1회 LLM 호출로 병합.
    // (업로드: SttController 비동기 + 프론트 /summarize, 온라인: endMeeting 비동기 + 방장 화면
    //  + 상세 화면이 동시에 호출 → 중복 LLM 호출로 TPM 소진 및 duplicate key의 원인이었음)
    private final ConcurrentHashMap<UUID, Object> summarizeLocks = new ConcurrentHashMap<>();

    @Async
    public void summarizeAsync(UUID meetingId, List<Transcript> transcripts) {
        try {
            summarize(meetingId, transcripts);
        } catch (Exception e) {
            log.error("[LLM] 비동기 요약 실패 — meetingId={}: {}", meetingId, e.getMessage());
        }
    }

    public TranscribeResponse.SummaryDto summarize(UUID meetingId, List<Transcript> transcripts) {
        return runSummarize(meetingId, transcripts, false);
    }

    /**
     * 강제 갱신 요약 — DB 캐시를 무시하고 항상 새로 요약해 덮어쓴다.
     * 회의 중 3분 롤링 선(先)요약 / 종료 시 최종 완전본 생성에 사용 (내용이 계속 늘어나므로 재요약 필요).
     */
    @Async
    public void refreshSummaryAsync(UUID meetingId, List<Transcript> transcripts) {
        try {
            runSummarize(meetingId, transcripts, true);
        } catch (Exception e) {
            log.error("[LLM] 롤링 선요약 실패 — meetingId={}: {}", meetingId, e.getMessage());
        }
    }

    private TranscribeResponse.SummaryDto runSummarize(UUID meetingId, List<Transcript> transcripts, boolean force) {
        Object lock = summarizeLocks.computeIfAbsent(meetingId, k -> new Object());
        synchronized (lock) {
            try {
                // 먼저 진입한 호출이 LLM을 실행하는 동안 대기했다가,
                // 완료되면 아래 DB 캐시 체크에서 결과를 그대로 반환한다 (force면 캐시 무시하고 재요약)
                return doSummarize(meetingId, transcripts, force);
            } finally {
                summarizeLocks.remove(meetingId, lock);
            }
        }
    }

    private TranscribeResponse.SummaryDto doSummarize(UUID meetingId, List<Transcript> transcripts, boolean force) {
        // DB에 이미 완료된 요약이 있으면 LLM 호출 없이 반환 (force=true 롤링/최종 갱신은 건너뜀)
        Optional<MeetingSummary> existing = meetingSummaryRepo.findByMeetingMeetingId(meetingId);
        if (!force && existing.isPresent()
                && existing.get().getProcessingStatus() == SttProcessingStatus.COMPLETED) {
            log.info("[LLM] DB 캐시 반환 — meetingId={}", meetingId);
            return fromEntity(existing.get());
        }

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

            ResponseEntity<Map> response = RetryHelper.retry(() -> {
                try {
                    return restTemplate.postForEntity(url, payload, Map.class);
                } catch (HttpClientErrorException.TooManyRequests e) {
                    log.warn("[LLM] 429 Rate limit — 재시도 대기: {}", e.getMessage());
                    throw e;
                }
            }, 3, 5000L, 20000L);

            if (!response.getStatusCode().is2xxSuccessful() || response.getBody() == null) {
                log.warn("[LLM] 요약 실패 — status={}", response.getStatusCode());
                return null;
            }

            // 저장은 Python(/api/summary → Supabase upsert + DB function)이 전담한다.
            // Java가 같은 컬럼(key_points 등)을 문자열로 다시 쓰면 jsonb와 형식이 충돌해
            // 이중 인코딩(봉투 겹침)이 발생하므로 Java는 요약 컬럼을 저장하지 않는다.
            TranscribeResponse.SummaryDto dto = toSummaryDto(response.getBody());
            log.info("[LLM] 요약 완료 — meetingId={} (DB 저장은 Python 전담)", meetingId);
            return dto;

        } catch (Exception e) {
            log.error("[LLM] 요약 중 오류 — meetingId={}: {}", meetingId, e.getMessage());
            return null;
        }
    }

    private TranscribeResponse.SummaryDto fromEntity(MeetingSummary entity) {
        return TranscribeResponse.SummaryDto.builder()
                .keyPoints(entity.getKeyPoints())
                .decisions(entity.getDecisions())
                .actionItems(entity.getActionItems())
                // 오염된 값도 parse→toJson 왕복으로 깨끗한 배열 문자열로 정규화
                .keywords(Keywords.toJson(Keywords.parse(entity.getKeywords())))
                .processingStatus(entity.getProcessingStatus().name())
                .processedAt(entity.getProcessedAt())
                .build();
    }

    private TranscribeResponse.SummaryDto toSummaryDto(Map<String, Object> body) {
        try {
            return TranscribeResponse.SummaryDto.builder()
                    .keyPoints(toJsonArray(body.get("summary")))
                    .decisions(toJsonArray(body.get("decisions")))
                    .actionItems(toJsonArray(body.get("action_items")))
                    // LLM 응답이 배열이든 문자열이든 항상 JSON 배열 문자열로 통일
                    .keywords(Keywords.toJson(Keywords.from(body.get("keywords"))))
                    .processingStatus("COMPLETED")
                    .processedAt(null)
                    .build();
        } catch (Exception e) {
            log.warn("[LLM] SummaryDto 변환 실패: {}", e.getMessage());
            return null;
        }
    }

    /**
     * LLM 응답 필드를 항상 JSON 배열 문자열로 변환.
     * 키 누락(null)이 문자열 "null"로 저장되거나, 문자열/객체가 그대로 들어가
     * 프론트 JSON.parse(...).map()이 깨지는 것을 방지한다.
     */
    private String toJsonArray(Object value) {
        try {
            if (value == null) return "[]";
            Object normalized = (value instanceof List) ? value : List.of(value);
            return objectMapper.writeValueAsString(normalized);
        } catch (Exception e) {
            return "[]";
        }
    }
}
