package com.nectarsoft.meetai.core;

import org.springframework.stereotype.Component;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

@Component
public class UserIdCounter {

    private final AtomicLong counter = new AtomicLong(1);

    public UUID next() {
        long n = counter.getAndIncrement();
        return UUID.fromString(String.format("00000000-0000-0000-0000-%012d", n));
    }
}
