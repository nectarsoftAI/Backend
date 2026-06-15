package com.nectarsoft.meetai.service.stt;

import java.nio.file.Path;
import java.util.List;

/** STT 엔진 전략 인터페이스 — Strategy + Decorator 패턴 베이스 */
public interface SttEngine {

    List<RawSegment> transcribe(Path audioPath);

    default List<RawSegment> transcribeSingleSpeaker(Path audioPath) {
        return transcribe(audioPath);
    }
}
