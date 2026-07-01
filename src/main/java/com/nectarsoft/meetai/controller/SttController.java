package com.nectarsoft.meetai.controller;

import com.nectarsoft.meetai.dto.TranscribeResponse;
import com.nectarsoft.meetai.model.*;
import com.nectarsoft.meetai.repository.AudioFileRepository;
import com.nectarsoft.meetai.repository.MeetingRepository;
import com.nectarsoft.meetai.repository.SttResultRepository;
import com.nectarsoft.meetai.repository.TranscriptRepository;
import com.nectarsoft.meetai.service.LlmService;
import com.nectarsoft.meetai.service.audio.AudioContext;
import com.nectarsoft.meetai.service.audio.AudioService;
import com.nectarsoft.meetai.service.stt.RawSegment;
import com.nectarsoft.meetai.service.stt.SttService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Slf4j
@Tag(name = "STT", description = "음성→텍스트 변환")
@RestController
@RequestMapping("/api/v1/stt")
@RequiredArgsConstructor
public class SttController {

    private final AudioService audioService;
    private final SttService sttService;
    private final MeetingRepository meetingRepo;
    private final AudioFileRepository audioFileRepo;
    private final SttResultRepository sttResultRepo;
    private final TranscriptRepository transcriptRepo;
    private final LlmService llmService;

    @Operation(summary = "파일 업로드 → STT + LLM 요약",
               description = "오디오 파일을 업로드하면 STT 변환 및 LLM 요약을 수행하고 둘 다 반환합니다.")
    @PostMapping(value = "/transcribe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public TranscribeResponse transcribe(
            @RequestParam("file") MultipartFile file,
            @RequestParam(value = "title", required = false) String title,
            @RequestHeader("X-User-Id") UUID profileId) throws IOException {
        Path saved = audioService.saveUpload(file);
        AudioContext ctx = audioService.preprocess(saved);

        // meeting 생성
        Meeting meeting = Meeting.builder()
                .userId(profileId)
                .title(title != null && !title.isBlank() ? title : file.getOriginalFilename())
                .meetingType(MeetingType.UPLOAD)
                .status(MeetingStatus.PROCESSING)
                .meetingDate(OffsetDateTime.now())
                .build();
        meetingRepo.save(meeting);

        // audio_files 저장
        AudioFile audioFile = AudioFile.builder()
                .meeting(meeting)
                .storageType(StorageType.LOCAL)
                .storagePath(saved.toString())
                .originalFilename(file.getOriginalFilename())
                .fileSizeBytes(file.getSize())
                .mimeType(file.getContentType())
                .build();
        audioFileRepo.save(audioFile);

        // STT 처리
        List<RawSegment> segments = sttService.process(ctx.getWorkingPath());

        // stt_results 저장
        SttResult sttResult = SttResult.builder()
                .meeting(meeting)
                .processingStatus(SttProcessingStatus.COMPLETED)
                .processedAt(OffsetDateTime.now())
                .build();
        sttResultRepo.save(sttResult);

        // transcripts 저장
        List<Transcript> transcripts = new ArrayList<>();
        for (RawSegment r : segments) {
            transcripts.add(Transcript.builder()
                    .meeting(meeting)
                    .sttResult(sttResult)
                    .speakerLabel(r.getSpeakerId())
                    .speakerDisplay(r.getSpeakerId())
                    .startSec(r.getStartSec())
                    .endSec(r.getEndSec())
                    .content(r.getText())
                    .build());
        }
        transcriptRepo.saveAll(transcripts);

        // meeting 완료 처리
        meeting.setStatus(MeetingStatus.COMPLETED);
        meetingRepo.save(meeting);

        log.info("[STT] 완료 — meetingId={}, segments={}", meeting.getMeetingId(), segments.size());

        // LLM 요약 (동기 — STT + 요약 한 번에 반환)
        TranscribeResponse.SummaryDto summary = null;
        if (!transcripts.isEmpty()) {
            summary = llmService.summarize(meeting.getMeetingId(), transcripts);
        }

        return TranscribeResponse.from(segments, sttService.engineName(), meeting.getMeetingId(), summary);
    }
}
