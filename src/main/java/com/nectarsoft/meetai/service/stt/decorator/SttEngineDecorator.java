package com.nectarsoft.meetai.service.stt.decorator;

import com.nectarsoft.meetai.service.stt.SttEngine;

/** Decorator 패턴 베이스 */
public abstract class SttEngineDecorator implements SttEngine {

    protected final SttEngine wrapped;

    protected SttEngineDecorator(SttEngine wrapped) {
        this.wrapped = wrapped;
    }
}
