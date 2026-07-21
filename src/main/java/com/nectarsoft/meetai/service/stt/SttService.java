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
            List<RawSegment> segments = fallback.execute();
            logSpeakerOutcome(segments);
            return segments;
        } catch (Exception ex) {
            throw new Exceptions.SttFailedError("모든 STT 전략 실패", ex);
        }
    }

    /**
     * 화자 분리 결과를 한 줄로 남긴다 — 업로드 회의록에 화자가 안 나뉠 때
     * 원인이 (1) 엔진 미지원 (2) 폴백으로 단일화자 (3) 실제로 1명 중 무엇인지 로그만으로 구분하기 위함.
     */
    private void logSpeakerOutcome(List<RawSegment> segments) {
        long speakers = segments.stream().map(RawSegment::getSpeakerId).distinct().count();
        if (speakers <= 1) {
            log.warn("[STT] 화자 분리 결과 없음 — engine={}, 세그먼트={}, 화자={}명. "
                            + "엔진이 화자분리를 지원하는지(openai_whisper/gpt4o는 미지원), "
                            + "위에 '폴백 체인 전략 1 실패' 로그가 있는지 확인할 것",
                    props.getStt().getEngine(), segments.size(), speakers);
        } else {
            log.info("[STT] 화자 분리 완료 — engine={}, 세그먼트={}, 화자={}명",
                    props.getStt().getEngine(), segments.size(), speakers);
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
        // 주의: openai_whisper / gpt4o는 화자 분리를 하지 않는다(전 세그먼트 SPEAKER_00).
        // 업로드 회의록에 화자가 필요하면 deepgram 또는 assemblyai를 써야 한다
        return switch (engine) {
            case "openai_whisper" -> new OpenAiWhisperEngine(props);
            case "gpt4o" -> new OpenAiGpt4oEngine(props);
            case "assemblyai" -> new AssemblyAiEngine(props);
            case "deepgram" -> new DeepgramBatchEngine(props);
            default -> throw new IllegalArgumentException(
                    "알 수 없는 STT 엔진: '" + engine
                            + "' | 허용값: openai_whisper, gpt4o, assemblyai, deepgram");
        };
    }
}
