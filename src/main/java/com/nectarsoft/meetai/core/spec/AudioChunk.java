package com.nectarsoft.meetai.core.spec;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class AudioChunk {
    double durationSec;
    double silenceRatio;
    int sampleRate;
    byte[] data;
}
