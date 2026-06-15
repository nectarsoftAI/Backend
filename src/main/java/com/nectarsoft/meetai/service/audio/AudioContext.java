package com.nectarsoft.meetai.service.audio;

import lombok.Builder;
import lombok.Data;

import java.nio.file.Path;

@Data
@Builder
public class AudioContext {
    private Path originalPath;
    private Path workingPath;
    @Builder.Default
    private int sampleRate = 16000;
    @Builder.Default
    private double durationSec = 0.0;
    @Builder.Default
    private boolean processed = false;
}
