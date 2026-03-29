package com.payment.consensus.service;

import com.payment.common.model.PaymentTransaction;
import com.payment.consensus.model.RaftLogEntry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Replicated log for Raft consensus algorithm.
 */
@Slf4j
@Service
public class ConsensusLog {

    private final List<RaftLogEntry> entries;
    private volatile int commitIndex;
    private volatile int lastApplied;
    private final ReadWriteLock lock;
    private final List<PaymentTransaction> appliedTransactions;

    public ConsensusLog() {
        this.entries = new ArrayList<>();
        this.entries.add(RaftLogEntry.builder().term(0).index(0).command(null).timestamp(0).build());
        this.commitIndex = 0;
        this.lastApplied = 0;
        this.lock = new ReentrantReadWriteLock();
        this.appliedTransactions = new ArrayList<>();
    }

    public int append(int term, PaymentTransaction command) {
        lock.writeLock().lock();
        try {
            int index = entries.size();
            RaftLogEntry entry = RaftLogEntry.builder()
                    .term(term).index(index).command(command).timestamp(System.currentTimeMillis()).build();
            entries.add(entry);
            return index;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public boolean appendEntries(int prevLogIndex, List<RaftLogEntry> newEntries) {
        lock.writeLock().lock();
        try {
            if (newEntries == null || newEntries.isEmpty()) return true;
            int insertIndex = prevLogIndex + 1;
            for (RaftLogEntry newEntry : newEntries) {
                if (insertIndex < entries.size()) {
                    RaftLogEntry existing = entries.get(insertIndex);
                    if (existing.getTerm() != newEntry.getTerm()) {
                        entries.subList(insertIndex, entries.size()).clear();
                    }
                }
                if (insertIndex >= entries.size()) entries.add(newEntry);
                insertIndex++;
            }
            return true;
        } finally {
            lock.writeLock().unlock();
        }
    }

    public Optional<RaftLogEntry> getEntry(int index) {
        lock.readLock().lock();
        try {
            if (index < 0 || index >= entries.size()) return Optional.empty();
            return Optional.of(entries.get(index));
        } finally {
            lock.readLock().unlock();
        }
    }

    public List<RaftLogEntry> getEntriesFrom(int startIndex) {
        lock.readLock().lock();
        try {
            if (startIndex >= entries.size()) return Collections.emptyList();
            return new ArrayList<>(entries.subList(startIndex, entries.size()));
        } finally {
            lock.readLock().unlock();
        }
    }

    public boolean containsEntry(int index, int term) {
        lock.readLock().lock();
        try {
            if (index < 0 || index >= entries.size()) return index == 0 && term == 0;
            return entries.get(index).getTerm() == term;
        } finally {
            lock.readLock().unlock();
        }
    }

    public int getLastLogIndex() {
        lock.readLock().lock();
        try { return entries.size() - 1; }
        finally { lock.readLock().unlock(); }
    }

    public int getLastLogTerm() {
        lock.readLock().lock();
        try { return entries.get(entries.size() - 1).getTerm(); }
        finally { lock.readLock().unlock(); }
    }

    public void updateCommitIndex(int leaderCommit) {
        lock.writeLock().lock();
        try {
            if (leaderCommit > commitIndex) {
                commitIndex = Math.min(leaderCommit, entries.size() - 1);
                applyCommittedEntries();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    public void setCommitIndex(int index) {
        lock.writeLock().lock();
        try {
            if (index > commitIndex && index < entries.size()) {
                commitIndex = index;
                applyCommittedEntries();
            }
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void applyCommittedEntries() {
        while (lastApplied < commitIndex) {
            lastApplied++;
            RaftLogEntry entry = entries.get(lastApplied);
            if (entry.getCommand() != null) {
                appliedTransactions.add(entry.getCommand());
            }
        }
    }

    public int getCommitIndex() { return commitIndex; }
    public int getLastApplied() { return lastApplied; }

    public List<PaymentTransaction> getAppliedTransactions() {
        lock.readLock().lock();
        try { return new ArrayList<>(appliedTransactions); }
        finally { lock.readLock().unlock(); }
    }

    public int size() {
        lock.readLock().lock();
        try { return entries.size(); }
        finally { lock.readLock().unlock(); }
    }
}
