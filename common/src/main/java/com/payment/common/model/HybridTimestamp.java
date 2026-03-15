package com.payment.common.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Hybrid timestamp combining physical (NTP-corrected) and logical (Lamport) time.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class HybridTimestamp {

    private long physicalTime;
    private long logicalTime;

    public static HybridTimestamp now(long logicalTime) {
        return HybridTimestamp.builder()
                .physicalTime(System.currentTimeMillis())
                .logicalTime(logicalTime)
                .build();
    }

    public static HybridTimestamp nowWithOffset(long ntpOffset, long logicalTime) {
        return HybridTimestamp.builder()
                .physicalTime(System.currentTimeMillis() + ntpOffset)
                .logicalTime(logicalTime)
                .build();
    }

    public int compareTo(HybridTimestamp other) {
        if (this.physicalTime != other.physicalTime) {
            return Long.compare(this.physicalTime, other.physicalTime);
        }
        return Long.compare(this.logicalTime, other.logicalTime);
    }
}
