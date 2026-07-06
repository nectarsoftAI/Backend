package com.nectarsoft.meetai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nectarsoft.meetai.core.websocket.WebSocketManager;
import com.nectarsoft.meetai.model.*;
import com.nectarsoft.meetai.repository.MeetingRepository;
import com.nectarsoft.meetai.repository.SttResultRepository;
import com.nectarsoft.meetai.repository.TranscriptRepository;
import com.nectarsoft.meetai.service.stt.RawSegment;
import com.nectarsoft.meetai.service.stt.SttService;
import com.nectarsoft.meetai.service.stt.TranscriptPolishService;
import com.nectarsoft.meetai.storage.FileStorage;
import com.nectarsoft.meetai.service.LlmService;
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

/**
 * @Async는 같은 빈 내부 호출 시 프록시를 우회하므로 별도 컴포넌트로 분리
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LiveBufferProcessor {

    private final SttService sttService;
    private final TranscriptPolishService polishService;
    private final WebSocketManager wsManager;
    private final FileStorage fileStorage;
    private final MeetingRepository meetingRepo;
    private final SttResultRepository sttResultRepo;
    private final TranscriptRepository transcriptRepo;
    private final LlmService llmService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Async
    public void process(String sessionId, SessionBuffer buffer, boolean isFinal) {
        try {
            byte[] audioData = buffer.drainAndBuild();
            log.info("[Live] STT 처리 — sessionId={}, bytes={}", sessionId, audioData.length);

            String filename = "live_" + sessionId + "_" + System.nanoTime() + ".webm";
            Path tmpFile = fileStorage.saveTempBytes(filename, audioData);

            List<RawSegment> segments = sttService.process(tmpFile);
            fileStorage.delete(tmpFile);

            // DB 저장
            UUID meetingId = UUID.fromString(sessionId);
            Meeting meeting = meetingRepo.findById(meetingId).orElse(null);
            if (meeting != null && !segments.isEmpty()) {
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
                            .speakerLabel(seg.getSpeakerId())
                            .speakerDisplay(seg.getSpeakerId())
                            .startSec(seg.getStartSec())
                            .endSec(seg.getEndSec())
                            .content(seg.getText())
                            .build());
                }
                transcriptRepo.saveAll(transcripts);
            }

            // WebSocket 브로드캐스트
            for (RawSegment seg : segments) {
                String json = objectMapper.writeValueAsString(Map.of(
                        "type", "segment",
                        "speaker_label", seg.getSpeakerId(),
                        "start_sec", seg.getStartSec(),
                        "end_sec", seg.getEndSec(),
                        "text", seg.getText(),
                        "confidence", seg.getConfidence()
                ));
                wsManager.broadcast(sessionId, json);
            }
            log.info("[Live] 브로드캐스트 완료 — {} 구간", segments.size());

            if (isFinal) {
                UUID mid = UUID.fromString(sessionId);
                List<Transcript> all = transcriptRepo
                        .findByMeetingMeetingIdOrderByStartSecAsc(mid);

                if (!all.isEmpty()) {
                    // 전체 세그먼트를 OpenAI로 일괄 후처리 후 DB 업데이트
                    List<RawSegment> raw = all.stream()
                            .map(t -> RawSegment.builder()
                                    .speakerId(t.getSpeakerLabel())
                                    .startSec(t.getStartSec())
                                    .endSec(t.getEndSec())
                                    .text(t.getContent())
                                    .confidence(1.0)
                                    .lowConfidence(false)
                                    .build())
                            .toList();

                    List<RawSegment> polished = polishService.polish(raw);

                    for (int i = 0; i < all.size(); i++) {
                        all.get(i).setContent(polished.get(i).getText());
                    }
                    transcriptRepo.saveAll(all);
                    log.info("[Live] 세션 종료 후처리 완료 — meetingId={}, count={}", mid, all.size());
                }

                // 요약은 프론트가 POST /summarize 를 직접 호출하므로 여기서 중복 트리거하지 않음
                wsManager.broadcast(sessionId, objectMapper.writeValueAsString(
                        Map.of("type", "session_ended", "transcript_count", all.size())));
                wsManager.closeAll(sessionId);
            }

        } catch (Exception ex) {
            log.error("[Live] 처리 실패 — {}: {}", sessionId, ex.getMessage());
            try {
                wsManager.broadcast(sessionId, objectMapper.writeValueAsString(Map.of(
                        "type", "error", "message", ex.getMessage()
                )));
                if (isFinal) wsManager.closeAll(sessionId);
            } catch (Exception ignored) {}
        }
    }
}
