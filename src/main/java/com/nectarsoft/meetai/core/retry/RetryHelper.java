package com.nectarsoft.meetai.core.retry;

import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Callable;

/**
 * 지수 백오프 재시도 유틸리티
 * FallbackChain 과 함께 STT EX-005/006 처리에 사용
 */
@Slf4j
public final class RetryHelper {

    private RetryHelper() {}

    /**
     * @param callable    실행할 작업
     * @param maxRetries  최대 재시도 횟수
     * @param baseDelayMs 초기 대기 시간 (ms)
     * @param maxDelayMs  최대 대기 시간 (ms)
     */
    public static <T> T retry(Callable<T> callable, int maxRetries, long baseDelayMs, long maxDelayMs) {
        int attempt = 0;
        long delay = baseDelayMs;

        while (true) {
            try {
                return callable.call();
            } catch (Exception ex) {
                attempt++;
                if (attempt > maxRetries) {
                    throw new RuntimeException("최대 재시도 횟수 초과 (" + maxRetries + "회): " + ex.getMessage(), ex);
                }
                log.warn("재시도 {}/{} — {}ms 후 재시도, 오류: {}", attempt, maxRetries, delay, ex.getMessage());
                try {
                    Thread.sleep(delay);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    throw new RuntimeException("재시도 중 인터럽트", ie);
                }
                delay = Math.min(delay * 2, maxDelayMs);
            }
        }
    }
}
