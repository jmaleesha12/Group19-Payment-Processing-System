package com.payment.timesync.controller;

import com.payment.common.model.HybridTimestamp;
import com.payment.timesync.config.ClockConfig;
import com.payment.timesync.service.LogicalClock;
import com.payment.timesync.service.NetworkTimeService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import java.util.HashMap;
import java.util.Map;

/**
 * REST endpoints for time synchronization operations.
 */
@RestController
@RequiredArgsConstructor
public class ClockController {

    
}
