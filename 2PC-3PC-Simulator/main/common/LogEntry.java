package main.common;

/**
 * Represents a single entry written to a site's stable log.
 *
 * In a real system these are flushed to disk before any message is sent.
 * Here we keep them in a list that survives simulated crashes.
 */
public class LogEntry {

    public enum Type {
        // 2PC + 3PC shared
        PREPARE,    // coordinator: about to ask participants
        READY,      // participant: I can commit
        ABORT,      // coordinator OR participant: abort decision
        COMMIT,     // coordinator OR participant: final commit

        // 3PC only
        PRECOMMIT,  // coordinator: everyone agreed, about to commit
        ACKNOWLEDGE // participant: acknowledged precommit
    }

    public final Type type;
    public final String transactionId;

    public LogEntry(Type type, String transactionId) {
        this.type = type;
        this.transactionId = transactionId;
    }

    @Override
    public String toString() {
        return "<" + type + " " + transactionId + ">";
    }
}
