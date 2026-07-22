package com.nectarsoft.meetai.service;

import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 온라인 회의 STT 엔진 A/B 비교용 지연 수집기.
 *
 * Deepgram과 OpenAI Realtime은 내부 구조가 달라(턴 누적 vs 서버 VAD) 각자 다른 중간
 * 지표를 갖지만, 비교하려면 "사용자가 체감하는 같은 것"을 같은 이름으로 재야 한다.
 * 그래서 엔진 중립적인 3개 지표만 공통으로 수집한다.
 *
 * - firstMs  발화 시작 → 첫 partial 방출. 화면에 글자가 처음 보이기까지 (반응성)
 * - finalMs  발화 종료 → 확정 자막 방출. 문장이 완성되기까지 (정확한 자막 대기)
 * - engineMs 그중 엔진이 쓴 몫. 지연의 책임이 엔진인지 우리 파이프라인인지 구분
 *
 * 개별 로그만으로는 판단이 어려워(회의 하나에 수백 줄) 세션 종료 시 P50/P95를 남긴다.
 * A/B는 평균이 아니라 분위수로 봐야 한다 — 평균이 같아도 P95가 3배면 체감은 전혀 다르다.
 */
@Slf4j
public class SttLatencyStats {

    private final String engine;   // "deepgram" | "openai-realtime"
    private final String display;  // 참가자 표시명
    private final boolean enabled;

    // WS 리스너 스레드에서 기록되므로 동기화한다 (회의당 수백 건이라 경합 부담은 미미)
    private final List<Long> firstMs = Collections.synchronizedList(new ArrayList<>());
    private final List<Long> finalMs = Collections.synchronizedList(new ArrayList<>());
    private final List<Long> engineMs = Collections.synchronizedList(new ArrayList<>());
    // 같은 턴의 후속 partial을 걸러내기 위한 직전 턴 시작 시각 (firstMs 잠금으로 보호)
    private double lastFirstTurnStart = Double.NaN;

    public SttLatencyStats(String engine, String display, boolean enabled) {
        this.engine = engine;
        this.display = display;
        this.enabled = enabled;
    }

    public String engine() {
        return engine;
    }

    /**
     * 발화 시작 → 첫 partial 도착.
     *
     * 엔진마다 값의 성격이 다르다는 점을 알고 읽어야 한다.
     * - Deepgram: 발화 중에 partial이 오므로 작은 값 (수백 ms)
     * - OpenAI Realtime: 문장이 끝나야 델타가 오므로 발화 길이가 통째로 포함된다
     * 이건 계측 오류가 아니라 "언제 화면에 글자가 뜨는가"의 실제 차이다.
     * 같은 오디오로 비교하면 발화 길이가 상쇄되므로 엔진 간 비교는 유효하다.
     */
    public void recordFirst(long ms) {
        if (!enabled || ms < 0) return;
        firstMs.add(ms);
        log.debug("[AB] engine={} kind=partial firstMs={} speaker={}", engine, ms, display);
    }

    /**
     * 한 발화(턴)당 첫 partial만 기록한다.
     *
     * Deepgram은 발화 중 partial을 초당 여러 번 보내는데, 매번 기록하면
     * 같은 턴의 후속 partial이 계속 큰 값으로 쌓여(오디오는 진행하고 턴 시작은 고정)
     * 표본 수가 부풀고 분포가 위로 왜곡된다. 발화당 1회만 기록하는
     * OpenAI 경로(putIfAbsent)와 표본 성격을 맞추기 위한 것이다.
     */
    public void recordFirstOnce(double turnStartSec, long ms) {
        if (!enabled) return;
        synchronized (firstMs) {
            if (turnStartSec == lastFirstTurnStart) return; // 같은 턴의 후속 partial
            lastFirstTurnStart = turnStartSec;
        }
        recordFirst(ms);
    }

    /**
     * 발화 종료 → 확정 자막 방출.
     * engineMs가 음수면(측정 불가) 수집에서 제외하고 finalMs만 남긴다.
     */
    public void recordFinal(long totalMs, long engineOnlyMs, String text) {
        if (!enabled || totalMs < 0) return;
        finalMs.add(totalMs);
        if (engineOnlyMs >= 0) engineMs.add(engineOnlyMs);
        log.info("[AB] engine={} kind=final finalMs={} engineMs={} speaker={} text=\"{}\"",
                engine, totalMs, engineOnlyMs >= 0 ? engineOnlyMs : "n/a", display, text);
    }

    /** 세션 종료 시 1회 — 이 줄만 모으면 엔진별 비교표가 된다 */
    public void logSummary() {
        if (!enabled) return;
        if (finalMs.isEmpty() && firstMs.isEmpty()) {
            log.info("[AB요약] engine={} speaker={} — 수집된 발화 없음", engine, display);
            return;
        }
        log.info("[AB요약] engine={} speaker={} | final {}건 P50={} P95={} max={} | "
                        + "first {}건 P50={} P95={} | engine P50={} P95={}",
                engine, display,
                finalMs.size(), pct(finalMs, 50), pct(finalMs, 95), max(finalMs),
                firstMs.size(), pct(firstMs, 50), pct(firstMs, 95),
                pct(engineMs, 50), pct(engineMs, 95));
    }

    /** 최근접 순위(nearest-rank) 분위수 — 표본이 적어도 왜곡이 적다 */
    private static String pct(List<Long> values, int p) {
        List<Long> copy;
        synchronized (values) {
            if (values.isEmpty()) return "n/a";
            copy = new ArrayList<>(values);
        }
        Collections.sort(copy);
        int idx = (int) Math.ceil(p / 100.0 * copy.size()) - 1;
        return copy.get(Math.max(0, Math.min(idx, copy.size() - 1))) + "ms";
    }

    private static String max(List<Long> values) {
        synchronized (values) {
            return values.isEmpty() ? "n/a" : Collections.max(values) + "ms";
        }
    }
}
