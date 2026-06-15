package com.nectarsoft.meetai.service.audio;

import com.nectarsoft.meetai.config.MeetAiProperties;
import com.nectarsoft.meetai.service.audio.handlers.*;
import com.nectarsoft.meetai.storage.FileStorage;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AudioService {

    private final MeetAiProperties props;
    private final FileStorage fileStorage;

    private AudioHandler fullChain;       // wav2vec2용
    private AudioHandler formatOnlyChain; // openai_whisper용

    @PostConstruct
    void init() {
        // ── Chain of Responsibility 조립 ─────────────────────────────
        FormatValidationHandler format = new FormatValidationHandler(props);
        SilenceDetectionHandler silence = new SilenceDetectionHandler(props);
        NoiseFilterHandler noise = new NoiseFilterHandler();
        NormalizationHandler normalize = new NormalizationHandler(props);

        // wav2vec2: 전체 체인
        format.setNext(silence).setNext(noise).setNext(normalize);
        fullChain = format;

        // openai_whisper: 포맷 검증만
        formatOnlyChain = new FormatValidationHandler(props);
    }

    public Path saveUpload(MultipartFile file) throws IOException {
        String filename = UUID.randomUUID() + "_" + file.getOriginalFilename();
        return fileStorage.saveUpload(filename, file);
    }

    public AudioContext preprocess(Path sourcePath) {
        String engine = props.getStt().getEngine().toLowerCase();
        AudioContext ctx = AudioContext.builder()
                .originalPath(sourcePath)
                .workingPath(sourcePath)
                .build();

        return switch (engine) {
            case "openai_whisper" -> formatOnlyChain.handle(ctx);
            default -> {
                // wav2vec2: 전체 체인 (16kHz WAV 변환)
                try {
                    Path working = fileStorage.saveTempCopy(sourcePath);
                    ctx.setWorkingPath(working);
                    yield fullChain.handle(ctx);
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            }
        };
    }
}
