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

        long startNanos = System.nanoTime();
        try {
            List<RawSegment> segments = fallback.execute();
            logBatchOutcome(segments, (System.nanoTime() - startNanos) / 1_000_000);
            return segments;
        } catch (Exception ex) {
            throw new Exceptions.SttFailedError("모든 STT 전략 실패", ex);
        }
    }

    /**
     * 배치(업로드) 엔진 A/B 비교용 결과 로그 — Deepgram vs AssemblyAI.
     *
     * 스트리밍과 달리 배치는 "발화 → 자막" 지연이 의미가 없다(파일을 통째로 처리하므로).
     * 대신 RTF(Real-Time Factor) = 처리시간 ÷ 오디오길이 를 본다.
     * 파일마다 길이가 달라 처리시간 자체는 비교가 불가능하고, RTF로 정규화해야
     * 서로 다른 파일로 잰 결과끼리도 견줄 수 있다. RTF가 작을수록 빠르다.
     *
     * 오디오 길이는 마지막 세그먼트의 endSec으로 근사한다. 끝부분 무음은 빠지지만
     * 두 엔진이 같은 파일을 보므로 비교에는 지장이 없다.
     */
    private void logBatchOutcome(List<RawSegment> segments, long totalMs) {
        String engine = props.getStt().getEngine();
        long speakers = segments.stream().map(RawSegment::getSpeakerId).distinct().count();
        double audioSec = segments.stream().mapToDouble(RawSegment::getEndSec).max().orElse(0);
        String rtf = audioSec > 0 ? String.format("%.3f", (totalMs / 1000.0) / audioSec) : "n/a";

        log.info("[AB배치] engine={} totalMs={} audioSec={} rtf={} segments={} speakers={}",
                engine, totalMs, String.format("%.1f", audioSec), rtf, segments.size(), speakers);

        // 화자가 안 나뉘면 원인 후보를 함께 남긴다 — 엔진 미지원 / 폴백 / 실제 1명 구분용
        if (speakers <= 1) {
            log.warn("[STT] 화자 분리 결과 없음 — engine={}, 세그먼트={}, 화자={}명. "
                            + "엔진이 화자분리를 지원하는지(openai_whisper/gpt4o는 미지원), "
                            + "위에 '폴백 체인 전략 1 실패' 로그가 있는지 확인할 것",
                    engine, segments.size(), speakers);
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
