package com.nectarsoft.meetai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nectarsoft.meetai.core.websocket.OnlineRoomManager;
import com.nectarsoft.meetai.model.*;
import com.nectarsoft.meetai.repository.MeetingRepository;
import com.nectarsoft.meetai.repository.SttResultRepository;
import com.nectarsoft.meetai.repository.TranscriptRepository;
import com.nectarsoft.meetai.service.stt.RawSegment;
import com.nectarsoft.meetai.service.stt.SttService;
import com.nectarsoft.meetai.storage.FileStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Component
@RequiredArgsConstructor
public class OnlineBufferProcessor {

    private final SttService sttService;
    private final OnlineRoomManager roomManager;
    private final FileStorage fileStorage;
    private final MeetingRepository meetingRepo;
    private final SttResultRepository sttResultRepo;
    private final TranscriptRepository transcriptRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Async
    public void process(String meetingId, String profileId, String speakerDisplay, SessionBuffer buffer) {
        try {
            byte[] audioData = buffer.drainAndBuild();
            log.info("[OnlineSTT] 처리 — meetingId={}, profileId={}, bytes={}", meetingId, profileId, audioData.length);

            String filename = "online_" + meetingId + "_" + profileId + "_" + System.nanoTime() + ".webm";
            Path tmpFile = fileStorage.saveTempBytes(filename, audioData);
            List<RawSegment> segments = sttService.process(tmpFile);
            fileStorage.delete(tmpFile);

            if (segments.isEmpty()) return;

            Meeting meeting = meetingRepo.findById(UUID.fromString(meetingId)).orElse(null);
            if (meeting == null) return;

            SttResult sttResult = SttResult.builder()
                    .meeting(meeting)
                    .processingStatus(SttProcessingStatus.COMPLETED)
                    .processedAt(OffsetDateTime.now())
                    .build();
            sttResultRepo.save(sttResult);

            List<Transcript> transcripts = new ArrayList<>();
            for (RawSegment seg : segments) {
                transcripts.add(Transcript.builder()
                        .meeting(meeting)
                        .sttResult(sttResult)
                        .speakerLabel(profileId)
                        .speakerDisplay(speakerDisplay)
                        .startSec(seg.getStartSec())
                        .endSec(seg.getEndSec())
                        .content(seg.getText())
                        .build());

                roomManager.broadcast(meetingId, objectMapper.writeValueAsString(Map.of(
                        "type", "transcript",
                        "profileId", profileId,
                        "speakerDisplay", speakerDisplay,
                        "text", seg.getText(),
                        "startSec", seg.getStartSec(),
                        "endSec", seg.getEndSec()
                )));
            }
            transcriptRepo.saveAll(transcripts);
            log.info("[OnlineSTT] 완료 — {} 구간 브로드캐스트", segments.size());

        } catch (Exception e) {
            log.error("[OnlineSTT] 처리 실패 — meetingId={}, profileId={}: {}", meetingId, profileId, e.getMessage());
        }
    }
}
