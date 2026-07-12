package com.nectarsoft.meetai.controller;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nectarsoft.meetai.core.exception.Exceptions;
import com.nectarsoft.meetai.dto.MeetingDetailResponse;
import com.nectarsoft.meetai.dto.SaveSummaryRequest;
import com.nectarsoft.meetai.dto.TranscribeResponse;
import com.nectarsoft.meetai.dto.UpdateTranscriptRequest;
import com.nectarsoft.meetai.model.Meeting;
import com.nectarsoft.meetai.service.LlmService;
import com.nectarsoft.meetai.model.MeetingStatus;
import com.nectarsoft.meetai.model.MeetingSummary;
import com.nectarsoft.meetai.model.SttProcessingStatus;
import com.nectarsoft.meetai.model.Transcript;
import com.nectarsoft.meetai.repository.AudioFileRepository;
import com.nectarsoft.meetai.repository.MeetingRepository;
import com.nectarsoft.meetai.repository.MeetingSummaryRepository;
import com.nectarsoft.meetai.repository.SttResultRepository;
import com.nectarsoft.meetai.repository.TranscriptRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Tag(name = "Meetings", description = "회의 결과 조회/삭제")
@RestController
@RequestMapping("/api/v1/meetings")
@RequiredArgsConstructor
public class MeetingController {

