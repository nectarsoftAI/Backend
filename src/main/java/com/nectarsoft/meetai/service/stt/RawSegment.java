package com.nectarsoft.meetai.service.stt;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class RawSegment {
    String speakerId;
    double startSec;
    double endSec;
    String text;
    double confidence;
    boolean lowConfidence;
}
