package com.nectarsoft.meetai.service;

import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * 라이브 세션별 오디오 버퍼
 * - 첫 번째 청크(WebM 헤더 포함)를 initSegment로 보관
 * - 이후 청크는 pendingChunks에 누적
 * - 30초 or 500KB 초과 시 처리 트리거
 */
@Slf4j
public class SessionBuffer {

    private static final long PROCESS_INTERVAL_MS = 5_000L; // 5초
    private static final long MAX_PENDING_BYTES   = 500_000L; // ~500KB

    private byte[] initSegment = null;
    private final List<byte[]> pendingChunks = new ArrayList<>();
    private long pendingSize = 0;
    private long lastProcessedMs = System.currentTimeMillis();

    public synchronized void addChunk(byte[] data) {
        if (initSegment == null) {
            // 첫 청크 = WebM EBML 헤더 포함 → 매 처리 시 앞에 붙임
            initSegment = data;
            log.debug("[Buffer] initSegment 저장 — {}bytes", data.length);
        } else {
            pendingChunks.add(data);
            pendingSize += data.length;
        }
    }

    public synchronized boolean shouldProcess() {
        if (initSegment == null || pendingChunks.isEmpty()) return false;
        long elapsed = System.currentTimeMillis() - lastProcessedMs;
        return elapsed >= PROCESS_INTERVAL_MS || pendingSize >= MAX_PENDING_BYTES;
    }

    /**
     * initSegment + pendingChunks 를 하나의 WebM 바이트 배열로 합쳐 반환
     * 호출 후 pendingChunks 초기화
     */
    public synchronized byte[] drainAndBuild() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(initSegment);
        for (byte[] chunk : pendingChunks) {
            out.write(chunk);
        }
        pendingChunks.clear();
        pendingSize = 0;
        lastProcessedMs = System.currentTimeMillis();
        return out.toByteArray();
    }

    public synchronized boolean hasInit() {
        return initSegment != null;
    }
}
