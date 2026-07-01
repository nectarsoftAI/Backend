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
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class OnlineMeetingService {

    private final MeetingRepository meetingRepo;
    private final MeetingParticipantRepository participantRepo;

    @Transactional
    public Meeting createMeeting(String title, UUID profileId) {
        Meeting meeting = Meeting.builder()
                .userId(profileId)
                .title(title)
                .meetingType(MeetingType.DISCORD)
                .status(MeetingStatus.PROCESSING)
                .meetingDate(OffsetDateTime.now())
                .build();
        meetingRepo.save(meeting);

        MeetingParticipant admin = MeetingParticipant.builder()
                .meeting(meeting)
                .profileId(profileId)
                .role(ParticipantRole.ADMIN)
                .canInvite(true)
                .canEdit(true)
                .canDelete(true)
                .canRunMeeting(true)
                .build();
        participantRepo.save(admin);

        log.info("[온라인회의] 생성 — meetingId={}, adminId={}", meeting.getMeetingId(), profileId);
        return meeting;
    }

    @Transactional
    public String generateInviteToken(UUID meetingId, UUID profileId) {
        Meeting meeting = meetingRepo.findById(meetingId)
                .orElseThrow(() -> new Exceptions.MeetingNotFoundError(meetingId.toString()));

        MeetingParticipant requester = participantRepo
                .findByMeetingMeetingIdAndProfileId(meetingId, profileId)
                .orElseThrow(() -> new Exceptions.AccessDeniedError("참여자가 아닙니다."));

        if (!requester.isCanInvite()) {
            throw new Exceptions.AccessDeniedError("초대 권한이 없습니다.");
        }

        String token = UUID.randomUUID().toString().replace("-", "");
        meeting.setInviteToken(token);
        meetingRepo.save(meeting);

        log.info("[온라인회의] 초대 토큰 생성 — meetingId={}", meetingId);
        return token;
    }

    @Transactional
    public MeetingParticipant joinWithToken(UUID meetingId, String token, UUID profileId) {
        Meeting meeting = meetingRepo.findById(meetingId)
                .orElseThrow(() -> new Exceptions.MeetingNotFoundError(meetingId.toString()));

        if (meeting.getInviteToken() == null || !meeting.getInviteToken().equals(token)) {
            throw new Exceptions.AccessDeniedError("유효하지 않은 초대 토큰입니다.");
        }

        if (meeting.getStatus() != MeetingStatus.LIVE && meeting.getStatus() != MeetingStatus.PROCESSING) {
            throw new Exceptions.AccessDeniedError("참여 가능한 상태가 아닙니다.");
        }

        return participantRepo.findByMeetingMeetingIdAndProfileId(meetingId, profileId)
                .orElseGet(() -> {
                    MeetingParticipant guest = MeetingParticipant.builder()
                            .meeting(meeting)
                            .profileId(profileId)
                            .role(ParticipantRole.GUEST)
                            .canInvite(false)
                            .canEdit(false)
                            .canDelete(false)
                            .canRunMeeting(false)
                            .build();
                    log.info("[온라인회의] 참여자 입장 — meetingId={}, profileId={}", meetingId, profileId);
                    return participantRepo.save(guest);
                });
    }

    public List<MeetingParticipant> getParticipants(UUID meetingId) {
        return participantRepo.findByMeetingMeetingId(meetingId);
    }

    @Transactional
    public MeetingParticipant updateRole(UUID meetingId, UUID targetProfileId, UUID requesterId,
                                         ParticipantRole newRole, boolean canInvite,
                                         boolean canEdit, boolean canDelete, boolean canRunMeeting) {
        participantRepo.findByMeetingMeetingIdAndProfileId(meetingId, requesterId)
                .filter(p -> p.getRole() == ParticipantRole.ADMIN)
                .orElseThrow(() -> new Exceptions.AccessDeniedError("관리자만 권한을 변경할 수 있습니다."));

        MeetingParticipant target = participantRepo
                .findByMeetingMeetingIdAndProfileId(meetingId, targetProfileId)
                .orElseThrow(() -> new Exceptions.MeetingNotFoundError("참여자를 찾을 수 없습니다."));

        target.setRole(newRole);
        target.setCanInvite(canInvite);
        target.setCanEdit(canEdit);
        target.setCanDelete(canDelete);
        target.setCanRunMeeting(canRunMeeting);
        return participantRepo.save(target);
    }

    @Transactional
    public void removeParticipant(UUID meetingId, UUID targetProfileId, UUID requesterId) {
        MeetingParticipant requester = participantRepo
                .findByMeetingMeetingIdAndProfileId(meetingId, requesterId)
                .orElseThrow(() -> new Exceptions.AccessDeniedError("참여자가 아닙니다."));

        if (requester.getRole() != ParticipantRole.ADMIN && !requesterId.equals(targetProfileId)) {
            throw new Exceptions.AccessDeniedError("권한이 없습니다.");
        }

        participantRepo.findByMeetingMeetingIdAndProfileId(meetingId, targetProfileId)
                .ifPresent(participantRepo::delete);
    }

    @Transactional
    public void startMeeting(UUID meetingId, UUID profileId) {
        Meeting meeting = meetingRepo.findById(meetingId)
                .orElseThrow(() -> new Exceptions.MeetingNotFoundError(meetingId.toString()));

        participantRepo.findByMeetingMeetingIdAndProfileId(meetingId, profileId)
                .filter(MeetingParticipant::isCanRunMeeting)
                .orElseThrow(() -> new Exceptions.AccessDeniedError("회의 시작 권한이 없습니다."));

        meeting.setStatus(MeetingStatus.LIVE);
        meetingRepo.save(meeting);
        log.info("[온라인회의] 시작 — meetingId={}", meetingId);
    }

    @Transactional
    public void endMeeting(UUID meetingId, UUID profileId) {
        Meeting meeting = meetingRepo.findById(meetingId)
                .orElseThrow(() -> new Exceptions.MeetingNotFoundError(meetingId.toString()));

        participantRepo.findByMeetingMeetingIdAndProfileId(meetingId, profileId)
                .filter(MeetingParticipant::isCanRunMeeting)
                .orElseThrow(() -> new Exceptions.AccessDeniedError("회의 종료 권한이 없습니다."));

        meeting.setStatus(MeetingStatus.COMPLETED);
        meetingRepo.save(meeting);
        log.info("[온라인회의] 종료 — meetingId={}", meetingId);
    }
}
