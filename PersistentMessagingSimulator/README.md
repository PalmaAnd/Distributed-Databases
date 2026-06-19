# Assignment 2 - Persistent Messaging System

## Setup

Requires Java 17+. No external dependencies.

```bash
# Compile (from the folder containing main/)
javac -d out $(find . -name "*.java")

# Run
java -cp out main.PersistentMessagingSimulator
```

## Project Structure

```
main/
├── PersistentMessagingSimulator.java   ← main entry point, all scenarios
├── model/
│   ├── Message.java                    ← row in messages_to_send
│   └── ReceivedMessage.java            ← row in received_messages
├── network/
│   └── NetworkChannel.java             ← unreliable network (drop, dup, delay)
├── sender/
│   └── Sender.java                     ← sender S + delivery process + T_OLD
└── receiver/
    └── Receiver.java                   ← receiver R + deduplication + cleanup
```

---

## Pre-built Scenarios

| Scenario | What it demonstrates |
|----------|----------------------|
| EXAM     | Exact Midterm II table - compute T_OLD, show cleanup |
| PM-1     | Happy path - all messages delivered and acked |
| PM-2     | Dropped message - sender retransmits, receiver processes on retry |
| PM-3     | **Dropped ACK** - receiver processed it, sender doesn't know, retransmit deduped |
| PM-4     | Duplicate delivery - network sends same message twice |
| PM-5     | **Receiver crashes after commit, before ack** - retransmit safely deduped |
| PM-6     | Out-of-order delivery - T_OLD waits for the oldest gap |
| PM-7     | Step-by-step T_OLD advancement and table shrinkage |

---

## Exercises

---

### Exercise A - Understand T_OLD

Run the EXAM scenario. Then answer:

1. After S receives the ack for message #9, which messages in `messages_to_send`
   are still unacknowledged?

2. Why is T_OLD defined as the *minimum* timestamp of unacknowledged messages,
   not the *maximum*?

3. What would happen if the receiver deleted rows with `time ≤ T_OLD` instead of
   `time < T_OLD`? Give a concrete example of what could go wrong.

4. True or False: *"The receiver can delete entries from `received_messages`
   at any time using a user-defined timeout."*
   Explain why this could cause incorrect behaviour.

5. In scenario PM-7, between Round 1 and Round 2, the ack for msg#9 arrives
   but T_OLD does not change. Why?

---

### Exercise B - Trace the Dropped ACK Scenario (PM-3)

This is the most important scenario to understand. Run PM-3 and answer:

1. After attempt 1, what is the state of:
   - `messages_to_send` at S?
   - `received_messages` at R?
   - The value of variable B?

2. When S retransmits in attempt 2, R receives the message again.
   Trace exactly what happens inside `Receiver.receive()`:
   - What check is performed first?
   - What is the result of that check?
   - What happens to the operation `B ← B − 30`?

3. Why is it critical that the ack is sent **after** the transaction commits,
   not before? What would go wrong if the ack were sent before commit?
   (Hint: think about what happens if R crashes between the two events.)

4. Why is it critical that the ack is sent **at all** even for duplicates?
   What would happen if R silently dropped a duplicate without re-sending the ack?

---

### Exercise C - Implement `Sender.computeTOldAfterAck()`

Add a method to `Sender.java` that computes the new T_OLD after a specific
acknowledgement arrives, **without** permanently modifying the table state:

```java
/**
 * Compute what T_OLD would be if the ack for messageNumber arrived.
 * Does NOT modify the table - this is a "preview" computation.
 *
 * Useful for the exam pattern: "assume ack for msg X arrives,
 * compute T_OLD".
 */
public long computeTOldAfterAck(long messageNumber) {
    // TODO
    // Hint: iterate messagesToSend, skip messageNumber even if unacked,
    // return minimum time among remaining unacked messages.
}
```

Test it in the EXAM scenario:
```java
// Before calling receiveAck(9), predict T_OLD:
long predicted = S.computeTOldAfterAck(9L);
System.out.println("Predicted T_OLD if msg#9 acked: " + predicted);

// Then actually apply the ack and verify:
S.receiveAck(9L);
long actual = S.computeTOld();
assert predicted == actual : "Prediction was wrong!";
```

---

### Exercise D - Implement `NetworkChannel.deliverWithLatency()`

Currently delays flush all at once. Implement selective, ordered flushing:

```java
/**
 * Flush exactly one delayed message (the oldest one in the queue).
 * Models a network where messages arrive in FIFO order but with variable
 * delay (each call = one "time tick" passes).
 *
 * @return the delivered message, or empty if nothing was delayed
 */
public Optional<Message> flushOldest() {
    // TODO
}
```

Use this to simulate a scenario where:
- S sends messages 1, 2, 3 in order
- Message 2 is delayed, 1 and 3 arrive immediately
- Show the state of T_OLD after each delivery step
- Finally flush message 2 and show the final state

What does this reveal about T_OLD and out-of-order acknowledgements?

---

### Exercise E - Implement Exception Handling for Undeliverable Messages

The lecture mentions: *"If a message cannot be delivered after a long period,
an exception handler is invoked."*

Add this to `Sender.java`:

```java
private int maxRetries = 3;
private final Map<Long, Integer> retryCounts = new HashMap<>();

/**
 * Attempt to send a message. Track retry count.
 * If retries exceed maxRetries, invoke the exception handler.
 *
 * @return true if delivered, false if dropped (will be retried next round)
 */
public boolean sendWithRetry(long messageNumber, NetworkChannel channel,
                             Fault fault) {
    // TODO
    // - increment retryCounts[messageNumber]
    // - if count > maxRetries: print exception handler message, return false
    // - otherwise: attempt delivery, return true if delivered
}
```

Test it by dropping a message 4 times in a row and verify the exception
handler fires on the 4th attempt.

---

### Exercise F - Multi-Sender Scenario

The lecture only discusses one sender and one receiver, but in a real
distributed system, multiple senders may message the same receiver.

Add a second sender S2 in `PersistentMessagingSimulator.main()`:

```java
var S1 = new Sender("S1");
var S2 = new Sender("S2");
var R  = new Receiver("R");
var channel = new NetworkChannel();

// S1 sends messages with numbers 1, 2, 3
// S2 also sends messages with numbers 1, 2, 3
// Both use the SAME receiver
```

**Problem:** Both senders use overlapping message numbers!
Message #1 from S1 and message #1 from S2 would collide in R's
`received_messages` table.

Answer these questions:
1. How would you fix the message numbering scheme to avoid collisions?
   Implement your fix and show that both senders' messages are handled correctly.

2. Does T_OLD need to be computed per-sender or globally?
   What happens if S1's T_OLD = 10 but S2's T_OLD = 3?

---

## Key Facts to Memorise

| Statement | True/False | Reason |
|-----------|------------|--------|
| T_OLD is a user-defined timeout | **FALSE** | It's the min timestamp of unacked messages |
| The receiver deletes rows with `time < T_OLD` | **TRUE** | Strictly less than - not ≤ |
| The ack is sent before the transaction commits | **FALSE** | After commit - to prevent message loss |
| A duplicate message is re-applied to the data | **FALSE** | Deduplicated via received_messages |
| If all messages are acked, T_OLD = ∞ | **TRUE** | Receiver can clear the entire table |
| Persistent messaging avoids growing `received_messages` via T_OLD | **TRUE** | Core purpose of T_OLD |
| A dropped ack causes the message to be processed twice | **FALSE** | Deduplication handles it |
