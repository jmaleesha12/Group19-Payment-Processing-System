package com.payment.config;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;

import java.util.TimeZone;

/**
 * Timezone configuration for Sri Lanka (Asia/Colombo, UTC+5:30)
 */
@Slf4j
@Configuration
public class TimezoneConfig {

    private static final String SRI_LANKA_TIMEZONE = "Asia/Colombo";

    @PostConstruct
    public void init() {
        TimeZone.setDefault(TimeZone.getTimeZone(SRI_LANKA_TIMEZONE));
        log.info("Application timezone set to: {} (UTC+5:30)", SRI_LANKA_TIMEZONE);
    }
}
