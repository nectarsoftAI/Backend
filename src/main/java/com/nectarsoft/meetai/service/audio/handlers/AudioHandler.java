package com.nectarsoft.meetai.service.audio.handlers;

import com.nectarsoft.meetai.service.audio.AudioContext;

/** Chain of Responsibility — 오디오 전처리 핸들러 베이스 */
public abstract class AudioHandler {

    protected AudioHandler next;

    public AudioHandler setNext(AudioHandler next) {
        this.next = next;
        return next;
    }

    public abstract AudioContext handle(AudioContext ctx);

    protected AudioContext passToNext(AudioContext ctx) {
        if (next != null) return next.handle(ctx);
        return ctx;
    }
}
