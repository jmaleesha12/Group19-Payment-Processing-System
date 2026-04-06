package com.payment.consensus.model;

import lombok.*;

/**
 * Response to log replication request.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LogReplicationResponse {

    private int term;
    private boolean success;
    private String nodeId;
    private int matchIndex;
}
