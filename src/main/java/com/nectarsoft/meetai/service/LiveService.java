package com.nectarsoft.meetai.service;

import com.nectarsoft.meetai.core.exception.Exceptions;
import com.nectarsoft.meetai.model.Meeting;
import com.nectarsoft.meetai.model.MeetingStatus;
import com.nectarsoft.meetai.model.MeetingType;
import com.nectarsoft.meetai.repository.MeetingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class LiveService {

    private final MeetingRepository meetingRepo;
    private final LiveBufferProcessor processor;

    private final ConcurrentHashMap<UUID, SessionBuffer> buffers = new ConcurrentHashMap<>();

    public Meeting createSession() {
        Meeting meeting = Meeting.builder()
                .meetingType(MeetingType.REALTIME)
                .status(MeetingStatus.LIVE)
                .meetingDate(OffsetDateTime.now())
                .build();
        meetingRepo.save(meeting);
        buffers.put(meeting.getMeetingId(), new SessionBuffer());
        log.info("[Live] 세션 생성 — meetingId={}", meeting.getMeetingId());
        return meeting;
    }

    public void handleChunk(String sessionId, byte[] audioData) {
        UUID meetingId = UUID.fromString(sessionId);
        Meeting meeting = meetingRepo.findById(meetingId).orElse(null);
        if (meeting == null || meeting.getStatus() != MeetingStatus.LIVE) {
            log.debug("[Live] 비활성 세션 청크 무시 — {}", sessionId);
            return;
        }

        SessionBuffer buffer = buffers.computeIfAbsent(meetingId, k -> new SessionBuffer());
        buffer.addChunk(audioData);
        log.debug("[Live] 청크 누적 — sessionId={}, bytes={}", sessionId, audioData.length);

        if (buffer.shouldProcess()) {
            processor.process(sessionId, buffer, false);
        }
    }

    public void endSession(String sessionId) {
        UUID meetingId = UUID.fromString(sessionId);
        Meeting meeting = meetingRepo.findById(meetingId)
                .orElseThrow(() -> new Exceptions.SessionNotFoundError(sessionId));

        SessionBuffer buffer = buffers.remove(meetingId);
        if (buffer != null && buffer.hasInit()) {
            log.info("[Live] 세션 종료 — 남은 버퍼 처리");
            processor.process(sessionId, buffer, true);
        }

        meeting.setStatus(MeetingStatus.COMPLETED);
        meetingRepo.save(meeting);
        log.info("[Live] 세션 종료 완료 — meetingId={}", meetingId);
    }
}
