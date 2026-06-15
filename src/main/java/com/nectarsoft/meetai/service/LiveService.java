package com.nectarsoft.meetai.service;

import com.nectarsoft.meetai.core.exception.Exceptions;
import com.nectarsoft.meetai.model.LiveSession;
import com.nectarsoft.meetai.model.LiveSessionStatus;
import com.nectarsoft.meetai.repository.LiveSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Service
@RequiredArgsConstructor
public class LiveService {

    private final LiveSessionRepository sessionRepo;
    private final LiveBufferProcessor processor;

    private final ConcurrentHashMap<String, SessionBuffer> buffers = new ConcurrentHashMap<>();

    public LiveSession createSession() {
        LiveSession session = LiveSession.builder()
                .id(UUID.randomUUID().toString())
                .status(LiveSessionStatus.ACTIVE)
                .build();
        sessionRepo.save(session);
        buffers.put(session.getId(), new SessionBuffer());
        log.info("[Live] 세션 생성 — {}", session.getId());
        return session;
    }

    /** WebSocket 바이너리 메시지 수신 시 호출 */
    public void handleChunk(String sessionId, byte[] audioData) {
        // 세션 종료 후 마지막 청크가 늦게 도착하는 경우 조용히 무시
        LiveSession session = sessionRepo.findById(sessionId).orElse(null);
        if (session == null || session.getStatus() != LiveSessionStatus.ACTIVE) {
            log.debug("[Live] 비활성 세션 청크 무시 — {}", sessionId);
            return;
        }

        SessionBuffer buffer = buffers.computeIfAbsent(sessionId, k -> new SessionBuffer());
        buffer.addChunk(audioData);
        log.debug("[Live] 청크 누적 — sessionId={}, bytes={}", sessionId, audioData.length);

        // 30초 or 500KB 조건 충족 시 비동기 STT 처리 (별도 컴포넌트로 @Async 정상 동작)
        if (buffer.shouldProcess()) {
            processor.process(sessionId, buffer, false);
        }
    }

    /** WebSocket 종료 or 클라이언트 end 메시지 수신 시 호출 */
    public void endSession(String sessionId) {
        LiveSession session = sessionRepo.findById(sessionId)
                .orElseThrow(() -> new Exceptions.SessionNotFoundError(sessionId));

        // 남은 버퍼 마지막 처리
        SessionBuffer buffer = buffers.remove(sessionId);
        if (buffer != null && buffer.hasInit()) {
            log.info("[Live] 세션 종료 — 남은 버퍼 처리");
            processor.process(sessionId, buffer, true);
        }

        session.setStatus(LiveSessionStatus.ENDED);
        session.setEndedAt(LocalDateTime.now());
        sessionRepo.save(session);
        log.info("[Live] 세션 종료 완료 — {}", sessionId);
    }
}
