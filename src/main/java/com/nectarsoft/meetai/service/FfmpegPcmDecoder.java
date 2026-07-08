package com.nectarsoft.meetai.service;

import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

/**
 * ffmpeg 서브프로세스로 webm/ogg 오디오를 16kHz mono s16le PCM으로 디코딩.
 * ffmpeg 미설치 환경(로컬 개발 등)에서는 null을 반환해 호출부가 기존 경로로 폴백하게 한다.
 */
@Slf4j
public final class FfmpegPcmDecoder {

    private static volatile Boolean available;

    private FfmpegPcmDecoder() {}

    public static boolean isAvailable() {
        Boolean a = available;
        if (a == null) {
            synchronized (FfmpegPcmDecoder.class) {
                if (available == null) {
                    boolean ok;
                    try {
                        Process p = new ProcessBuilder("ffmpeg", "-version")
                                .redirectErrorStream(true).start();
                        p.getInputStream().readAllBytes();
                        ok = p.waitFor(5, TimeUnit.SECONDS) && p.exitValue() == 0;
                    } catch (Exception e) {
                        ok = false;
                    }
                    available = ok;
                    if (!ok) log.warn("[FFmpeg] 사용 불가 — VAD 생략, webm 직접 전송으로 폴백");
                    else log.info("[FFmpeg] 사용 가능 — VAD 파이프라인 활성화");
                }
                a = available;
            }
        }
        return a;
    }

    /** webm/ogg → 16kHz mono 16-bit PCM. 실패 시 null (호출부 폴백) */
    public static byte[] decode(byte[] audio) {
        if (!isAvailable()) return null;
        try {
            Process p = new ProcessBuilder(
                    "ffmpeg", "-hide_banner", "-loglevel", "error",
                    "-i", "pipe:0", "-f", "s16le", "-ac", "1", "-ar", "16000", "pipe:1")
                    .start();
            Thread writer = new Thread(() -> {
                try (OutputStream in = p.getOutputStream()) {
                    in.write(audio);
                } catch (IOException ignored) {
                    // ffmpeg가 먼저 종료하면 broken pipe — 정상 케이스
                }
            }, "ffmpeg-stdin");
            writer.setDaemon(true);
            writer.start();

            byte[] pcm = p.getInputStream().readAllBytes();
            String err = new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
            if (!p.waitFor(15, TimeUnit.SECONDS)) {
                p.destroyForcibly();
                log.warn("[FFmpeg] 디코딩 타임아웃 ({}바이트)", audio.length);
                return null;
            }
            if (p.exitValue() != 0 || pcm.length == 0) {
                log.warn("[FFmpeg] 디코딩 실패 (exit={}): {}", p.exitValue(), err.strip());
                return null;
            }
            return pcm;
        } catch (Exception e) {
            log.warn("[FFmpeg] 실행 오류: {}", e.getMessage());
            return null;
        }
    }
}
