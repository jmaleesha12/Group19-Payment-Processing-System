package com.payment.consensus.model;

import lombok.*;
import java.util.List;

/**
 * Request for log replication (also used as heartbeat).
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LogReplicationRequest {

    private int term;
    private String leaderId;
    private int prevLogIndex;
    private int prevLogTerm;
    private List<RaftLogEntry> entries;
    private int leaderCommit;
}
