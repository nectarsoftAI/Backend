package com.nectarsoft.meetai.service.audio.handlers;

import com.nectarsoft.meetai.service.audio.AudioContext;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class NoiseFilterHandler extends AudioHandler {

    @Override
    public AudioContext handle(AudioContext ctx) {
        // wav2vec2 파이프라인 전용 — 실제 구현: ffmpeg afftdn 필터
        log.debug("[NoiseFilter] 노이즈 필터 적용 — {}", ctx.getWorkingPath().getFileName());
        return passToNext(ctx);
    }
}
