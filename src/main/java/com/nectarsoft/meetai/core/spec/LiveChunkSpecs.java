package com.nectarsoft.meetai.core.spec;

import com.nectarsoft.meetai.config.MeetAiProperties;

/** 라이브 청크 유효성 검증 Specification 빌더 — Specification 패턴 */
public final class LiveChunkSpecs {

    private LiveChunkSpecs() {}

    public static Specification<AudioChunk> build(MeetAiProperties props) {
        MeetAiProperties.Live cfg = props.getLive();
        return minDuration(cfg.getChunkMinDurationSec())
                .and(maxSilenceRatio(cfg.getChunkMaxSilenceRatio()))
                .and(expectedSampleRate(cfg.getChunkExpectedSampleRate()));
    }

    // ── 개별 Spec ──────────────────────────────────────────────────────

    static Specification<AudioChunk> minDuration(double minSec) {
        return new Specification<>() {
            @Override
            public boolean isSatisfiedBy(AudioChunk c) { return c.getDurationSec() >= minSec; }
            @Override
            public String describeFailure(AudioChunk c) {
                return "청크 길이 부족: %.2fs < %.2fs".formatted(c.getDurationSec(), minSec);
            }
        };
    }

    static Specification<AudioChunk> maxSilenceRatio(double maxRatio) {
        return new Specification<>() {
            @Override
            public boolean isSatisfiedBy(AudioChunk c) { return c.getSilenceRatio() <= maxRatio; }
            @Override
            public String describeFailure(AudioChunk c) {
                return "무음 비율 초과: %.2f > %.2f".formatted(c.getSilenceRatio(), maxRatio);
            }
        };
    }

    static Specification<AudioChunk> expectedSampleRate(int expected) {
        return new Specification<>() {
            @Override
            public boolean isSatisfiedBy(AudioChunk c) { return c.getSampleRate() == expected; }
            @Override
            public String describeFailure(AudioChunk c) {
                return "샘플레이트 불일치: %d != %d".formatted(c.getSampleRate(), expected);
            }
        };
    }
}
