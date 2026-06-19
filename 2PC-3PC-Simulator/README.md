# 2PC & 3PC Commit Protocol Simulator

## Setup

Requires Java 17+. No external dependencies.

```bash
# Compile (from the folder containing main/)
javac -d out $(find . -name "*.java")

# Run
java -cp out main.CommitSimulator
```

## Project Structure

```
main/
├── CommitSimulator.java          ← main entry point, all 8 scenarios
├── common/
│   ├── LogEntry.java             ← stable log entry (PREPARE, READY, PRECOMMIT, COMMIT, ABORT)
│   ├── SiteLog.java              ← a site's persistent log
│   └── FailurePoint.java         ← enum controlling where crashes are injected
├── twopc/
│   ├── TwoPhaseCoordinator.java  ← drives both 2PC phases
│   └── TwoPhaseParticipant.java  ← votes, applies decisions, recovers
└── threepc/
    ├── ThreePhaseCoordinator.java ← drives all 3 phases of 3PC
    └── ThreePhaseParticipant.java ← votes, precommits, commits, recovers
```

---

## Pre-built Scenarios (run as-is)

| Scenario | Protocol | What it demonstrates |
|----------|----------|----------------------|
| 2PC-1 | 2PC | Happy path - all sites commit |
| 2PC-2 | 2PC | One participant votes ABORT -> all abort |
| 2PC-3 | 2PC | **THE BLOCKING PROBLEM** - coordinator crashes after decision |
| 2PC-4 | 2PC | Participant crashes before Phase 1, recovers with empty log |
| 3PC-1 | 3PC | Happy path - all three phases |
| 3PC-2 | 3PC | Abort path via Phase 2 |
| 3PC-3 | 3PC | **NO BLOCKING** - same crash as 2PC-3, different outcome |
| 3PC-4 | 3PC | Participant misses PRECOMMIT, recovers to correct action |

---

## Exercises

Work through these after reading the code and running the simulator.

---

### Exercise A - Understand the Blocking Problem

Run the simulator and compare the output of **2PC-3** and **3PC-3**.

Answer these questions in a comment block at the top of `CommitSimulator.java`:

1. In 2PC-3, what exact log entry do all participants have when the coordinator crashes?
   Why does this entry leave the fate of the transaction unknown?
   > `<Ready T3>`. All participants have `<READY T>` but no decision. They hold all their locks and CANNOT proceed. They must wait until coordinator recovers.

2. In 3PC-3, what log entry do participants have instead?
   How does this entry allow a new coordinator to make progress?
    > `[<READY T7>, <PRECOMMIT T7>]`. Participants with `<PRECOMMIT T>` tell any new coordinator: COMMIT. Participants with only `<READY T>` tell any new coordinator: safe to ABORT.

3. In 2PC-3, participant S2 calls `recover()`. What does the output say?
   What would S2 need to do in a real system to resolve its uncertainty?
   > In a real system: send a query to the coordinator and wait. If coordinator is also down, this site BLOCKS - the 2PC problem.

4. True or False (and explain): *"2PC blocks only if all participants voted READY."*

---

### Exercise B - Inject Failures at Different Points

In `CommitSimulator.main()`, add four new scenarios using the `FailurePoint` enum:

```java
// Scenario B1: 2PC - coordinator crashes before sending PREPARE
// What do participants have in their log? What happens when they recover?

// Scenario B2: 2PC - coordinator crashes after sending PREPARE,
//              but before collecting votes
// Hint: participants have already flushed <READY T> to their logs.
//       What happens next?

// Scenario B3: 3PC - coordinator crashes mid-broadcast in Phase 3
//              (AFTER_SOME_COMMITS - S1 gets COMMIT, S2 and S3 do not)
// Is the system in an inconsistent state? What must happen?

// Scenario B4: 2PC - participant S1 votes ABORT while S2 and S3 vote READY.
//              Then S1 crashes before receiving the final ABORT decision.
//              What does S1 find in its log when it recovers?
```

