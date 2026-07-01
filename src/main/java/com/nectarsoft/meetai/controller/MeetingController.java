package com.nectarsoft.meetai.controller;

import com.nectarsoft.meetai.core.exception.Exceptions;
import com.nectarsoft.meetai.dto.MeetingDetailResponse;
import com.nectarsoft.meetai.dto.SaveSummaryRequest;
import com.nectarsoft.meetai.dto.TranscribeResponse;
import com.nectarsoft.meetai.dto.UpdateTranscriptRequest;
import com.nectarsoft.meetai.model.Meeting;
import com.nectarsoft.meetai.service.LlmService;
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
import org.springframework.http.HttpStatus;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

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
               description = "DB 트랜스크립트로 LLM 요약을 실행하고 결과를 반환합니다. session_ended 후 프론트가 호출합니다.")
    @PostMapping("/{meetingId}/summarize")
    public MeetingDetailResponse.SummaryDto generateSummary(@PathVariable UUID meetingId) {
        meetingRepo.findById(meetingId)
                .orElseThrow(() -> new Exceptions.MeetingNotFoundError(meetingId.toString()));

        List<Transcript> transcripts = transcriptRepo.findByMeetingMeetingIdOrderByStartSecAsc(meetingId);
        if (transcripts.isEmpty()) {
            throw new Exceptions.SttFailedError("트랜스크립트가 없어 요약을 생성할 수 없습니다");
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
