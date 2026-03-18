package com.payment.consensus.model;

import lombok.*;

/**
 * Request for leader election vote.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ElectionRequest {

    private int term;
    private String candidateId;
    private int lastLogIndex;
    private int lastLogTerm;
}
