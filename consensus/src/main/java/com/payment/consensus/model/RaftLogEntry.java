package com.payment.consensus.model;

import com.payment.common.model.PaymentTransaction;
import lombok.*;

/**
 * Entry in the Raft replicated log.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RaftLogEntry {

    private int term;
    private int index;
    private PaymentTransaction command;
    private long timestamp;
}
