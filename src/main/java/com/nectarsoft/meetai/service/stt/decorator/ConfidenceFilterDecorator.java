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
        return segments.stream()
                .map(s -> s.getConfidence() < threshold
                        ? RawSegment.builder()
                            .speakerId(s.getSpeakerId())
                            .startSec(s.getStartSec()).endSec(s.getEndSec())
                            .text(s.getText()).confidence(s.getConfidence())
                            .lowConfidence(true).build()
                        : s)
                .toList();
    }
}
