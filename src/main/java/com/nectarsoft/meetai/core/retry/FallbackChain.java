package com.nectarsoft.meetai.core.retry;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.concurrent.Callable;

/**
 * 순서대로 전략을 시도하고, 앞선 전략이 실패하면 다음 전략으로 폴백
 * STT: 화자분리 STT → 단일화자 STT 순서로 폴백
 */
@Slf4j
public class FallbackChain<T> {

    private final List<Callable<T>> strategies;

    @SafeVarargs
    public FallbackChain(Callable<T>... strategies) {
        this.strategies = List.of(strategies);
    }

    public T execute() {
        Exception lastException = null;
        for (int i = 0; i < strategies.size(); i++) {
            try {
                return strategies.get(i).call();
            } catch (Exception ex) {
                lastException = ex;
                log.warn("폴백 체인 전략 {} 실패: {}", i + 1, ex.getMessage());
            }
        }
        throw new RuntimeException("모든 폴백 전략 실패", lastException);
    }
}
