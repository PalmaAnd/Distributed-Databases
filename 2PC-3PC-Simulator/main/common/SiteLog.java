package main.common;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Simulates a site's stable (disk-persistent) log.
 *
 * Key property: entries written here survive a simulated crash.
 * When a site "recovers", it reads this log to decide what to do.
 */
public class SiteLog {

    private final String siteId;
    private final List<LogEntry> entries = new ArrayList<>();

    public SiteLog(String siteId) {
        this.siteId = siteId;
    }

    /** Write a log entry. In a real system this flushes to disk before returning. */
    public void write(LogEntry.Type type, String txId) {
        LogEntry entry = new LogEntry(type, txId);
        entries.add(entry);
        System.out.printf("    [LOG %s] wrote %s%n", siteId, entry);
    }

    /** Find the last log entry of a given type for a transaction. */
    public Optional<LogEntry> findLast(LogEntry.Type type, String txId) {
        for (int i = entries.size() - 1; i >= 0; i--) {
            LogEntry e = entries.get(i);
            if (e.transactionId.equals(txId) && e.type == type) return Optional.of(e);
        }
        return Optional.empty();
    }

    /** Check whether this log contains any entry of the given type for a transaction. */
    public boolean contains(LogEntry.Type type, String txId) {
        return findLast(type, txId).isPresent();
    }

    /** Return all entries for a given transaction (for recovery inspection). */
    public List<LogEntry> entriesFor(String txId) {
        List<LogEntry> result = new ArrayList<>();
        for (LogEntry e : entries) {
            if (e.transactionId.equals(txId)) result.add(e);
        }
        return result;
    }

    public String getSiteId() { return siteId; }

    public void printAll() {
        System.out.printf("  Log at %s: %s%n", siteId, entries);
    }
}
