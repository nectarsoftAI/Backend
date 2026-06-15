package com.nectarsoft.meetai.service.stt.decorator;

import com.nectarsoft.meetai.service.stt.RawSegment;
import com.nectarsoft.meetai.service.stt.SttEngine;
import lombok.extern.slf4j.Slf4j;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** 타임스탬프 겹침/음수 보정 */
@Slf4j
public class TimestampAlignerDecorator extends SttEngineDecorator {

    public TimestampAlignerDecorator(SttEngine wrapped) {
        super(wrapped);
    }

    @Override
    public List<RawSegment> transcribe(Path audioPath) {
        List<RawSegment> segments = wrapped.transcribe(audioPath);
        List<RawSegment> aligned = new ArrayList<>();
        double cursor = 0.0;

        for (RawSegment s : segments) {
            double start = Math.max(s.getStartSec(), cursor);
            double end = Math.max(s.getEndSec(), start + 0.001);
            cursor = end;

            aligned.add(RawSegment.builder()
                    .speakerId(s.getSpeakerId())
                    .startSec(start).endSec(end)
                    .text(s.getText()).confidence(s.getConfidence())
                    .lowConfidence(s.isLowConfidence()).build());
        }
        return aligned;
    }
}
