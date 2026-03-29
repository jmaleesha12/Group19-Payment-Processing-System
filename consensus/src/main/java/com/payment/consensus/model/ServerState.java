package com.payment.consensus.model;

/**
 * Possible states of a Raft node.
 */
public enum ServerState {
    FOLLOWER,
    CANDIDATE,
    LEADER
}
