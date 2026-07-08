package com.nectarsoft.meetai.service;

import lombok.extern.slf4j.Slf4j;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@Slf4j
public class SessionBuffer {

    private static final byte[] CLUSTER_ID = {(byte) 0x1F, (byte) 0x43, (byte) 0xB6, (byte) 0x75};
    private static final long PROCESS_INTERVAL_MS = 5_000L;
    private static final long MAX_PENDING_BYTES   = 500_000L;

    private byte[] initSegment = null;
    private final List<byte[]> pendingChunks = new ArrayList<>();
    private long pendingSize = 0;
    private long lastProcessedMs = System.currentTimeMillis();

    public synchronized void addChunk(byte[] data) {
        if (initSegment == null) {
            int clusterOffset = findClusterOffset(data);
            if (clusterOffset > 0) {
                initSegment = Arrays.copyOfRange(data, 0, clusterOffset);
                byte[] firstCluster = Arrays.copyOfRange(data, clusterOffset, data.length);
                pendingChunks.add(firstCluster);
                pendingSize += firstCluster.length;
                log.debug("[Buffer] initSegment {}bytes / 첫 Cluster {}bytes 분리",
                        initSegment.length, firstCluster.length);
            } else {
                initSegment = data;
                log.warn("[Buffer] Cluster 경계 미검출 — 전체 {}bytes를 initSegment로 저장", data.length);
            }
        } else {
            pendingChunks.add(data);
            pendingSize += data.length;
            log.debug("[Buffer] 청크 추가 — {}bytes (누적 {}bytes)", data.length, pendingSize);
        }
    }

    private static int findClusterOffset(byte[] data) {
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

    public synchronized byte[] drainAndBuild() throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(initSegment);
        for (byte[] chunk : pendingChunks) out.write(chunk);
        pendingChunks.clear();
        pendingSize = 0;
        lastProcessedMs = System.currentTimeMillis();
        return out.toByteArray();
    }

    public synchronized boolean hasInit() {
        return initSegment != null;
    }
}
