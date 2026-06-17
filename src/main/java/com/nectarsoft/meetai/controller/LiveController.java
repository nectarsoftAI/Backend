package com.nectarsoft.meetai.controller;

import com.nectarsoft.meetai.dto.LiveSessionResponse;
import com.nectarsoft.meetai.model.Meeting;
import com.nectarsoft.meetai.service.LiveService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@Tag(name = "Live", description = "실시간 라이브 녹음 세션")
@RestController
@RequestMapping("/api/v1/live")
@RequiredArgsConstructor
public class LiveController {

    private final LiveService liveService;

    @Operation(summary = "라이브 세션 생성",
               description = "세션 생성 후 반환된 wsUrl 로 WebSocket 연결하여 오디오 청크를 스트리밍합니다.")
    @PostMapping("/sessions")
    public LiveSessionResponse createSession(HttpServletRequest request) {
        Meeting meeting = liveService.createSession();
        String host = request.getServerName() + ":" + request.getServerPort();
        return LiveSessionResponse.from(meeting, host);
    }

    @Operation(summary = "라이브 세션 종료")
    @PostMapping("/sessions/{sessionId}/end")
    public void endSession(@PathVariable String sessionId) {
        liveService.endSession(sessionId);
    }
}
