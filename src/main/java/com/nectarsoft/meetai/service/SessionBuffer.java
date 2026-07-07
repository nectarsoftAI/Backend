package com.nectarsoft.meetai.service;

import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
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

    // WebM Cluster 엘리먼트 ID (0x1F43B675) — 첫 발화 오디오가 시작되는 지점
    private static final byte[] CLUSTER_ID = {(byte) 0x1F, (byte) 0x43, (byte) 0xB6, (byte) 0x75};

    public synchronized void addChunk(byte[] data) {
        initialized = true;
        pendingChunks.add(data);
        pendingSize += data.length;
        log.debug("[Buffer] PCM 청크 추가 — {}bytes (누적 {}bytes)", data.length, pendingSize);
        if (initSegment == null) {
            // 첫 청크 = [초기화 세그먼트(헤더~Tracks)] + [첫 Cluster(첫 발화 오디오)]
            // 헤더 부분만 initSegment로 분리 저장하고, 첫 Cluster는 일반 청크로 취급해야
            // 매 처리마다 첫 발화가 중복 재주입되지 않는다.
            int clusterOffset = findFirstClusterOffset(data);
            if (clusterOffset > 0) {
                initSegment = Arrays.copyOfRange(data, 0, clusterOffset);
                byte[] firstCluster = Arrays.copyOfRange(data, clusterOffset, data.length);
                pendingChunks.add(firstCluster);
                pendingSize += firstCluster.length;
                log.debug("[Buffer] initSegment {}bytes / 첫 Cluster {}bytes 분리",
                        initSegment.length, firstCluster.length);
            } else {
                // Cluster 경계를 못 찾으면 안전하게 기존 방식(전체를 헤더로) 유지
                initSegment = data;
                log.warn("[Buffer] Cluster 경계 미검출 — 전체 {}bytes를 initSegment로 저장", data.length);
            }
        } else {
            pendingChunks.add(data);
            pendingSize += data.length;
        }
    }

    /**
     * WebM 바이트 스트림에서 첫 Cluster 엘리먼트(ID 0x1F43B675) 시작 오프셋을 찾는다.
     * @return 오프셋(>0), 못 찾으면 -1
     */
    private static int findFirstClusterOffset(byte[] data) {
        outer:
        for (int i = 0; i + CLUSTER_ID.length <= data.length; i++) {
            for (int j = 0; j < CLUSTER_ID.length; j++) {
                if (data[i + j] != CLUSTER_ID[j]) continue outer;
            }
            return i;
        }
        return -1;
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
