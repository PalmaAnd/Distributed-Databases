package main.network;

import main.model.Message;

import java.util.*;

/**
 * Simulates an unreliable network channel between sender and receiver.
 *
 * Supports three fault modes that persistent messaging must handle:
 *
 *   DROP        - message is lost entirely (no delivery, no ack)
 *   DUPLICATE   - message is delivered twice (tests idempotency)
 *   DELAY       - message sits in a pending queue; delivered later
 *   NONE        - reliable delivery (happy path)
 *
 * Also simulates loss of acknowledgement messages (ack_drop), which
 * causes the sender to retransmit even though the receiver already
 * processed the message - the most important deduplication scenario.
 */
public class NetworkChannel {

    public enum Fault { NONE, DROP, DUPLICATE, DELAY }

    // Pending deliveries (used for DELAY mode)
    private final Deque<Message> delayed = new ArrayDeque<>();

    // Which message numbers should have their ACK dropped
    private final Set<Long> ackDropNumbers = new HashSet<>();

    // Delivery log for inspection
    private final List<String> deliveryLog = new ArrayList<>();

    private boolean verbose = true;

    public NetworkChannel() {}

    // ── Core delivery ─────────────────────────────────────────────────────────

    /**
     * Attempt to deliver msg to receiver using the given fault mode.
     *
     * @return list of Message objects actually delivered this call
     *         (0 = dropped/delayed, 1 = normal, 2 = duplicate)
     */
    public List<Message> deliver(Message msg, Fault fault) {
        List<Message> delivered = new ArrayList<>();

        switch (fault) {
            case NONE -> {
                log("DELIVER Msg#" + msg.number);
                delivered.add(msg);
            }
            case DROP -> {
                log("DROP    Msg#" + msg.number + " (lost on network)");
                // nothing delivered
            }
            case DUPLICATE -> {
                log("DUPLICATE Msg#" + msg.number + " (delivered twice)");
                delivered.add(msg);
                delivered.add(msg);   // receiver must deduplicate
            }
            case DELAY -> {
                log("DELAY   Msg#" + msg.number + " (queued for later)");
                delayed.addLast(msg);
                // caller must call flushDelayed() later
            }
        }
        return delivered;
    }

    /**
     * Flush all delayed messages - deliver them now.
     *
     * @return all messages that were waiting
     */
    public List<Message> flushDelayed() {
        List<Message> flushed = new ArrayList<>(delayed);
        for (Message m : flushed) log("FLUSH   Msg#" + m.number + " (delayed delivery)");
        delayed.clear();
        return flushed;
    }

    // ── Acknowledgement fault injection ──────────────────────────────────────

    /**
     * Register a message number whose acknowledgement should be dropped.
     * The receiver processes the message, but the sender never hears back
     * → sender retransmits → receiver must deduplicate.
     */
    public void dropAckFor(long messageNumber) {
        ackDropNumbers.add(messageNumber);
        log("ACK-DROP registered for Msg#" + messageNumber);
    }

    /**
     * Simulate sending an ack. Returns false if the ack was dropped.
     */
    public boolean sendAck(long messageNumber) {
        if (ackDropNumbers.contains(messageNumber)) {
            log("ACK-DROP Msg#" + messageNumber + " (sender will retransmit)");
            return false;
        }
        log("ACK-SENT Msg#" + messageNumber);
        return true;
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void log(String event) {
        deliveryLog.add(event);
        if (verbose) System.out.printf("    [NET] %s%n", event);
    }

    public List<String> getDeliveryLog() { return Collections.unmodifiableList(deliveryLog); }
    public void setVerbose(boolean v)    { this.verbose = v; }
    public boolean hasDelayed()          { return !delayed.isEmpty(); }
}
