package com.nectarsoft.meetai.service;

import com.nectarsoft.meetai.core.exception.Exceptions;
import com.nectarsoft.meetai.model.*;
import com.nectarsoft.meetai.repository.MeetingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class LiveService {

    private final MeetingRepository meetingRepo;
    private final AssemblyAiStreamingManager streamingManager;

    public Meeting createSession(String title, UUID profileId) {
        Meeting meeting = Meeting.builder()
                .userId(profileId)
                .title(title)
                .meetingType(MeetingType.REALTIME)
                .status(MeetingStatus.LIVE)
                .meetingDate(OffsetDateTime.now())
                .build();
        meetingRepo.save(meeting);
        log.info("[Live] 세션 생성 — meetingId={}", meeting.getMeetingId());
        return meeting;
    }

    public void endSession(String meetingId) {
        UUID mid = UUID.fromString(meetingId);
        Meeting meeting = meetingRepo.findById(mid)
                .orElseThrow(() -> new Exceptions.SessionNotFoundError(meetingId));

        if (meeting.getStatus() == MeetingStatus.COMPLETED) return;

        streamingManager.endAllSessions(meetingId);

        if (meeting.getMeetingDate() != null) {
            meeting.setDurationSeconds(
                    (int) ChronoUnit.SECONDS.between(meeting.getMeetingDate(), OffsetDateTime.now()));
        }
        meeting.setStatus(MeetingStatus.COMPLETED);
        meetingRepo.save(meeting);
        log.info("[Live] 세션 종료 완료 — meetingId={}", meetingId);
    }
}
