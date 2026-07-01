package com.nectarsoft.meetai.controller;

import com.nectarsoft.meetai.model.Meeting;
import com.nectarsoft.meetai.service.LiveService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@Tag(name = "Live", description = "실시간 라이브 녹음 세션")
@RestController
@RequestMapping("/api/v1/live")
@RequiredArgsConstructor
public class LiveController {

    private final LiveService liveService;

    @Data
    static class CreateSessionRequest {
        private String title;
    }

    @Operation(summary = "라이브 세션 생성",
               description = "프론트에서 전달한 title로 세션을 생성하고 meetingId를 반환합니다.")
    @PostMapping("/sessions")
    @ResponseStatus(HttpStatus.CREATED)
    public Map<String, String> createSession(
            @RequestBody CreateSessionRequest req,
            @RequestHeader(value = "X-User-Id", required = false) UUID profileId) {
        Meeting meeting = liveService.createSession(req.getTitle(), profileId);
        return Map.of("meetingId", meeting.getMeetingId().toString());
    }

    @Operation(summary = "라이브 세션 종료",
               description = "세션 종료 시 REST 대신 WebSocket으로 {\"type\":\"end\"} 메시지를 보내는 것을 권장합니다.")
    @PostMapping("/sessions/{sessionId}/end")
    public void endSession(@PathVariable String sessionId) {
        liveService.endSession(sessionId);
    }
}
