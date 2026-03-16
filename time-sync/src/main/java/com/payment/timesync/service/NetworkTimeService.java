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

 
}
