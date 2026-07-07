package com.nectarsoft.meetai.service;

import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 온라인 세션별 PCM 오디오 버퍼
 * - 프론트에서 AudioWorklet → Int16 PCM 청크로 전송
 * - 5초 or 500KB 누적 시 STT 트리거
 * - 각 청크가 독립적(WAV 헤더는 OnlineBufferProcessor에서 조립)
 */
@Slf4j
public class SessionBuffer {

    private static final long PROCESS_INTERVAL_MS = 5_000L;
    private static final long MAX_PENDING_BYTES   = 500_000L;

    // PCM 청크가 하나라도 있으면 처리 가능 상태
    private boolean initialized = false;
    private final List<byte[]> pendingChunks = new ArrayList<>();
    private long pendingSize = 0;
    private long lastProcessedMs = System.currentTimeMillis();

    public synchronized void addChunk(byte[] data) {
        initialized = true;
        pendingChunks.add(data);
        pendingSize += data.length;
        log.debug("[Buffer] PCM 청크 추가 — {}bytes (누적 {}bytes)", data.length, pendingSize);
    }

    public synchronized boolean shouldProcess() {
        if (!initialized || pendingChunks.isEmpty()) return false;
        long elapsed = System.currentTimeMillis() - lastProcessedMs;
        return elapsed >= PROCESS_INTERVAL_MS || pendingSize >= MAX_PENDING_BYTES;
    }

    public synchronized byte[] drainAndBuild() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (byte[] chunk : pendingChunks) out.write(chunk);
        pendingChunks.clear();
        pendingSize = 0;
        lastProcessedMs = System.currentTimeMillis();
        return out.toByteArray();
    }

    public synchronized boolean hasInit() {
        return initialized;
    }
}
