package main.receiver;

import main.model.Message;
import main.model.ReceivedMessage;
import main.network.NetworkChannel;

import java.util.*;

/**
 * Models the receiver R and its received_messages relation.
 *
 * ── Receiver Protocol (from lecture) ─────────────────────────────────────────
 *
 *  On receiving a message M:
 *  1. BEGIN TRANSACTION
 *  2. IF M.number NOT IN received_messages:
 *       INSERT INTO received_messages (number, message, time, ack='sent')
 *       Apply M.operation to local data
 *  3. COMMIT TRANSACTION
 *  4. Send ack to sender
 *     (CRITICAL: ack sent AFTER commit - not before - to avoid losing a message
 *      if the receiver crashes between ack-send and commit)
 *
 * ── Deduplication guarantee ───────────────────────────────────────────────────
 *  Step 2's IF guard ensures idempotency: retransmitted messages are silently
 *  ignored. The receiver only applies an operation once, even if the message
 *  arrives multiple times (due to dropped acks, network retransmission, etc.)
 *
 * ── T_OLD cleanup ─────────────────────────────────────────────────────────────
 *  When the sender reports T_OLD, the receiver deletes all rows from
 *  received_messages where time < T_OLD. These rows can never be needed again
 *  (the sender will never resend those messages - it already has their acks).
 */
public class Receiver {

    private final String id;

    // The received_messages relation: number → ReceivedMessage
    private final Map<Long, ReceivedMessage> receivedMessages = new LinkedHashMap<>();

    // Simulated data store: variable name → value
    // Models the actual database state that operations modify
    private final Map<String, Integer> dataStore = new LinkedHashMap<>();

    // Track whether the receiver is "crashed" (for crash simulation)
    private boolean crashed = false;

    // If true, simulate a crash between commit and ack-send
    private boolean crashAfterCommit = false;

    public Receiver(String id) {
        this.id = id;
    }

    // ── Pre-population (for exam table recreation) ────────────────────────────

    public void preloadReceivedMessage(long number, String operation, long time) {
        receivedMessages.put(number, new ReceivedMessage(number, operation, time));
    }

    public void initDataStore(String variable, int value) {
        dataStore.put(variable, value);
    }

    // ── Message receipt ───────────────────────────────────────────────────────

    /**
     * Process a delivered message. Returns true if an ack was sent.
     *
     * This method models the full receiver transaction:
     *   BEGIN → deduplicate → insert → apply → COMMIT → send ack
     *
     * The NetworkChannel's sendAck() call can drop the ack (simulating
     * ack loss), causing the sender to retransmit - testing idempotency.
     */
    public boolean receive(Message msg, NetworkChannel channel) {
        if (crashed) {
            System.out.printf("  [%s] CRASHED - cannot receive Msg#%d%n", id, msg.number);
            return false;
        }

        System.out.printf("  [%s] Receiving Msg#%d: \"%s\"%n", id, msg.number, msg.operation);

        // ── Step 1: check for duplicate ──────────────────────────────────────
        if (receivedMessages.containsKey(msg.number)) {
            System.out.printf("  [%s] Msg#%d already in received_messages → DUPLICATE, ignored%n",
                    id, msg.number);
            // Still send ack - sender may not have received the first one
            boolean ackSent = channel.sendAck(msg.number);
            System.out.printf("  [%s] Re-sent ack for Msg#%d%n", id, msg.number);
            return ackSent;
        }

        // ── Step 2: begin transaction, insert, apply, commit ─────────────────
        System.out.printf("  [%s] BEGIN TRANSACTION%n", id);

        // Insert into received_messages (within transaction)
        ReceivedMessage rm = new ReceivedMessage(msg.number, msg.operation, msg.time);
        receivedMessages.put(msg.number, rm);

        // Apply the operation to the data store (within transaction)
        applyOperation(msg.operation);

        // ── Crash simulation: crash before commit ─────────────────────────────
        // In this case the transaction is rolled back - the insert is undone.
        // The message was not processed. When sender retransmits, receiver
        // processes it correctly (number not in received_messages → not a duplicate).
        if (crashed) {
            System.out.printf("  [%s] CRASHED before COMMIT → rolling back%n", id);
            receivedMessages.remove(msg.number); // simulated rollback
            return false;
        }

        System.out.printf("  [%s] COMMIT TRANSACTION%n", id);

        // ── Crash simulation: crash AFTER commit but BEFORE sending ack ───────
        // Most dangerous case: message WAS processed, but ack never sent.
        // Sender retransmits → receiver sees number already in received_messages
        // → correctly identified as duplicate → ack re-sent, no double-apply.
        if (crashAfterCommit) {
            crashAfterCommit = false;
            crashed = true;
            System.out.printf("  [%s] CRASHED after COMMIT, before sending ack!%n", id);
            System.out.printf("  [%s] Message was applied. On retransmit: will be deduplicated.%n", id);
            return false;
        }

        // ── Step 3: send ack AFTER commit ────────────────────────────────────
        boolean ackSent = channel.sendAck(msg.number);
        if (!ackSent) {
            System.out.printf("  [%s] Ack for Msg#%d was DROPPED by network%n", id, msg.number);
        }
        return ackSent;
    }

