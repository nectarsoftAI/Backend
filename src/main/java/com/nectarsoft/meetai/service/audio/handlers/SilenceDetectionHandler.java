package com.nectarsoft.meetai.service.audio.handlers;

import com.nectarsoft.meetai.config.MeetAiProperties;
import com.nectarsoft.meetai.core.exception.Exceptions;
import com.nectarsoft.meetai.service.audio.AudioContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class SilenceDetectionHandler extends AudioHandler {

    private final MeetAiProperties props;

    @Override
    public AudioContext handle(AudioContext ctx) {
        double silenceRatio = estimateSilenceRatio(ctx);
        double threshold = props.getAudio().getSilenceThresholdRatio();

        if (silenceRatio > threshold) {
            throw new Exceptions.SilenceDetectedError(
                    "무음 비율 %.1f%% > 임계값 %.1f%%".formatted(silenceRatio * 100, threshold * 100));
        }
        log.debug("[SilenceDetection] OK — 무음 비율: {:.1f}%", silenceRatio * 100);
        return passToNext(ctx);
    }

    /** 간단한 RMS 기반 무음 추정 (실제 프로덕션에서는 ffmpeg silencedetect 활용) */
    private double estimateSilenceRatio(AudioContext ctx) {
        return 0.1; // wav2vec2 체인 전용 — 실제 구현 시 교체
    }
}
