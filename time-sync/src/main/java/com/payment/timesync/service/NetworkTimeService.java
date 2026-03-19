package com.payment.timesync.service;

import com.payment.timesync.config.ClockConfig;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import java.net.*;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Synchronizes with NTP servers to get accurate physical time.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class NetworkTimeService {

    private static final int NTP_PACKET_SIZE = 48;
    private static final long NTP_EPOCH_OFFSET = 2208988800L;

    private final ClockConfig config;
    private final AtomicLong ntpOffset = new AtomicLong(0);
    @Getter private volatile long lastSyncTime = 0;
    @Getter private volatile boolean syncSuccessful = false;
    @Getter private volatile String lastError = null;

    @PostConstruct
    public void init() {
        syncWithNtp();
    }

    @Scheduled(fixedRateString = "${time-sync.sync-interval-ms:60000}")
    public void scheduledSync() {
        syncWithNtp();
    }

   
}
