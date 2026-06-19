package main.sender;

import main.model.Message;
import main.network.NetworkChannel;

import java.util.*;

/**
 * Models the sender S and its messages_to_send relation.
 *
 * ── Sender Protocol (from lecture) ───────────────────────────────────────────
 *
 *  1. A transaction enqueues a message by inserting a row into messages_to_send.
 *     This insert happens INSIDE the transaction - if the transaction aborts,
 *     the message is never added (no spurious sends).
 *
 *  2. A background delivery process periodically scans messages_to_send,
 *     sends all rows where ack is null, and retries until ack = 'received'.
 *
 *  3. When an acknowledgement arrives, the sender marks the row ack = 'received'.
 *
 *  4. T_OLD is computed as the minimum timestamp among unacknowledged messages.
 *     The sender periodically reports T_OLD to the receiver so it can
 *     clean up its received_messages relation.
 *
 * ── Key invariant ─────────────────────────────────────────────────────────────
 *  A message is NEVER deleted from messages_to_send until its ack is received.
 *  This guarantees at-least-once delivery even if the sender crashes.
 *  The receiver provides exactly-once by deduplicating on message number.
 */
public class Sender {

    private final String id;

    // The messages_to_send relation: number → Message
    // Using LinkedHashMap to preserve insertion order (makes output readable)
    private final Map<Long, Message> messagesToSend = new LinkedHashMap<>();

    private long nextNumber = 1;
    private long logicalClock = 0;

    public Sender(String id) {
        this.id = id;
    }

    // ── Transaction interface ─────────────────────────────────────────────────

    /**
     * Enqueue a message as part of a transaction.
     * In a real system this insert is part of the same DB transaction
     * that performs the business logic - atomicity ensures the message
     * is only enqueued if the transaction commits.
     *
     * @param operation  the DB update to apply at the receiver (e.g. "Q ← Q + 3")
     * @return the created Message (for inspection / test assertions)
     */
    public Message enqueue(String operation) {
        logicalClock++;
        Message msg = new Message(nextNumber++, operation, logicalClock);
        messagesToSend.put(msg.number, msg);
        System.out.printf("  [%s] Enqueued %s%n", id, msg);
        return msg;
    }

    /**
     * Pre-populate messages_to_send with an already-acknowledged message.
     * Used to recreate the initial table state from exam exercises.
     */
    public Message enqueueAcked(String operation) {
        Message msg = enqueue(operation);
        msg.acknowledge();
        return msg;
    }

    /**
     * Pre-populate with a specific timestamp (for exam table recreation).
     */
    public Message enqueueWithTime(long number, String operation, long time, boolean acked) {
        Message msg = new Message(number, operation, time);
        if (acked) msg.acknowledge();
        messagesToSend.put(number, msg);
        nextNumber = Math.max(nextNumber, number + 1);
        logicalClock = Math.max(logicalClock, time);
        return msg;
    }

    // ── Delivery process ──────────────────────────────────────────────────────

    /**
     * Send a specific message over the channel with the given fault mode.
     * Returns the Message so the caller can pass it to the receiver.
     */
    public List<Message> send(long messageNumber, NetworkChannel channel,
                              NetworkChannel.Fault fault) {
        Message msg = messagesToSend.get(messageNumber);
        if (msg == null) throw new IllegalArgumentException("No message #" + messageNumber);
        if (msg.isAcknowledged()) {
            System.out.printf("  [%s] Msg#%d already acked - skipping%n", id, messageNumber);
            return List.of();
        }
        System.out.printf("  [%s] Sending %s%n", id, msg);
        return channel.deliver(msg, fault);
    }

    /**
     * Retransmit all unacknowledged messages (what the delivery process does
     * on a timer). Uses NONE fault mode - the retry itself is reliable here;
     * faults are injected per individual send() call.
     */
    public List<Message> retransmitAll(NetworkChannel channel) {
        System.out.printf("  [%s] Retransmitting all unacknowledged messages...%n", id);
        List<Message> sent = new ArrayList<>();
        for (Message msg : messagesToSend.values()) {
            if (!msg.isAcknowledged()) {
                List<Message> delivered = channel.deliver(msg, NetworkChannel.Fault.NONE);
                sent.addAll(delivered);
            }
        }
        return sent;
    }

    // ── Acknowledgement handling ──────────────────────────────────────────────

    /**
     * Called when an ack arrives for the given message number.
     * Marks the row ack = 'received' in messages_to_send.
     */
    public void receiveAck(long messageNumber) {
        Message msg = messagesToSend.get(messageNumber);
        if (msg == null) {
            System.out.printf("  [%s] Received ack for unknown Msg#%d (ignored)%n",
                    id, messageNumber);
            return;
        }
        msg.acknowledge();
        System.out.printf("  [%s] Ack received for Msg#%d → marked 'received'%n",
                id, messageNumber);
    }

    // ── T_OLD computation ─────────────────────────────────────────────────────

    /**
     * Compute T_OLD: the timestamp of the OLDEST unacknowledged message.
     *
     * Definition (from lecture):
     *   T_OLD = min{ msg.time | msg ∈ messages_to_send AND msg.ack IS NULL }
     *
     * Interpretation:
     *   - The sender guarantees it will NEVER resend any message with time < T_OLD.
     *   - Therefore the receiver can safely delete all received_messages with time < T_OLD.
     *   - If ALL messages are acknowledged, T_OLD = ∞ (receiver can delete everything).
     *
     * EXAM TRAP: T_OLD is NOT a user-defined timeout. It is derived from the
     * actual state of messages_to_send at the moment of computation.
     */
    public long computeTOld() {
        long tOld = Long.MAX_VALUE; // represents ∞
        for (Message msg : messagesToSend.values()) {
            if (!msg.isAcknowledged()) {
                tOld = Math.min(tOld, msg.time);
            }
        }
        if (tOld == Long.MAX_VALUE) {
            System.out.printf("  [%s] T_OLD = ∞ (all messages acknowledged)%n", id);
        } else {
            System.out.printf("  [%s] T_OLD = %d  (oldest unacked: time=%d)%n", id, tOld, tOld);
        }
        return tOld;
    }

    // ── Inspection ────────────────────────────────────────────────────────────

    public void printTable() {
        System.out.printf("  [%s] messages_to_send:%n", id);
        System.out.printf("    %-8s %-20s %-6s %-10s%n", "number", "message", "time", "ack");
        System.out.printf("    %-8s %-20s %-6s %-10s%n", "------", "-------", "----", "---");
        for (Message m : messagesToSend.values()) {
            System.out.printf("    %-8d %-20s %-6d %-10s%n",
                    m.number, m.operation, m.time,
                    m.isAcknowledged() ? "received" : "");
        }
    }

    public Map<Long, Message> getTable() {
        return Collections.unmodifiableMap(messagesToSend);
    }

    public String getId() { return id; }
}
