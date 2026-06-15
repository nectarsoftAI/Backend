package com.nectarsoft.meetai.service.stt.decorator;

import com.nectarsoft.meetai.service.stt.RawSegment;
import com.nectarsoft.meetai.service.stt.SttEngine;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.List;

/** 화자 ID 누락 시 기본값 보정 */
@Slf4j
public class SpeakerValidatorDecorator extends SttEngineDecorator {

    public SpeakerValidatorDecorator(SttEngine wrapped) {
        super(wrapped);
    }

    @Override
    public List<RawSegment> transcribe(Path audioPath) {
        List<RawSegment> segments = wrapped.transcribe(audioPath);
        return segments.stream()
                .map(s -> (s.getSpeakerId() == null || s.getSpeakerId().isBlank())
                        ? RawSegment.builder()
                            .speakerId("SPEAKER_00")
                            .startSec(s.getStartSec()).endSec(s.getEndSec())
                            .text(s.getText()).confidence(s.getConfidence())
                            .lowConfidence(s.isLowConfidence()).build()
                        : s)
                .toList();
    }
}
