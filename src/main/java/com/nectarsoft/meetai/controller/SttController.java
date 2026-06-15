package com.nectarsoft.meetai.controller;

import com.nectarsoft.meetai.dto.TranscribeResponse;
import com.nectarsoft.meetai.model.Meeting;
import com.nectarsoft.meetai.model.MeetingStatus;
import com.nectarsoft.meetai.model.TranscriptSegment;
import com.nectarsoft.meetai.repository.MeetingRepository;
import com.nectarsoft.meetai.repository.TranscriptSegmentRepository;
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
    private final TranscriptSegmentRepository segmentRepo;

    @Operation(summary = "파일 업로드 → STT 변환",
               description = "오디오 파일을 업로드하면 Whisper API로 변환한 텍스트를 즉시 반환하고 DB에 저장합니다.")
    @PostMapping(value = "/transcribe", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public TranscribeResponse transcribe(@RequestParam("file") MultipartFile file) throws IOException {
        Path saved = audioService.saveUpload(file);
        AudioContext ctx = audioService.preprocess(saved);

        List<RawSegment> segments = sttService.process(ctx.getWorkingPath());

        // DB 저장
        String meetingId = UUID.randomUUID().toString();
        Meeting meeting = Meeting.builder()
                .id(meetingId)
                .filename(file.getOriginalFilename())
                .status(MeetingStatus.COMPLETED)
                .engineUsed(sttService.engineName())
                .build();
        meetingRepo.save(meeting);

        List<TranscriptSegment> entities = new ArrayList<>();
        for (int i = 0; i < segments.size(); i++) {
            RawSegment r = segments.get(i);
            entities.add(TranscriptSegment.builder()
                    .meeting(meeting)
                    .speakerId(r.getSpeakerId())
                    .startSec(r.getStartSec())
                    .endSec(r.getEndSec())
                    .text(r.getText())
                    .confidence(r.getConfidence())
                    .lowConfidence(r.isLowConfidence())
                    .segmentIndex(i)
                    .build());
        }
        segmentRepo.saveAll(entities);

        log.info("[STT] 완료 — meetingId={}, segments={}", meetingId, segments.size());
        return TranscribeResponse.from(segments, sttService.engineName(), meetingId);
    }
}