For each scenario, write a one-sentence comment in the code describing what the recovery output tells you.

---

### Exercise C - Recovery Oracle

Without running the code, predict the output of `recover()` for each participant given the following log states. Then run the simulator (create a new scenario) to verify.

**Transaction T_X in a 2PC system:**

| Site | Log contents | Your prediction | Correct? |
|------|-------------|-----------------|----------|
| S1   | `<PREPARE T_X>`, `<COMMIT T_X>` | ? | |
| S2   | `<READY T_X>` only | ? | |
| S3   | (empty) | ? | |
| S4   | `<ABORT T_X>` | ? | |

**Transaction T_Y in a 3PC system:**

| Site | Log contents | Your prediction | Correct? |
|------|-------------|-----------------|----------|
| S1   | `<READY T_Y>`, `<PRECOMMIT T_Y>`, `<COMMIT T_Y>` | ? | |
| S2   | `<READY T_Y>`, `<PRECOMMIT T_Y>` only | ? | |
| S3   | `<READY T_Y>` only | ? | |
| S4   | (empty) | ? | |

Hint: use `SiteLog.write()` directly to pre-populate a log state, then call `recover()`.

---

### Exercise D - Implement Coordinator Recovery

Currently, if the 2PC coordinator crashes and recovers, it has no recovery logic.

Implement `TwoPhaseCoordinator.recover(String txId, List<TwoPhaseParticipant> participants)`:

```java
/**
 * The coordinator crashed and is back online.
 * It checks its own log and the logs of available participants
 * to determine what to do.
 *
 * Rules:
 *  - If coordinator log has <COMMIT T>   -> re-send commit to all participants
 *  - If coordinator log has <ABORT T>    -> re-send abort to all participants
 *  - If coordinator log has <PREPARE T> only (no decision yet):
 *      -> check participants:
 *          - any participant has <ABORT T>   -> abort
 *          - any participant has <COMMIT T>  -> commit
 *          - all participants have <READY T> -> BLOCKING - cannot decide
 *          - some participant has nothing    -> abort (safe: they never voted)
 */
public void recover(String txId, List<TwoPhaseParticipant> participants) {
    // TODO: implement
}
```

Test it by:
1. Running scenario 2PC-3 (coordinator crashes after decision)
2. Calling `coord.recover("T3", participants)` after the crash
3. Verifying that participants end up in the correct committed state

---

### Exercise E - Implement Persistent Messaging (Preview of Assignment 2)

This is a stretch exercise connecting Assignment 1 to Assignment 2.

Modify the simulator so that instead of direct method calls between coordinator
and participants, messages go through a `MessageQueue`:

```java
public class MessageQueue {
    // messages waiting to be delivered
    private final Queue<Message> pending = new LinkedList<>();

    // messages already delivered (for deduplication)
    private final Set<Long> delivered = new HashSet<>();

    public void send(Message m) { pending.add(m); }

    /**
     * Deliver the next message to its recipient.
     * Drop it silently if it was already delivered (simulate duplicate).
     */
    public void deliverNext() { /* TODO */ }

    /** Simulate a message getting lost */
    public void dropNext() { pending.poll(); }
}
```

Observe: what happens to the 2PC protocol when a COMMIT message is dropped?
Does the participant eventually receive the decision? How would persistent
messaging fix this?

---

## Key Facts to Memorise

After completing the exercises, you should be able to answer these exam-style T/F:

| Statement | Answer |
|-----------|--------|
| 2PC does not block as long as the coordinator is reachable | **TRUE** |
| `<ready T>` in the log means the transaction failed | **FALSE** - fate is unknown |
| Phase 1 of 2PC and 3PC are identical | **TRUE** |
| 3PC avoids blocking under network partitioning | **FALSE** - assumes no partition |
| Finding `<abort T>` in 3PC recovery requires no action | **TRUE** |
| A participant that logged `<precommit T>` knows the coordinator decided COMMIT | **TRUE** |
