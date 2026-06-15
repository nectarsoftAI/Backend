package com.nectarsoft.meetai.service.audio;

import com.nectarsoft.meetai.config.MeetAiProperties;
import com.nectarsoft.meetai.service.audio.handlers.*;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class AudioService {

    private final MeetAiProperties props;

    private AudioHandler fullChain;       // wav2vec2용
    private AudioHandler formatOnlyChain; // openai_whisper용

    private Path uploadDir;
    private Path tempDir;

    @PostConstruct
    void init() throws IOException {
        uploadDir = Path.of(props.getStorage().getUploadDir());
        tempDir = Path.of(props.getStorage().getTempDir());
        Files.createDirectories(uploadDir);
        Files.createDirectories(tempDir);

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
        Path dest = uploadDir.resolve(filename);
        file.transferTo(dest);
        return dest;
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
                Path working = tempDir.resolve(sourcePath.getFileName());
                try { Files.copy(sourcePath, working); } catch (IOException e) { throw new RuntimeException(e); }
                ctx.setWorkingPath(working);
                yield fullChain.handle(ctx);
            }
        };
    }
}
