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
    public Meeting createMeeting(String title, UUID hostUserId) {
        Meeting meeting = Meeting.builder()
                .userId(hostUserId)
                .title(title)
                .meetingType(MeetingType.DISCORD)
                .status(MeetingStatus.PROCESSING)
                .meetingDate(OffsetDateTime.now())
                .build();
        meetingRepo.save(meeting);

        // 방장 자동 등록 — 모든 권한 부여
        MeetingParticipant host = MeetingParticipant.builder()
                .meeting(meeting)
                .userId(hostUserId)
                .role(ParticipantRole.HOST)
                .canInvite(true)
                .canEdit(true)
                .canDelete(true)
                .canStartEnd(true)
                .build();
        participantRepo.save(host);

        log.info("[온라인회의] 생성 — meetingId={}, hostId={}", meeting.getMeetingId(), hostUserId);
        return meeting;
    }

    @Transactional
    public String generateInviteToken(UUID meetingId, UUID requesterId) {
        Meeting meeting = meetingRepo.findById(meetingId)
                .orElseThrow(() -> new Exceptions.MeetingNotFoundError(meetingId.toString()));

        MeetingParticipant requester = participantRepo
                .findByMeetingMeetingIdAndUserId(meetingId, requesterId)
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
    public MeetingParticipant joinWithToken(UUID meetingId, String token, UUID userId) {
        Meeting meeting = meetingRepo.findById(meetingId)
                .orElseThrow(() -> new Exceptions.MeetingNotFoundError(meetingId.toString()));

        if (meeting.getInviteToken() == null || !meeting.getInviteToken().equals(token)) {
            throw new Exceptions.AccessDeniedError("유효하지 않은 초대 토큰입니다.");
        }

        if (meeting.getStatus() != MeetingStatus.LIVE && meeting.getStatus() != MeetingStatus.PROCESSING) {
            throw new Exceptions.AccessDeniedError("참여 가능한 상태가 아닙니다.");
        }

        // 이미 참여 중이면 기존 정보 반환
        return participantRepo.findByMeetingMeetingIdAndUserId(meetingId, userId)
                .orElseGet(() -> {
                    MeetingParticipant guest = MeetingParticipant.builder()
                            .meeting(meeting)
                            .userId(userId)
                            .role(ParticipantRole.GUEST)
                            .canInvite(false)
                            .canEdit(false)
                            .canDelete(false)
                            .canStartEnd(false)
                            .build();
                    log.info("[온라인회의] 참여자 입장 — meetingId={}, userId={}", meetingId, userId);
                    return participantRepo.save(guest);
                });
    }

    public List<MeetingParticipant> getParticipants(UUID meetingId) {
        return participantRepo.findByMeetingMeetingId(meetingId);
    }

    @Transactional
    public MeetingParticipant updateRole(UUID meetingId, UUID targetUserId, UUID requesterId,
                                         ParticipantRole newRole, boolean canInvite,
                                         boolean canEdit, boolean canDelete, boolean canStartEnd) {
        participantRepo.findByMeetingMeetingIdAndUserId(meetingId, requesterId)
                .filter(p -> p.getRole() == ParticipantRole.HOST)
                .orElseThrow(() -> new Exceptions.AccessDeniedError("방장만 권한을 변경할 수 있습니다."));

        MeetingParticipant target = participantRepo
                .findByMeetingMeetingIdAndUserId(meetingId, targetUserId)
                .orElseThrow(() -> new Exceptions.MeetingNotFoundError("참여자를 찾을 수 없습니다."));

        target.setRole(newRole);
        target.setCanInvite(canInvite);
        target.setCanEdit(canEdit);
        target.setCanDelete(canDelete);
        target.setCanStartEnd(canStartEnd);
        return participantRepo.save(target);
    }

    @Transactional
    public void removeParticipant(UUID meetingId, UUID targetUserId, UUID requesterId) {
        MeetingParticipant requester = participantRepo
                .findByMeetingMeetingIdAndUserId(meetingId, requesterId)
                .orElseThrow(() -> new Exceptions.AccessDeniedError("참여자가 아닙니다."));

        // 방장이거나 본인만 퇴장 가능
        if (requester.getRole() != ParticipantRole.HOST && !requesterId.equals(targetUserId)) {
            throw new Exceptions.AccessDeniedError("권한이 없습니다.");
        }

        participantRepo.findByMeetingMeetingIdAndUserId(meetingId, targetUserId)
                .ifPresent(participantRepo::delete);
    }

    @Transactional
    public void startMeeting(UUID meetingId, UUID requesterId) {
        Meeting meeting = meetingRepo.findById(meetingId)
                .orElseThrow(() -> new Exceptions.MeetingNotFoundError(meetingId.toString()));

        participantRepo.findByMeetingMeetingIdAndUserId(meetingId, requesterId)
                .filter(MeetingParticipant::isCanStartEnd)
                .orElseThrow(() -> new Exceptions.AccessDeniedError("회의 시작 권한이 없습니다."));

        meeting.setStatus(MeetingStatus.LIVE);
        meetingRepo.save(meeting);
        log.info("[온라인회의] 시작 — meetingId={}", meetingId);
    }

    @Transactional
    public void endMeeting(UUID meetingId, UUID requesterId) {
        Meeting meeting = meetingRepo.findById(meetingId)
                .orElseThrow(() -> new Exceptions.MeetingNotFoundError(meetingId.toString()));

        participantRepo.findByMeetingMeetingIdAndUserId(meetingId, requesterId)
                .filter(MeetingParticipant::isCanStartEnd)
                .orElseThrow(() -> new Exceptions.AccessDeniedError("회의 종료 권한이 없습니다."));

        meeting.setStatus(MeetingStatus.COMPLETED);
        meetingRepo.save(meeting);
        log.info("[온라인회의] 종료 — meetingId={}", meetingId);
    }
}