    /**
     * Process a list of delivered messages (used with network simulation).
     */
    public void receiveAll(List<Message> messages, NetworkChannel channel) {
        for (Message m : messages) receive(m, channel);
    }

    // ── T_OLD cleanup ─────────────────────────────────────────────────────────

    /**
     * Apply T_OLD received from the sender: delete all received_messages
     * where time < T_OLD.
     *
     * Safety guarantee: the sender will NEVER resend messages with time < T_OLD
     * (it already has their acks). So these rows in received_messages can never
     * be needed for deduplication again → safe to delete.
     *
     * EXAM NOTE: rows with time == T_OLD are kept (only strictly less than).
     */
    public void applyTOld(long tOld) {
        if (tOld == Long.MAX_VALUE) {
            System.out.printf("  [%s] T_OLD = ∞ → clearing ALL entries from received_messages%n", id);
            receivedMessages.clear();
            return;
        }

        System.out.printf("  [%s] Applying T_OLD = %d → removing entries with time < %d%n",
                id, tOld, tOld);

        int removed = 0;
        Iterator<Map.Entry<Long, ReceivedMessage>> it = receivedMessages.entrySet().iterator();
        while (it.hasNext()) {
            ReceivedMessage rm = it.next().getValue();
            if (rm.time < tOld) {
                System.out.printf("  [%s]   Removing %s%n", id, rm);
                it.remove();
                removed++;
            }
        }
        System.out.printf("  [%s]   Removed %d row(s). Table now has %d row(s).%n",
                id, removed, receivedMessages.size());
    }

    // ── Operation interpreter ─────────────────────────────────────────────────

    /**
     * Parse and apply a simple DB operation of the form "X ← X + n" or "X ← X - n".
     *
     * In a real system this would be a full SQL statement; here we keep
     * it simple to focus on the messaging protocol.
     */
    private void applyOperation(String operation) {
        try {
            // Parse "VAR ← VAR OP NUMBER"
            // e.g. "Q ← Q + 3" or "B ← B − 9"
            String[] parts = operation.split("←");
            String varName = parts[0].trim();
            String expr    = parts[1].trim();       // "Q + 3" or "B − 9"

            // Replace Unicode minus with ASCII minus for parsing
            expr = expr.replace("−", "-").replace("–", "-");

            // Extract operator and number (skip the variable name part)
            // expr is like "Q + 3" - split on + or -
            int value = dataStore.getOrDefault(varName, 0);
            int delta;
            if (expr.contains("+")) {
                String[] ops = expr.split("\\+");
                delta = Integer.parseInt(ops[1].trim());
                value += delta;
            } else if (expr.contains("-")) {
                String[] ops = expr.split("-", 2);
                // ops[0] is the variable name (ignored), ops[1] is the number
                delta = Integer.parseInt(ops[1].trim());
                value -= delta;
            } else {
                System.out.printf("  [%s] Cannot parse operation: %s%n", id, operation);
                return;
            }
            dataStore.put(varName, value);
            System.out.printf("  [%s]   Applied: %s → %s = %d%n", id, operation, varName, value);
        } catch (Exception e) {
            System.out.printf("  [%s]   Skipping operation (parse error): %s%n", id, operation);
        }
    }

    // ── Crash simulation ──────────────────────────────────────────────────────

    public void crash()              { crashed = true;
        System.out.printf("  [%s] *** CRASHED ***%n", id); }
    public void recover()            { crashed = false;
        System.out.printf("  [%s] Recovered.%n", id); }
    public void crashAfterNextCommit() { crashAfterCommit = true; }

    // ── Inspection ────────────────────────────────────────────────────────────

    public void printReceivedTable() {
        System.out.printf("  [%s] received_messages:%n", id);
        System.out.printf("    %-8s %-20s %-6s %-6s%n", "number", "message", "time", "ack");
        System.out.printf("    %-8s %-20s %-6s %-6s%n", "------", "-------", "----", "---");
        for (ReceivedMessage rm : receivedMessages.values()) {
            System.out.printf("    %-8d %-20s %-6d %-6s%n",
                    rm.number, rm.operation, rm.time, rm.ack);
        }
        if (receivedMessages.isEmpty()) {
            System.out.printf("    (empty)%n");
        }
    }

    public void printDataStore() {
        System.out.printf("  [%s] Data store: %s%n", id, dataStore);
    }

    public Map<Long, ReceivedMessage> getReceivedMessages() {
        return Collections.unmodifiableMap(receivedMessages);
    }

    public Map<String, Integer> getDataStore() {
        return Collections.unmodifiableMap(dataStore);
    }

    public String getId()      { return id; }
    public boolean isCrashed() { return crashed; }
}
