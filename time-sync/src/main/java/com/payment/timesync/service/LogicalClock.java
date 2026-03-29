package com.payment.timesync.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Logical clock implementation for event ordering.
 */
@Slf4j
@Service
public class LogicalClock {

    private final AtomicLong timestamp = new AtomicLong(0);

    public long tick() {
        return timestamp.incrementAndGet();
    }

    public long update(long receivedTimestamp) {
        long newTime;
        while (true) {
            long current = timestamp.get();
            newTime = Math.max(current, receivedTimestamp) + 1;
            if (timestamp.compareAndSet(current, newTime)) break;
        }
        return newTime;
    }

    public long getTime() {
        return timestamp.get();
    }

    public long send() {
        return tick();
    }

    public long receive(long messageTimestamp) {
        return update(messageTimestamp);
    }
}
