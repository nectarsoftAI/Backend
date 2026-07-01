package com.nectarsoft.meetai.controller;

import com.nectarsoft.meetai.dto.*;
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

    // 온라인 회의 생성 — meetingId + 초대 토큰 반환
    @PostMapping("/online")
    public ResponseEntity<Map<String, String>> createMeeting(
            @RequestBody CreateMeetingRequest req,
            @RequestHeader("X-User-Id") UUID profileId) {
        return ResponseEntity.ok(onlineMeetingService.createMeeting(req.getTitle(), profileId));
    }

    // 초대 토큰 재발급
    @PostMapping("/{meetingId}/invite")
    public ResponseEntity<InviteResponse> regenerateInviteToken(
            @PathVariable UUID meetingId,
            @RequestHeader("X-User-Id") UUID profileId) {
        String token = onlineMeetingService.regenerateInviteToken(meetingId, profileId);
        return ResponseEntity.ok(new InviteResponse(meetingId.toString(), token));
    }

    // 참여자 목록 조회
    @GetMapping("/{meetingId}/participants")
    public ResponseEntity<List<ParticipantResponse>> getParticipants(@PathVariable UUID meetingId) {
        List<ParticipantResponse> list = onlineMeetingService.getParticipants(meetingId)
                .stream().map(ParticipantResponse::from).collect(Collectors.toList());
        return ResponseEntity.ok(list);
    }
}
