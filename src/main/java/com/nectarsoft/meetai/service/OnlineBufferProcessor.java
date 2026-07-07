package com.nectarsoft.meetai.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.nectarsoft.meetai.core.websocket.OnlineRoomManager;
import com.nectarsoft.meetai.model.*;
import com.nectarsoft.meetai.repository.MeetingRepository;
import com.nectarsoft.meetai.repository.ProfileRepository;
import com.nectarsoft.meetai.repository.SttResultRepository;
import com.nectarsoft.meetai.repository.TranscriptRepository;
import com.nectarsoft.meetai.service.stt.RawSegment;
import com.nectarsoft.meetai.service.stt.SttService;
import com.nectarsoft.meetai.storage.FileStorage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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
    private final ProfileRepository profileRepo;
    private final SttResultRepository sttResultRepo;
    private final TranscriptRepository transcriptRepo;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Async
    public void process(String meetingId, String profileId, String speakerDisplay, SessionBuffer buffer) {
        try {
            // profileId → display_name 조회 (없으면 UUID 앞 8자리 사용)
            String resolvedDisplay = profileRepo.findById(UUID.fromString(profileId))
                    .map(p -> p.getDisplayName() != null ? p.getDisplayName() : profileId.substring(0, 8))
                    .orElse(profileId.substring(0, 8));

            byte[] pcmData = buffer.drainAndBuild();
            log.info("[OnlineSTT] 처리 — meetingId={}, profileId={}, bytes={}", meetingId, profileId, pcmData.length);

            // PCM Int16(16kHz mono) → WAV 파일로 조립
            byte[] wavData = buildWav(pcmData, 16000, 1, 16);
            String filename = "online_" + meetingId + "_" + profileId + "_" + System.nanoTime() + ".wav";
            Path tmpFile = fileStorage.saveTempBytes(filename, wavData);
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
                        .speakerDisplay(resolvedDisplay)
                        .startSec(seg.getStartSec())
                        .endSec(seg.getEndSec())
                        .content(seg.getText())
                        .build());

                roomManager.broadcast(meetingId, objectMapper.writeValueAsString(Map.of(
                        "type", "transcript",
                        "profileId", profileId,
                        "speakerDisplay", resolvedDisplay,
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

    private byte[] buildWav(byte[] pcm, int sampleRate, int channels, int bitsPerSample) {
        int byteRate = sampleRate * channels * bitsPerSample / 8;
        int blockAlign = channels * bitsPerSample / 8;
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put(new byte[]{'R','I','F','F'});
        header.putInt(36 + pcm.length);
        header.put(new byte[]{'W','A','V','E'});
        header.put(new byte[]{'f','m','t',' '});
        header.putInt(16);
        header.putShort((short) 1);           // PCM
        header.putShort((short) channels);
        header.putInt(sampleRate);
        header.putInt(byteRate);
        header.putShort((short) blockAlign);
        header.putShort((short) bitsPerSample);
        header.put(new byte[]{'d','a','t','a'});
        header.putInt(pcm.length);
        byte[] result = new byte[44 + pcm.length];
        System.arraycopy(header.array(), 0, result, 0, 44);
        System.arraycopy(pcm, 0, result, 44, pcm.length);
        return result;
    }
}
