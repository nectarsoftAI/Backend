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
