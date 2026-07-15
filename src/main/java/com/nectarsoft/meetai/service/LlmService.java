package com.nectarsoft.meetai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nectarsoft.meetai.config.MeetAiProperties;
import com.nectarsoft.meetai.dto.TranscribeResponse;
import com.nectarsoft.meetai.model.Meeting;
import com.nectarsoft.meetai.model.MeetingSummary;
import com.nectarsoft.meetai.model.SttProcessingStatus;
import com.nectarsoft.meetai.model.Transcript;
import com.nectarsoft.meetai.repository.MeetingRepository;
import com.nectarsoft.meetai.repository.MeetingSummaryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestTemplate;
import com.nectarsoft.meetai.core.retry.RetryHelper;

import org.springframework.dao.DataIntegrityViolationException;

import java.time.OffsetDateTime;
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
    private final MeetingRepository meetingRepo;
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

            TranscribeResponse.SummaryDto dto = toSummaryDto(response.getBody());
            if (dto != null) {
                saveToDb(meetingId, dto, existing);
                log.info("[LLM] 요약 완료 및 DB 저장 — meetingId={}", meetingId);
            }
            return dto;

        } catch (Exception e) {
            log.error("[LLM] 요약 중 오류 — meetingId={}: {}", meetingId, e.getMessage());
            return null;
        }
    }

    private void saveToDb(UUID meetingId, TranscribeResponse.SummaryDto dto,
                          Optional<MeetingSummary> existing) {
        try {
            Meeting meeting = meetingRepo.findById(meetingId).orElse(null);
            if (meeting == null) return;

            Optional<MeetingSummary> latest = meetingSummaryRepo.findByMeetingMeetingId(meetingId);
            MeetingSummary entity = latest.orElseGet(() ->
                    existing.orElseGet(() -> MeetingSummary.builder().meeting(meeting).build()));

            applyFields(entity, dto);
            meetingSummaryRepo.save(entity);
        } catch (DataIntegrityViolationException e) {
            // Python LLM 서버 콜백(POST /summary)이 동시에 INSERT한 경우 — 재조회 후 UPDATE
            log.warn("[LLM] 중복 INSERT 감지, UPDATE 재시도 — meetingId={}", meetingId);
            try {
                MeetingSummary entity = meetingSummaryRepo.findByMeetingMeetingId(meetingId)
                        .orElseThrow(() -> new IllegalStateException("재조회 실패"));
                applyFields(entity, dto);
                meetingSummaryRepo.save(entity);
            } catch (Exception e2) {
                log.error("[LLM] UPDATE 재시도 실패 — meetingId={}: {}", meetingId, e2.getMessage());
            }
        } catch (Exception e) {
            log.error("[LLM] DB 저장 실패 — meetingId={}: {}", meetingId, e.getMessage());
        }
    }

    private void applyFields(MeetingSummary entity, TranscribeResponse.SummaryDto dto) {
        entity.setLlmModel("gpt-4o");
        entity.setProcessingStatus(SttProcessingStatus.COMPLETED);
        entity.setKeyPoints(dto.getKeyPoints());
        entity.setDecisions(dto.getDecisions());
        entity.setActionItems(dto.getActionItems());
        entity.setKeywords(dto.getKeywords());
        entity.setProcessedAt(OffsetDateTime.now());
    }

    private TranscribeResponse.SummaryDto fromEntity(MeetingSummary entity) {
        return TranscribeResponse.SummaryDto.builder()
                .keyPoints(entity.getKeyPoints())
                .decisions(entity.getDecisions())
                .actionItems(entity.getActionItems())
                .keywords(entity.getKeywords())
                .processingStatus(entity.getProcessingStatus().name())
                .processedAt(entity.getProcessedAt())
                .build();
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
