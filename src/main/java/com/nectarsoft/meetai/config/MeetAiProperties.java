package com.nectarsoft.meetai.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

@Data
@Validated
@ConfigurationProperties(prefix = "meetai")
public class MeetAiProperties {

    private Storage storage = new Storage();
    private Audio audio = new Audio();
    private Stt stt = new Stt();
    private Openai openai = new Openai();
    private AssemblyAi assemblyai = new AssemblyAi();
    private Speechmatics speechmatics = new Speechmatics();
    private Diarization diarization = new Diarization();
    private Llm llm = new Llm();
    private LiveKit livekit = new LiveKit();

    @Data
    public static class Llm {
        private String url = "https://llm-production-6c26.up.railway.app";
    }

    @Data
    public static class Storage {
        private String uploadDir = "uploads";
        private String tempDir = "temp";
    }

    @Data
    public static class Audio {
        private int sampleRate = 16000;
        private int channels = 1;
        private String format = "wav";
        private int maxSizeMb = 100;
        private double minDurationSec = 1.0;
        private double maxDurationSec = 7200.0;
        private double silenceThresholdRatio = 0.7;
        private String supportedFormats = "wav,mp3,m4a,ogg,flac,webm";

        public List<String> getSupportedFormatList() {
            return List.of(supportedFormats.split(","));
        }
    }

    @Data
    public static class Stt {
        /** openai_whisper | wav2vec2 */
        private String engine = "openai_whisper";
        private double confidenceThreshold = 0.8;
        private int maxRetries = 3;
        private long retryBaseDelayMs = 1000L;
        private long retryMaxDelayMs = 8000L;
        private boolean polishEnabled = false;
        private String polishModel = "gpt-4o-mini";
    }

    @Data
    public static class Openai {
        private String apiKey = "";
        private String whisperModel = "whisper-1";
        private String whisperLanguage = "ko";
        // Realtime API 실시간 자막용 (gpt-4o-transcribe | gpt-4o-mini-transcribe)
        private String realtimeModel = "gpt-4o-transcribe";
        // 실시간(말하는 중) 자막 — 문제 시 OPENAI_REALTIME_ENABLED=false로 Whisper 배치 폴백
        private boolean realtimeEnabled = true;
    }

    @Data
    public static class AssemblyAi {
        private String apiKey = "";
        private String languageCode = "ko";
        private int speakersExpected = 2;
    }

    @Data
    public static class Speechmatics {
        // Railway 환경변수 SPEECHMATICS_KEY 로 주입
        private String apiKey = "";
        // 리전별 실시간 엔드포인트 (eu2 | neu | us). 한국은 us가 지연이 낮은 편
        private String url = "wss://eu2.rt.speechmatics.com/v2";
        private String language = "ko";
        // true일 때 실시간 녹음이 OpenAI 롤링 배치 대신 Speechmatics 스트리밍을 사용
        private boolean enabled = false;
        // 확정 자막(AddTranscript) 지연 상한(초). 낮을수록 자막이 빨리 뜨지만 문장이 잘게 쪼개짐
        private double maxDelaySec = 2.0;
    }

    @Data
    public static class Diarization {
        private int minSpeakers = 1;
        private int maxSpeakers = 10;
    }

    @Data
    public static class LiveKit {
        private String apiKey = "";
        private String apiSecret = "";
        private String url = "";
    }
}
