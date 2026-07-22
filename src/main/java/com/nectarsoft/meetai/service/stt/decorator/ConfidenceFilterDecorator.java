package com.nectarsoft.meetai.service.stt.decorator;

import com.nectarsoft.meetai.config.MeetAiProperties;
import com.nectarsoft.meetai.service.stt.RawSegment;
import com.nectarsoft.meetai.service.stt.SttEngine;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.List;

/** 낮은 신뢰도 세그먼트에 플래그 설정 */
@Slf4j
public class ConfidenceFilterDecorator extends SttEngineDecorator {

    private final double threshold;

    public ConfidenceFilterDecorator(SttEngine wrapped, MeetAiProperties props) {
        super(wrapped);
        this.threshold = props.getStt().getConfidenceThreshold();
    }

    @Override
    public List<RawSegment> transcribe(Path audioPath) {
        List<RawSegment> segments = wrapped.transcribe(audioPath);
        List<RawSegment> filtered = segments.stream()
                .filter(s -> s.getConfidence() >= threshold)
                .toList();

        // 전부 걸러지면 회의록이 통째로 비어버린다(segments=0 → 요약도 불가).
        // 짧은 발화는 confidence가 낮게 나오는 일이 흔하므로, 이 경우 원본을
        // lowConfidence 플래그만 달아 유지한다.
        if (filtered.isEmpty() && !segments.isEmpty()) {
            log.warn("[STT] confidence 필터가 전체 {}개 세그먼트를 제거 — 원본 유지 (lowConfidence 플래그)",
                    segments.size());
            return segments.stream()
                    .map(s -> RawSegment.builder()
                            .speakerId(s.getSpeakerId())
                            .startSec(s.getStartSec())
                            .endSec(s.getEndSec())
                            .text(s.getText())
                            .confidence(s.getConfidence())
                            .lowConfidence(true)
                            .build())
                    .toList();
        }

        if (filtered.size() < segments.size()) {
            log.info("[STT] confidence 필터: {}/{} 세그먼트 제거 (임계값={})",
                    segments.size() - filtered.size(), segments.size(), threshold);
            // 무엇이 버려졌는지 남긴다 — 잡음이면 정상이지만 실제 발화면 회의록 유실이다.
            // 엔진마다 confidence 척도가 달라 임계값을 조정할 근거가 필요하다
            segments.stream()
                    .filter(s -> s.getConfidence() < threshold)
                    .forEach(s -> log.info("[STT] confidence 제외 — conf={} [{}~{}s] \"{}\"",
                            String.format("%.3f", s.getConfidence()),
                            String.format("%.1f", s.getStartSec()),
                            String.format("%.1f", s.getEndSec()), s.getText()));
        }
        return filtered;
    }
}