    private final MeetingRepository meetingRepo;
    private final TranscriptRepository transcriptRepo;
    private final SttResultRepository sttResultRepo;
    private final AudioFileRepository audioFileRepo;
    private final MeetingSummaryRepository meetingSummaryRepo;
    private final LlmService llmService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Operation(summary = "회의 목록 조회 (페이징)")
    @GetMapping
    public Map<String, Object> listMeetings(
            @RequestHeader("X-User-Id") UUID profileId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "6") int size) {

        Page<Meeting> meetingPage = meetingRepo.findByUserId(
                profileId, PageRequest.of(page, size, Sort.by("createdAt").descending()));

        List<UUID> ids = meetingPage.getContent().stream().map(Meeting::getMeetingId).toList();

        Map<UUID, List<Transcript>> txByMeeting = ids.isEmpty() ? Map.of() :
                transcriptRepo.findByMeetingMeetingIdIn(ids).stream()
                        .collect(Collectors.groupingBy(t -> t.getMeeting().getMeetingId()));

        Map<UUID, MeetingSummary> sumByMeeting = ids.isEmpty() ? Map.of() :
                meetingSummaryRepo.findByMeetingMeetingIdIn(ids).stream()
                        .collect(Collectors.toMap(s -> s.getMeeting().getMeetingId(), s -> s));

        List<Map<String, Object>> meetings = meetingPage.getContent().stream().map(m -> {
            List<Transcript> txList = txByMeeting.getOrDefault(m.getMeetingId(), List.of());

            List<Map<String, String>> participants = txList.stream()
                    .collect(Collectors.toMap(Transcript::getSpeakerLabel, Transcript::getSpeakerDisplay, (a, b) -> a))
                    .entrySet().stream()
                    .map(e -> Map.of("speakerLabel", e.getKey(), "speakerDisplay", e.getValue()))
                    .toList();

            List<String> keywords = List.of();
            MeetingSummary summary = sumByMeeting.get(m.getMeetingId());
            if (summary != null && summary.getKeywords() != null) {
                try { keywords = objectMapper.readValue(summary.getKeywords(), new TypeReference<>() {}); }
                catch (Exception ignored) {}
            }

            Map<String, Object> item = new LinkedHashMap<>();
            item.put("meetingId", m.getMeetingId().toString());
            item.put("title", m.getTitle() != null ? m.getTitle() : "");
            item.put("meetingType", m.getMeetingType().name());
            item.put("status", m.getStatus().name());
            item.put("durationSeconds", m.getDurationSeconds());
            item.put("meetingDate", m.getMeetingDate());
            item.put("createdAt", m.getCreatedAt());
            item.put("participants", participants);
            item.put("keywords", keywords);
            return item;
        }).toList();

        return Map.of(
                "meetings", meetings,
                "totalCount", meetingPage.getTotalElements(),
                "page", page,
                "size", size,
                "totalPages", meetingPage.getTotalPages()
        );
    }

    @Operation(summary = "회의 결과 상세 조회 (대화록 포함)")
    @GetMapping("/{meetingId}")
    public MeetingDetailResponse getMeeting(@PathVariable UUID meetingId) {
        Meeting meeting = meetingRepo.findById(meetingId)
                .orElseThrow(() -> new Exceptions.MeetingNotFoundError(meetingId.toString()));
        List<Transcript> transcripts = transcriptRepo.findByMeetingMeetingIdOrderByStartSecAsc(meetingId);
        MeetingSummary summary = meetingSummaryRepo.findByMeetingMeetingId(meetingId).orElse(null);
        return MeetingDetailResponse.from(meeting, transcripts, summary);
    }

    @Operation(summary = "LLM 요약 동기 생성",
               description = "DB 트랜스크립트로 LLM 요약을 실행하고 결과를 반환합니다. session_ended 후 프론트가 호출합니다. "
                       + "실시간 녹음은 종료 직후 최종 화자 분리가 백그라운드로 진행되므로(status=PROCESSING) 완료될 때까지 대기 후 요약합니다.")
    @PostMapping("/{meetingId}/summarize")
    public MeetingDetailResponse.SummaryDto generateSummary(@PathVariable UUID meetingId) {
        Meeting meeting = meetingRepo.findById(meetingId)
                .orElseThrow(() -> new Exceptions.MeetingNotFoundError(meetingId.toString()));

        // 실시간 녹음(A안): session_ended 직후엔 최종 화자 분리가 백그라운드 진행 중이라
        // transcripts가 아직 비어 있다 — COMPLETED로 바뀔 때까지 기다렸다가 요약한다
        awaitLiveFinalization(meetingId, meeting.getStatus());

        List<Transcript> transcripts = transcriptRepo.findByMeetingMeetingIdOrderByStartSecAsc(meetingId);
        if (transcripts.isEmpty()) {
            throw new Exceptions.NoTranscriptError("음성 인식 결과가 없습니다. 녹음 내용을 확인해 주세요.");
        }

        TranscribeResponse.SummaryDto dto = llmService.summarize(meetingId, transcripts);
        if (dto == null) {
            throw new Exceptions.SttFailedError("LLM 요약 생성에 실패했습니다");
        }

        return MeetingDetailResponse.SummaryDto.builder()
                .keyPoints(dto.getKeyPoints())
                .decisions(dto.getDecisions())
                .actionItems(dto.getActionItems())
                .keywords(dto.getKeywords())
                .processingStatus(dto.getProcessingStatus())
                .processedAt(dto.getProcessedAt())
                .build();
    }

    private static final long FINALIZE_WAIT_TIMEOUT_MS = 180_000L;
    private static final long FINALIZE_POLL_INTERVAL_MS = 2_000L;

    // 백그라운드 최종 변환(LiveService.finalizeTranscripts)이 끝나 status가 PROCESSING을
    // 벗어날 때까지 폴링 대기. findById 재조회는 영속성 컨텍스트에 캐시된 엔티티를 돌려주므로
    // 반드시 스칼라 쿼리(findStatusByMeetingId)로 확인해야 상태 변화가 보인다.
    private void awaitLiveFinalization(UUID meetingId, MeetingStatus initialStatus) {
        if (initialStatus != MeetingStatus.PROCESSING) return;

        log.info("[Summarize] 최종 변환 대기 시작 — meetingId={}", meetingId);
        long deadline = System.currentTimeMillis() + FINALIZE_WAIT_TIMEOUT_MS;
        while (System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(FINALIZE_POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
            MeetingStatus status = meetingRepo.findStatusByMeetingId(meetingId).orElse(null);
            if (status != MeetingStatus.PROCESSING) {
                log.info("[Summarize] 최종 변환 완료 감지 — meetingId={}, status={}", meetingId, status);
                return;
            }
        }
        log.warn("[Summarize] 최종 변환 대기 타임아웃({}s) — meetingId={}",
                FINALIZE_WAIT_TIMEOUT_MS / 1000, meetingId);
    }

    @Operation(summary = "LLM 요약 저장 (Python LLM 서버가 호출)")
    @PostMapping("/{meetingId}/summary")
    @ResponseStatus(HttpStatus.CREATED)
    public void saveSummary(@PathVariable UUID meetingId, @RequestBody SaveSummaryRequest req) {
        Meeting meeting = meetingRepo.findById(meetingId)
                .orElseThrow(() -> new Exceptions.MeetingNotFoundError(meetingId.toString()));

        MeetingSummary summary = meetingSummaryRepo.findByMeetingMeetingId(meetingId)
                .orElseGet(() -> MeetingSummary.builder().meeting(meeting).build());

        summary.setLlmModel("gpt-4o");
        summary.setProcessingStatus(SttProcessingStatus.COMPLETED);
        summary.setKeyPoints(req.getKeyPoints());
        summary.setDecisions(req.getDecisions());
        summary.setActionItems(req.getActionItems());
        summary.setKeywords(req.getKeywords());
        summary.setRawResponse(req.getRawResponse());
        summary.setProcessedAt(OffsetDateTime.now());
        meetingSummaryRepo.save(summary);
    }

    @Operation(summary = "대화 내용 수정", description = "변경된 트랜스크립트 목록을 전달하면 content/speakerDisplay를 업데이트합니다.")
    @PutMapping("/{meetingId}/transcripts")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void updateTranscripts(@PathVariable UUID meetingId,
                                   @RequestBody List<UpdateTranscriptRequest> updates) {
        meetingRepo.findById(meetingId)
                .orElseThrow(() -> new Exceptions.MeetingNotFoundError(meetingId.toString()));

        List<Transcript> transcripts = transcriptRepo.findByMeetingMeetingIdOrderByStartSecAsc(meetingId);
        Map<Long, Transcript> byId = transcripts.stream()
                .collect(Collectors.toMap(Transcript::getTranscriptId, t -> t));

        for (UpdateTranscriptRequest req : updates) {
            Transcript t = byId.get(req.getTranscriptId());
            if (t == null) continue;
            if (req.getContent() != null) t.setContent(req.getContent());
            if (req.getSpeakerDisplay() != null) t.setSpeakerDisplay(req.getSpeakerDisplay());
        }
        transcriptRepo.saveAll(byId.values());
    }

    @Operation(summary = "회의 삭제")
    @DeleteMapping("/{meetingId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Transactional
    public void deleteMeeting(@PathVariable UUID meetingId) {
        if (!meetingRepo.existsById(meetingId)) {
            throw new Exceptions.MeetingNotFoundError(meetingId.toString());
        }
        meetingSummaryRepo.deleteByMeetingMeetingId(meetingId);
        transcriptRepo.deleteByMeetingMeetingId(meetingId);
        sttResultRepo.deleteByMeetingMeetingId(meetingId);
        audioFileRepo.deleteByMeetingMeetingId(meetingId);
        meetingRepo.deleteById(meetingId);
    }
}
