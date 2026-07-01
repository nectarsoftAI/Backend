package com.nectarsoft.meetai.service;

import com.nectarsoft.meetai.core.exception.Exceptions;
import com.nectarsoft.meetai.model.*;
import com.nectarsoft.meetai.repository.MeetingParticipantRepository;
import com.nectarsoft.meetai.repository.MeetingRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OnlineMeetingService {

    private final MeetingRepository meetingRepo;
    private final MeetingParticipantRepository participantRepo;

    private String generateToken() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 10);
    }

    // 회의 생성 — 초대 토큰 자동 발급, ADMIN 자동 등록
    @Transactional
    public Map<String, String> createMeeting(String title, UUID profileId) {
        String token = generateToken();

        Meeting meeting = Meeting.builder()
                .userId(profileId)
                .title(title)
                .meetingType(MeetingType.DISCORD)
                .status(MeetingStatus.PROCESSING)
                .meetingDate(OffsetDateTime.now())
                .inviteToken(token)
                .build();
        meetingRepo.save(meeting);

        participantRepo.save(MeetingParticipant.builder()
                .meeting(meeting)
                .profileId(profileId)
                .role(ParticipantRole.ADMIN)
                .canInvite(true).canEdit(true).canDelete(true).canRunMeeting(true)
                .build());

        log.info("[온라인회의] 생성 — meetingId={}, adminId={}", meeting.getMeetingId(), profileId);
        return Map.of("meetingId", meeting.getMeetingId().toString(), "token", token);
    }

    // 초대 토큰 재발급
    @Transactional
    public String regenerateInviteToken(UUID meetingId, UUID profileId) {
        Meeting meeting = meetingRepo.findById(meetingId)
                .orElseThrow(() -> new Exceptions.MeetingNotFoundError(meetingId.toString()));

        participantRepo.findByMeetingMeetingIdAndProfileId(meetingId, profileId)
                .filter(p -> p.isCanInvite())
                .orElseThrow(() -> new Exceptions.AccessDeniedError("초대 권한이 없습니다."));

        String token = generateToken();
        meeting.setInviteToken(token);
        meetingRepo.save(meeting);

        log.info("[온라인회의] 토큰 재발급 — meetingId={}", meetingId);
        return token;
    }

    // 참여자 목록 조회
    public List<MeetingParticipant> getParticipants(UUID meetingId) {
        return participantRepo.findByMeetingMeetingId(meetingId);
    }
}
