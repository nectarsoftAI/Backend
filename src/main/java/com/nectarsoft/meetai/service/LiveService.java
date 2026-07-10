package com.nectarsoft.meetai.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.nectarsoft.meetai.config.MeetAiProperties;
import com.nectarsoft.meetai.core.exception.Exceptions;
import com.nectarsoft.meetai.core.websocket.OnlineRoomManager;
import com.nectarsoft.meetai.model.*;
import com.nectarsoft.meetai.repository.MeetingRepository;
import com.nectarsoft.meetai.repository.SttResultRepository;
import com.nectarsoft.meetai.repository.TranscriptRepository;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.stereotype.Service;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 실시간 녹음 세션 (한 마이크, 다중 화자)
 * - 오디오를 파일로 누적하면서 주기적으로 OpenAI gpt-4o-transcribe-diarize(배치)로 변환해
 *   화자 라벨(SPEAKER_A/B) 자막을 준실시간 브로드캐스트 (~15초 지연)
 *   ※ Realtime WS용 diarize는 OpenAI가 아직 미개방이라 빠른 배치를 롤링으로 사용
 * - 종료 시 전체 오디오를 최종 변환해 회의록 transcripts 저장
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class LiveService {

    private static final int SAMPLE_RATE = 16000;
    // 짧을수록 자막 지연이 줄지만 전체 오디오를 매번 재처리하므로 API 비용 증가
    private static final int ROLLING_INTERVAL_SEC = 6;
    private static final String TRANSCRIBE_URL = "https://api.openai.com/v1/audio/transcriptions";
    private static final String DIARIZE_MODEL = "gpt-4o-transcribe-diarize";

    private final MeetAiProperties props;
    private final MeetingRepository meetingRepo;
    private final SttResultRepository sttResultRepo;
    private final TranscriptRepository transcriptRepo;
    private final OnlineRoomManager roomManager;
    private final ObjectMapper objectMapper;

    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)
            .readTimeout(120, TimeUnit.SECONDS)
            .build();

    private final Map<String, Recording> recordings = new ConcurrentHashMap<>();

    private final ScheduledExecutorService diarizeExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "live-diarize");
        t.setDaemon(true);
        return t;
    });

    private record DiarizedSegment(String speaker, String text, double start, double end) {}

    private static class Recording {
        OutputStream out;
        Path path;
        boolean pcm;
        long bytes;
        long lastDiarizedBytes;
        double lastBroadcastEnd;
        String ownerId;
    }

    @PostConstruct
    void startRollingDiarize() {
        diarizeExecutor.scheduleWithFixedDelay(this::rollingPass,
                ROLLING_INTERVAL_SEC, ROLLING_INTERVAL_SEC, TimeUnit.SECONDS);
    }

    public Meeting createSession(String title, UUID profileId) {
        Meeting meeting = Meeting.builder()
                .userId(profileId)
                .title(title)
                .meetingType(MeetingType.REALTIME)
                .status(MeetingStatus.LIVE)
                .meetingDate(OffsetDateTime.now())
                .build();
        meetingRepo.save(meeting);
        log.info("[Live] 세션 생성 — meetingId={}", meeting.getMeetingId());
        return meeting;
    }

    /** WS로 수신한 오디오 청크 누적 (webm은 그대로, raw PCM은 WAV 래핑해 사용) */
    public void appendAudio(String meetingId, byte[] data) {
        try {
            Recording r = recordings.computeIfAbsent(meetingId, k -> openRecording(k, data));
            synchronized (r) {
                r.out.write(data);
                r.bytes += data.length;
            }
        } catch (Exception e) {
            log.warn("[Live] 녹음 누적 실패 — meetingId={}: {}", meetingId, e.getMessage());
        }
    }

    public void endSession(String meetingId) {
        UUID mid = UUID.fromString(meetingId);
        Meeting meeting = meetingRepo.findById(mid)
                .orElseThrow(() -> new Exceptions.SessionNotFoundError(meetingId));

        // COMPLETED = 이미 종료 / PROCESSING = 최종 변환 중 — 중복 종료 방지
        if (meeting.getStatus() == MeetingStatus.COMPLETED
                || meeting.getStatus() == MeetingStatus.PROCESSING) return;

        if (meeting.getMeetingDate() != null) {
            meeting.setDurationSeconds(
                    (int) ChronoUnit.SECONDS.between(meeting.getMeetingDate(), OffsetDateTime.now()));
        }

        Path recording = finishRecording(meetingId);
        if (recording != null) {
            meeting.setStatus(MeetingStatus.PROCESSING);
            meetingRepo.save(meeting);
            diarizeExecutor.submit(() -> finalizeTranscripts(mid, recording));
            log.info("[Live] 세션 종료 — 최종 화자 분리 변환 시작, meetingId={}", meetingId);
            return;
        }

        meeting.setStatus(MeetingStatus.COMPLETED);
        meetingRepo.save(meeting);
        log.info("[Live] 세션 종료 완료 — meetingId={}", meetingId);
    }

    // ── 준실시간: 주기적으로 누적 오디오를 화자 분리해 새 구간만 브로드캐스트 ──

    private void rollingPass() {
        for (Map.Entry<String, Recording> entry : recordings.entrySet()) {
            String meetingId = entry.getKey();
            Recording r = entry.getValue();
            try {
                byte[] snapshot;
                synchronized (r) {
                    if (r.bytes < SAMPLE_RATE || r.bytes == r.lastDiarizedBytes) continue;
                    r.out.flush();
                    r.lastDiarizedBytes = r.bytes;
                }
                snapshot = snapshotAudio(r);
                if (snapshot == null) continue;

                List<DiarizedSegment> segments = diarize(snapshot, r.pcm ? "audio.wav" : "audio.webm");
                broadcastNewSegments(meetingId, r, segments);
            } catch (Exception e) {
                log.warn("[Live] 롤링 화자 분리 실패 — meetingId={}: {}", meetingId, e.getMessage());
            }
        }
    }

    private void broadcastNewSegments(String meetingId, Recording r, List<DiarizedSegment> segments) {
        // 마지막 세그먼트는 발화가 진행 중일 수 있으므로 보류 (다음 패스나 최종 변환에서 확정)
        List<DiarizedSegment> stable = segments.size() > 1
                ? segments.subList(0, segments.size() - 1) : List.of();
        for (DiarizedSegment seg : stable) {
            if (seg.start() < r.lastBroadcastEnd - 0.3) continue; // 이미 방송된 구간
            String text = seg.text().strip();
            if (text.isEmpty()) continue;
            String label = "SPEAKER_" + seg.speaker();
            try {
                roomManager.broadcast(meetingId, objectMapper.writeValueAsString(Map.of(
                        "type", "transcript",
                        "profileId", r.ownerId,
                        "speakerLabel", label,
                        "speakerDisplay", label,
                        "text", text,
                        "startSec", seg.start(),
                        "endSec", seg.end(),
                        "isFinal", true
                )));
                r.lastBroadcastEnd = Math.max(r.lastBroadcastEnd, seg.end());
                log.info("[Live] 화자 자막 — [{}] \"{}\"", label, text);
            } catch (Exception e) {
                log.debug("[Live] 자막 브로드캐스트 실패: {}", e.getMessage());
            }
        }
    }

    // ── 종료: 전체 오디오 최종 변환 → 회의록 저장 ──────────────────────

    private void finalizeTranscripts(UUID mid, Path audio) {
        Meeting meeting = meetingRepo.findById(mid).orElse(null);
        if (meeting == null) return;
        try {
            List<DiarizedSegment> segments = diarize(Files.readAllBytes(audio), audio.getFileName().toString());

            SttResult sttResult = SttResult.builder()
                    .meeting(meeting)
                    .processingStatus(SttProcessingStatus.COMPLETED)
                    .processedAt(OffsetDateTime.now())
                    .build();
            sttResultRepo.save(sttResult);

            transcriptRepo.deleteAll(transcriptRepo.findByMeetingMeetingIdOrderByStartSecAsc(mid));
            List<Transcript> transcripts = new ArrayList<>();
            for (DiarizedSegment seg : segments) {
                String text = seg.text().strip();
                if (text.isEmpty()) continue;
                String label = "SPEAKER_" + seg.speaker();
                transcripts.add(Transcript.builder()
                        .meeting(meeting)
                        .sttResult(sttResult)
                        .speakerLabel(label)
                        .speakerDisplay(label)
                        .startSec(seg.start())
                        .endSec(seg.end())
                        .content(text)
                        .build());
            }
            transcriptRepo.saveAll(transcripts);

            long speakers = segments.stream().map(DiarizedSegment::speaker).distinct().count();
            log.info("[Live] 최종 화자 분리 완료 — meetingId={}, segments={}, 화자 {}명",
                    mid, transcripts.size(), speakers);
        } catch (Exception e) {
            log.error("[Live] 최종 화자 분리 실패 — meetingId={}: {}", mid, e.getMessage());
        } finally {
            meeting.setStatus(MeetingStatus.COMPLETED);
            meetingRepo.save(meeting);
        }
    }

    // ── OpenAI gpt-4o-transcribe-diarize 호출 ─────────────────────────

    private List<DiarizedSegment> diarize(byte[] audio, String filename) throws IOException {
        RequestBody body = new MultipartBody.Builder()
                .setType(MultipartBody.FORM)
                .addFormDataPart("file", filename,
                        RequestBody.create(audio, MediaType.parse("application/octet-stream")))
                .addFormDataPart("model", DIARIZE_MODEL)
                .addFormDataPart("response_format", "diarized_json")
                .build();
        Request request = new Request.Builder()
                .url(TRANSCRIBE_URL)
                .header("Authorization", "Bearer " + props.getOpenai().getApiKey())
                .post(body)
                .build();

        try (Response response = http.newCall(request).execute()) {
            String raw = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                throw new IOException("diarize 요청 실패 " + response.code() + ": " + raw);
            }
            JsonNode root = objectMapper.readTree(raw);
            List<DiarizedSegment> result = new ArrayList<>();
            for (JsonNode s : root.path("segments")) {
                result.add(new DiarizedSegment(
                        s.path("speaker").asText("A"),
                        s.path("text").asText(""),
                        s.path("start").asDouble(0),
                        s.path("end").asDouble(0)));
            }
            return result;
        }
    }

    // ── 녹음 파일 관리 ────────────────────────────────────────────────

    private Recording openRecording(String meetingId, byte[] first) {
        try {
            Path dir = Path.of(props.getStorage().getUploadDir(), "live");
            Files.createDirectories(dir);
            Recording r = new Recording();
            r.pcm = !isContainer(first);
            r.path = dir.resolve(meetingId + (r.pcm ? ".pcm" : ".webm"));
            r.out = new BufferedOutputStream(Files.newOutputStream(
                    r.path, StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING));
            r.ownerId = meetingRepo.findById(UUID.fromString(meetingId))
                    .map(m -> m.getUserId().toString()).orElse(meetingId);
            log.info("[Live] 녹음 시작 — {} (pcm={})", r.path.getFileName(), r.pcm);
            return r;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** 진행 중 녹음의 디코딩 가능한 스냅샷 (PCM → WAV 래핑, webm → 마지막 클러스터 경계까지) */
    private byte[] snapshotAudio(Recording r) throws IOException {
        byte[] raw = Files.readAllBytes(r.path);
        if (r.pcm) return pcmToWav(raw);

        int first = indexOfCluster(raw, 0);
        if (first < 0) return null;
        int last = lastIndexOfCluster(raw);
        if (last <= first) return null; // 완결된 클러스터가 아직 없음
        byte[] cut = new byte[last];
        System.arraycopy(raw, 0, cut, 0, last);
        return cut;
    }

    /** 녹음 확정 — 최종 파일 경로 반환 (raw PCM은 WAV로 변환). 유효 녹음 없으면 null */
    private Path finishRecording(String meetingId) {
        Recording r = recordings.remove(meetingId);
        if (r == null) return null;
        try {
            synchronized (r) { r.out.close(); }
            if (r.bytes < SAMPLE_RATE) { // 0.5초 미만이면 의미 없는 녹음
                Files.deleteIfExists(r.path);
                return null;
            }
            if (!r.pcm) return r.path;

            Path wav = r.path.resolveSibling(meetingId + ".wav");
            Files.write(wav, pcmToWav(Files.readAllBytes(r.path)));
            Files.deleteIfExists(r.path);
            log.info("[Live] 녹음 확정 — {} ({}KB)", wav.getFileName(), r.bytes / 1024);
            return wav;
        } catch (IOException e) {
            log.warn("[Live] 녹음 확정 실패 — meetingId={}: {}", meetingId, e.getMessage());
            return null;
        }
    }

    private static boolean isContainer(byte[] d) {
        if (d == null || d.length < 4) return false;
        boolean webm = (d[0] & 0xFF) == 0x1A && (d[1] & 0xFF) == 0x45
                && (d[2] & 0xFF) == 0xDF && (d[3] & 0xFF) == 0xA3;
        boolean ogg = d[0] == 'O' && d[1] == 'g' && d[2] == 'g' && d[3] == 'S';
        return webm || ogg;
    }

    private static int indexOfCluster(byte[] d, int from) {
        for (int i = from; i + 3 < d.length; i++) {
            if ((d[i] & 0xFF) == 0x1F && (d[i + 1] & 0xFF) == 0x43
                    && (d[i + 2] & 0xFF) == 0xB6 && (d[i + 3] & 0xFF) == 0x75) return i;
        }
        return -1;
    }

    private static int lastIndexOfCluster(byte[] d) {
        for (int i = d.length - 4; i >= 0; i--) {
            if ((d[i] & 0xFF) == 0x1F && (d[i + 1] & 0xFF) == 0x43
                    && (d[i + 2] & 0xFF) == 0xB6 && (d[i + 3] & 0xFF) == 0x75) return i;
        }
        return -1;
    }

    /** raw PCM(16kHz/16bit/mono) 바이트에 WAV 헤더를 붙여 반환 */
    private static byte[] pcmToWav(byte[] pcm) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream(pcm.length + 44);
        DataOutputStream dos = new DataOutputStream(out);
        int byteRate = SAMPLE_RATE * 2;
        dos.writeBytes("RIFF"); writeLE32(dos, 36 + pcm.length);
        dos.writeBytes("WAVE");
        dos.writeBytes("fmt "); writeLE32(dos, 16);
        writeLE16(dos, (short) 1);
        writeLE16(dos, (short) 1);
        writeLE32(dos, SAMPLE_RATE);
        writeLE32(dos, byteRate);
        writeLE16(dos, (short) 2);
        writeLE16(dos, (short) 16);
        dos.writeBytes("data"); writeLE32(dos, pcm.length);
        dos.write(pcm);
        return out.toByteArray();
    }

    private static void writeLE32(DataOutputStream d, int v) throws IOException {
        d.write(v & 0xFF); d.write((v >> 8) & 0xFF);
        d.write((v >> 16) & 0xFF); d.write((v >> 24) & 0xFF);
    }

    private static void writeLE16(DataOutputStream d, short v) throws IOException {
        d.write(v & 0xFF); d.write((v >> 8) & 0xFF);
    }
}
