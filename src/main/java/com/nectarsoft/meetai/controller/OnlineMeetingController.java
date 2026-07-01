package com.nectarsoft.meetai.controller;

import com.nectarsoft.meetai.dto.*;
import com.nectarsoft.meetai.model.Meeting;
import com.nectarsoft.meetai.model.MeetingParticipant;
import com.nectarsoft.meetai.service.OnlineMeetingService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/v1/meetings")
@RequiredArgsConstructor
public class OnlineMeetingController {

    private final OnlineMeetingService onlineMeetingService;

    // 온라인 회의 생성
    @PostMapping
    public ResponseEntity<Map<String, String>> createMeeting(
            @RequestBody CreateMeetingRequest req,
            @RequestHeader("X-User-Id") UUID profileId) {
        Meeting meeting = onlineMeetingService.createMeeting(req.getTitle(), profileId);
        return ResponseEntity.ok(Map.of("meetingId", meeting.getMeetingId().toString()));
    }

    // 초대 토큰 생성
    @PostMapping("/{meetingId}/invite")
    public ResponseEntity<InviteResponse> generateInviteToken(
            @PathVariable UUID meetingId,
            @RequestHeader("X-User-Id") UUID profileId) {
        String token = onlineMeetingService.generateInviteToken(meetingId, profileId);
        return ResponseEntity.ok(new InviteResponse(meetingId.toString(), token));
    }

    // 초대 토큰으로 참여
    @GetMapping("/{meetingId}/join")
    public ResponseEntity<ParticipantResponse> joinWithToken(
            @PathVariable UUID meetingId,
            @RequestParam String token,
            @RequestHeader("X-User-Id") UUID profileId) {
        MeetingParticipant participant = onlineMeetingService.joinWithToken(meetingId, token, profileId);
        return ResponseEntity.ok(ParticipantResponse.from(participant));
    }

    // 참여자 목록 조회
    @GetMapping("/{meetingId}/participants")
    public ResponseEntity<List<ParticipantResponse>> getParticipants(@PathVariable UUID meetingId) {
        List<ParticipantResponse> list = onlineMeetingService.getParticipants(meetingId)
                .stream().map(ParticipantResponse::from).collect(Collectors.toList());
        return ResponseEntity.ok(list);
    }

    // 참여자 역할/권한 변경
    @PatchMapping("/{meetingId}/participants/{targetProfileId}/role")
    public ResponseEntity<ParticipantResponse> updateRole(
            @PathVariable UUID meetingId,
            @PathVariable UUID targetProfileId,
            @RequestHeader("X-User-Id") UUID requesterId,
            @RequestBody UpdateRoleRequest req) {
        MeetingParticipant updated = onlineMeetingService.updateRole(
                meetingId, targetProfileId, requesterId,
                req.getRole(), req.isCanInvite(), req.isCanEdit(), req.isCanDelete(), req.isCanRunMeeting());
        return ResponseEntity.ok(ParticipantResponse.from(updated));
    }

    // 참여자 퇴장/강퇴
    @DeleteMapping("/{meetingId}/participants/{targetProfileId}")
    public ResponseEntity<Void> removeParticipant(
            @PathVariable UUID meetingId,
            @PathVariable UUID targetProfileId,
            @RequestHeader("X-User-Id") UUID requesterId) {
        onlineMeetingService.removeParticipant(meetingId, targetProfileId, requesterId);
        return ResponseEntity.noContent().build();
    }

    // 회의 시작
    @PostMapping("/{meetingId}/start")
    public ResponseEntity<Void> startMeeting(
            @PathVariable UUID meetingId,
            @RequestHeader("X-User-Id") UUID profileId) {
        onlineMeetingService.startMeeting(meetingId, profileId);
        return ResponseEntity.ok().build();
    }

    // 회의 종료
    @PostMapping("/{meetingId}/end")
    public ResponseEntity<Void> endMeeting(
            @PathVariable UUID meetingId,
            @RequestHeader("X-User-Id") UUID profileId) {
        onlineMeetingService.endMeeting(meetingId, profileId);
        return ResponseEntity.ok().build();
    }
}
