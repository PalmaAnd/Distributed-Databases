package main.model;

/**
 * A single persistent message from sender S to receiver R.
 *
 * Maps directly to a row in the messages_to_send relation:
 *
 *   messages_to_send
 *   ┌────────┬─────────────────┬──────┬──────────┐
 *   │ number │ message         │ time │ ack      │
 *   ├────────┼─────────────────┼──────┼──────────┤
 *   │ 7      │ Q ← Q + 3      │  5   │          │
 *   │ 9      │ C ← C − 6      │  8   │          │
 *   └────────┴─────────────────┴──────┴──────────┘
 *
 * The "ack" column is represented here as the boolean field `acknowledged`.
 * When true, the sender has confirmed delivery and this message is done.
 */
public class Message {

    public final long number;       // unique message ID, monotonically increasing
    public final String operation;  // e.g. "Q ← Q + 3" - the DB update to apply
    public final long time;         // logical timestamp at time of creation

    // Mutable state - updated when acknowledgement arrives
    private boolean acknowledged = false;

    public Message(long number, String operation, long time) {
        this.number    = number;
        this.operation = operation;
        this.time      = time;
    }

    public boolean isAcknowledged() { return acknowledged; }

    public void acknowledge() {
        acknowledged = true;
    }

    @Override
    public String toString() {
        return String.format("Msg#%d [t=%d] \"%s\" ack=%s",
                number, time, operation, acknowledged ? "received" : "-");
    }
}
