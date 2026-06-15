package com.nectarsoft.meetai.service.stt;

import com.nectarsoft.meetai.config.MeetAiProperties;
import com.nectarsoft.meetai.core.exception.Exceptions;
import com.nectarsoft.meetai.core.retry.FallbackChain;
import com.nectarsoft.meetai.core.retry.RetryHelper;
import com.nectarsoft.meetai.service.stt.decorator.ConfidenceFilterDecorator;
import com.nectarsoft.meetai.service.stt.decorator.SpeakerValidatorDecorator;
import com.nectarsoft.meetai.service.stt.decorator.TimestampAlignerDecorator;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.nio.file.Path;
import java.util.List;

@Slf4j
@Service
public class SttService {

    private final MeetAiProperties props;

    public SttService(MeetAiProperties props) {
        this.props = props;
    }

    public List<RawSegment> process(Path audioPath) {
        SttEngine engine = buildEngine();
        MeetAiProperties.Stt cfg = props.getStt();

        FallbackChain<List<RawSegment>> fallback = new FallbackChain<>(
                () -> RetryHelper.retry(
                        () -> engine.transcribe(audioPath),
                        cfg.getMaxRetries(), cfg.getRetryBaseDelayMs(), cfg.getRetryMaxDelayMs()),
                () -> {
                    log.warn("[STT] 화자 분리 실패 → 단일 화자 모드 (EX-006)");
                    return RetryHelper.retry(
                            () -> engine.transcribeSingleSpeaker(audioPath),
                            cfg.getMaxRetries(), cfg.getRetryBaseDelayMs(), cfg.getRetryMaxDelayMs());
                }
        );

        try {
            return fallback.execute();
        } catch (Exception ex) {
            throw new Exceptions.SttFailedError("모든 STT 전략 실패", ex);
        }
    }

    public String engineName() {
        return props.getStt().getEngine();
    }

    private SttEngine buildEngine() {
        SttEngine base = createBase();
        return new TimestampAlignerDecorator(
                new SpeakerValidatorDecorator(
                        new ConfidenceFilterDecorator(base, props)));
    }

    private SttEngine createBase() {
        String engine = props.getStt().getEngine().toLowerCase();
        log.info("[STT] 엔진 선택: {}", engine);
        return switch (engine) {
            case "openai_whisper" -> new OpenAiWhisperEngine(props);
            default -> throw new IllegalArgumentException(
                    "알 수 없는 STT 엔진: '" + engine + "' | 허용값: openai_whisper, wav2vec2");
        };
    }
}
