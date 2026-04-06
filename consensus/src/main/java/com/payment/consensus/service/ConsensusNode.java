package com.payment.consensus.service;

import com.payment.common.model.PaymentTransaction;
import com.payment.common.model.PaymentStatus;
import com.payment.consensus.config.RaftConfig;
import com.payment.consensus.model.*;
import jakarta.annotation.PostConstruct;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Raft consensus node implementation.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ConsensusNode {

    private final RaftConfig config;
    private final ConsensusLog consensusLog;
    private final RestTemplate restTemplate;

    @Getter private volatile ServerState state = ServerState.FOLLOWER;
    private final AtomicInteger currentTerm = new AtomicInteger(0);
    @Getter private volatile String votedFor = null;
    @Getter private volatile String leaderId = null;
    private volatile long lastHeartbeatTime;
    private volatile long electionTimeout;
    private final Random random = new Random();
    private final ConcurrentHashMap<String, Integer> nextIndex = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Integer> matchIndex = new ConcurrentHashMap<>();

    @PostConstruct
    public void init() {
        resetElectionTimeout();
        lastHeartbeatTime = System.currentTimeMillis();
    }

    private void resetElectionTimeout() {
        int range = config.getElectionTimeoutMax() - config.getElectionTimeoutMin();
        electionTimeout = config.getElectionTimeoutMin() + random.nextInt(range);
    }

    @Scheduled(fixedRate = 100)
    public void checkElectionTimeout() {
        if (state == ServerState.LEADER) return;
        if (System.currentTimeMillis() - lastHeartbeatTime >= electionTimeout) {
            startElection();
        }
    }

    @Scheduled(fixedRate = 500)
    public void sendHeartbeats() {
        if (state != ServerState.LEADER) return;
        for (String peer : config.getPeers()) sendLogReplication(peer);
    }

    private synchronized void startElection() {
        int newTerm = currentTerm.incrementAndGet();
        state = ServerState.CANDIDATE;
        votedFor = config.getNodeId();
        leaderId = null;
        resetElectionTimeout();
        lastHeartbeatTime = System.currentTimeMillis();

        int votesReceived = 1;
        int votesNeeded = (config.getPeers().size() + 1) / 2 + 1;

        ElectionRequest request = ElectionRequest.builder()
                .term(newTerm).candidateId(config.getNodeId())
                .lastLogIndex(consensusLog.getLastLogIndex()).lastLogTerm(consensusLog.getLastLogTerm()).build();

        for (String peer : config.getPeers()) {
            try {
                ResponseEntity<ElectionResponse> response = restTemplate.postForEntity(
                        peer + "/raft/vote", request, ElectionResponse.class);
                ElectionResponse voteResponse = response.getBody();
                if (voteResponse != null) {
                    if (voteResponse.getTerm() > currentTerm.get()) { stepDown(voteResponse.getTerm()); return; }
                    if (voteResponse.isVoteGranted()) votesReceived++;
                }
            } catch (Exception e) { }
        }

        if (state == ServerState.CANDIDATE && votesReceived >= votesNeeded) becomeLeader();
    }

    private void becomeLeader() {
        state = ServerState.LEADER;
        leaderId = config.getNodeId();
        int lastLogIndex = consensusLog.getLastLogIndex();
        for (String peer : config.getPeers()) {
            nextIndex.put(peer, lastLogIndex + 1);
            matchIndex.put(peer, 0);
        }
        log.info("Node {} became LEADER for term {}", config.getNodeId(), currentTerm.get());
    }

    private void stepDown(int newTerm) {
        currentTerm.set(newTerm);
        state = ServerState.FOLLOWER;
        votedFor = null;
        resetElectionTimeout();
        lastHeartbeatTime = System.currentTimeMillis();
    }

    public synchronized ElectionResponse handleVoteRequest(ElectionRequest request) {
        int term = currentTerm.get();
        if (request.getTerm() < term)
            return ElectionResponse.builder().term(term).voteGranted(false).voterId(config.getNodeId()).build();
        if (request.getTerm() > term) { stepDown(request.getTerm()); term = request.getTerm(); }

        boolean canVote = (votedFor == null || votedFor.equals(request.getCandidateId()));
        boolean logOk = (request.getLastLogTerm() > consensusLog.getLastLogTerm()) ||
                (request.getLastLogTerm() == consensusLog.getLastLogTerm() && request.getLastLogIndex() >= consensusLog.getLastLogIndex());

        boolean voteGranted = canVote && logOk;
        if (voteGranted) { votedFor = request.getCandidateId(); lastHeartbeatTime = System.currentTimeMillis(); }

        return ElectionResponse.builder().term(term).voteGranted(voteGranted).voterId(config.getNodeId()).build();
    }

    public synchronized LogReplicationResponse handleLogReplication(LogReplicationRequest request) {
        int term = currentTerm.get();
        if (request.getTerm() < term)
            return LogReplicationResponse.builder().term(term).success(false).nodeId(config.getNodeId()).matchIndex(consensusLog.getLastLogIndex()).build();
        if (request.getTerm() > term) { stepDown(request.getTerm()); term = request.getTerm(); }

        lastHeartbeatTime = System.currentTimeMillis();
        resetElectionTimeout();
        leaderId = request.getLeaderId();
        if (state == ServerState.CANDIDATE) state = ServerState.FOLLOWER;

        if (request.getPrevLogIndex() > 0 && !consensusLog.containsEntry(request.getPrevLogIndex(), request.getPrevLogTerm()))
            return LogReplicationResponse.builder().term(term).success(false).nodeId(config.getNodeId()).matchIndex(consensusLog.getLastLogIndex()).build();

        if (request.getEntries() != null && !request.getEntries().isEmpty())
            consensusLog.appendEntries(request.getPrevLogIndex(), request.getEntries());

        if (request.getLeaderCommit() > consensusLog.getCommitIndex())
            consensusLog.updateCommitIndex(request.getLeaderCommit());

        return LogReplicationResponse.builder().term(term).success(true).nodeId(config.getNodeId()).matchIndex(consensusLog.getLastLogIndex()).build();
    }

    private void sendLogReplication(String peer) {
        try {
            int nextIdx = nextIndex.getOrDefault(peer, consensusLog.getLastLogIndex() + 1);
            int prevLogIndex = nextIdx - 1;
            int prevLogTerm = consensusLog.getEntry(prevLogIndex).map(RaftLogEntry::getTerm).orElse(0);

            LogReplicationRequest request = LogReplicationRequest.builder()
                    .term(currentTerm.get()).leaderId(config.getNodeId())
                    .prevLogIndex(prevLogIndex).prevLogTerm(prevLogTerm)
                    .entries(consensusLog.getEntriesFrom(nextIdx)).leaderCommit(consensusLog.getCommitIndex()).build();

            ResponseEntity<LogReplicationResponse> response = restTemplate.postForEntity(
                    peer + "/raft/append", request, LogReplicationResponse.class);
            LogReplicationResponse appendResponse = response.getBody();

            if (appendResponse != null) {
                if (appendResponse.getTerm() > currentTerm.get()) { stepDown(appendResponse.getTerm()); return; }
                if (appendResponse.isSuccess()) {
                    nextIndex.put(peer, appendResponse.getMatchIndex() + 1);
                    matchIndex.put(peer, appendResponse.getMatchIndex());
                    updateLeaderCommitIndex();
                } else {
                    nextIndex.put(peer, Math.max(1, nextIdx - 1));
                }
            }
        } catch (Exception e) { }
    }

    private void updateLeaderCommitIndex() {
        List<Integer> indices = new ArrayList<>(matchIndex.values());
        indices.add(consensusLog.getLastLogIndex());
        Collections.sort(indices, Collections.reverseOrder());
        int majority = indices.size() / 2 + 1;
        if (indices.size() >= majority) {
            int n = indices.get(majority - 1);
            Optional<RaftLogEntry> entry = consensusLog.getEntry(n);
            if (entry.isPresent() && entry.get().getTerm() == currentTerm.get()) consensusLog.setCommitIndex(n);
        }
    }

    public PaymentTransaction submitTransaction(PaymentTransaction transaction) {
        if (state != ServerState.LEADER) { transaction.setStatus(PaymentStatus.FAILED); return transaction; }

        transaction.setStatus(PaymentStatus.PROCESSING);
        transaction.setProcessedByNode(config.getNodeId());
        if (transaction.getCreatedTimestamp() == 0) transaction.setCreatedTimestamp(System.currentTimeMillis());

        int index = consensusLog.append(currentTerm.get(), transaction);
        for (String peer : config.getPeers()) sendLogReplication(peer);

        int attempts = 0;
        while (consensusLog.getCommitIndex() < index && attempts < 20) {
            try { Thread.sleep(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
            attempts++;
        }

        transaction.setStatus(consensusLog.getCommitIndex() >= index ? PaymentStatus.SUCCESS : PaymentStatus.FAILED);
        return transaction;
    }

    public int getCurrentTerm() { return currentTerm.get(); }
    public boolean isLeader() { return state == ServerState.LEADER; }

    public Map<String, Object> getStatus() {
        Map<String, Object> status = new HashMap<>();
        status.put("nodeId", config.getNodeId());
        status.put("state", state);
        status.put("currentTerm", currentTerm.get());
        status.put("votedFor", votedFor);
        status.put("leaderId", leaderId);
        status.put("isLeader", isLeader());
        status.put("logSize", consensusLog.size());
        status.put("commitIndex", consensusLog.getCommitIndex());
        return status;
    }
}
