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

     public void syncWithNtp() {
        try {
            long offset = calculateNtpOffset();
            ntpOffset.set(offset);
            lastSyncTime = System.currentTimeMillis();
            syncSuccessful = true;
            lastError = null;
            log.info("NTP sync successful. Offset: {} ms", offset);
        } catch (Exception e) {
            syncSuccessful = false;
            lastError = e.getMessage();
            log.error("NTP sync failed: {}", e.getMessage());
        }
    }

      private long calculateNtpOffset() throws Exception {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(config.getNtpTimeout());
            InetAddress address = InetAddress.getByName(config.getNtpServer());

            byte[] ntpRequest = createNtpRequestPacket();
            long t1 = System.currentTimeMillis();

            DatagramPacket requestPacket = new DatagramPacket(ntpRequest, ntpRequest.length, address, config.getNtpPort());
            socket.send(requestPacket);

            byte[] ntpResponse = new byte[NTP_PACKET_SIZE];
            DatagramPacket responsePacket = new DatagramPacket(ntpResponse, ntpResponse.length);
            socket.receive(responsePacket);

            long t4 = System.currentTimeMillis();
            long t2 = extractNtpTimestamp(ntpResponse, 32);
            long t3 = extractNtpTimestamp(ntpResponse, 40);

            return ((t2 - t1) + (t3 - t4)) / 2;
        }
    }

    private byte[] createNtpRequestPacket() {
        byte[] packet = new byte[NTP_PACKET_SIZE];
        packet[0] = 0x1B;
        return packet;
    }

     private long extractNtpTimestamp(byte[] packet, int offset) {
        long seconds = ((long)(packet[offset] & 0xFF) << 24) | ((long)(packet[offset+1] & 0xFF) << 16) |
                       ((long)(packet[offset+2] & 0xFF) << 8) | ((long)(packet[offset+3] & 0xFF));
        long fraction = ((long)(packet[offset+4] & 0xFF) << 24) | ((long)(packet[offset+5] & 0xFF) << 16) |
                        ((long)(packet[offset+6] & 0xFF) << 8) | ((long)(packet[offset+7] & 0xFF));
        long unixSeconds = seconds - NTP_EPOCH_OFFSET;
        long unixMillis = (fraction * 1000) / 0x100000000L;
        return (unixSeconds * 1000) + unixMillis;
    }

    public long getOffset() {
        return ntpOffset.get();
    }

    public long getCorrectedTime() {
        return System.currentTimeMillis() + ntpOffset.get();
    }
   
}
