package com.nectarsoft.meetai.service;

import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * 라이브 세션별 오디오 버퍼
 * - 첫 번째 청크의 WebM 초기화 세그먼트(EBML 헤더~Tracks)만 initSegment로 보관
 * - 첫 청크에 함께 실려온 첫 Cluster(첫 발화 오디오)는 일반 청크로 취급
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

    // WebM Cluster 엘리먼트 ID (0x1F43B675) — 첫 발화 오디오가 시작되는 지점
    private static final byte[] CLUSTER_ID = {(byte) 0x1F, (byte) 0x43, (byte) 0xB6, (byte) 0x75};

    public synchronized void addChunk(byte[] data) {
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
