package com.nectarsoft.meetai.controller;

import com.nectarsoft.meetai.service.LiveService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@Tag(name = "Live", description = "실시간 라이브 녹음 세션")
@RestController
@RequestMapping("/api/v1/live")
@RequiredArgsConstructor
public class LiveController {

    private final LiveService liveService;

    @Operation(summary = "라이브 세션 종료",
               description = "세션 종료 시 REST 대신 WebSocket으로 {\"type\":\"end\"} 메시지를 보내는 것을 권장합니다.")
    @PostMapping("/sessions/{sessionId}/end")
    public void endSession(@PathVariable String sessionId) {
        liveService.endSession(sessionId);
    }
}
