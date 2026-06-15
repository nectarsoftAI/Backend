package com.nectarsoft.meetai.service.audio.handlers;

import com.nectarsoft.meetai.config.MeetAiProperties;
import com.nectarsoft.meetai.service.audio.AudioContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public class NormalizationHandler extends AudioHandler {

    private final MeetAiProperties props;

    @Override
    public AudioContext handle(AudioContext ctx) {
        // wav2vec2 파이프라인 전용 — 실제 구현: ffmpeg 16kHz mono WAV 변환
        int targetRate = props.getAudio().getSampleRate();
        log.debug("[Normalization] {}Hz 정규화 — {}", targetRate, ctx.getWorkingPath().getFileName());
        ctx.setSampleRate(targetRate);
        ctx.setProcessed(true);
        return passToNext(ctx);
    }
}
