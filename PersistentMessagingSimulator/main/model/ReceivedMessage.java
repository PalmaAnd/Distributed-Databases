package main.model;

/**
 * A single entry in the receiver's received_messages relation.
 *
 *   received_messages
 *   ┌────────┬─────────────────┬──────┬──────┐
 *   │ number │ message         │ time │ ack  │
 *   ├────────┼─────────────────┼──────┼──────┤
 *   │ 7      │ Q ← Q + 3      │  5   │ sent │
 *   │ 8      │ B ← B − 9      │  7   │ sent │
 *   └────────┴─────────────────┴──────┴──────┘
 *
 * The "ack" column here tracks whether the receiver has sent its
 * acknowledgement back to the sender. In this simulation it is
 * always "sent" once the row exists (the ack is sent immediately
 * after the transaction commits and the row is inserted).
 *
 * Key invariant: a row is only inserted if number is not already present.
 * This is the deduplication mechanism - idempotent on retransmission.
 */
public class ReceivedMessage {

    public final long   number;
    public final String operation;
    public final long   time;
    public final String ack;       // always "sent" in our model

    public ReceivedMessage(long number, String operation, long time) {
        this.number    = number;
        this.operation = operation;
        this.time      = time;
        this.ack       = "sent";
    }

    @Override
    public String toString() {
        return String.format("RecvMsg#%d [t=%d] \"%s\" ack=%s",
                number, time, operation, ack);
    }
}
