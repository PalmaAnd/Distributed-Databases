package main;

import main.network.NetworkChannel;
import main.network.NetworkChannel.Fault;
import main.receiver.Receiver;
import main.sender.Sender;

/**
 * ===========================================================================
 *  main Assignment 2 - Persistent Messaging System
 * ===========================================================================
 *
 *  SCENARIOS
 *  ─────────
 *  EXAM  - Exact table from Midterm II Exercise 2: compute T_OLD and show
 *          received_messages after applying it.
 *
 *  PM-1  - Happy path: S sends three messages, R receives all, acks all.
 *          T_OLD advances as acks arrive; R's table shrinks.
 *
 *  PM-2  - Dropped message: sender retransmits; receiver processes on retry.
 *
 *  PM-3  - Dropped ACK: receiver processed the message, but ack never reached
 *          sender. Sender retransmits. Receiver deduplicates correctly.
 *          *** This is the most important scenario ***
 *
 *  PM-4  - Duplicate delivery: network delivers same message twice.
 *          Receiver silently ignores the second copy.
 *
 *  PM-5  - Receiver crashes AFTER commit but BEFORE sending ack.
 *          On recovery + retransmit: message is deduplicated (not re-applied).
 *
 *  PM-6  - Out-of-order delivery: message 3 arrives before message 2.
 *          Both are processed correctly; T_OLD accounts for the gap.
 *
 *  PM-7  - T_OLD cleanup: multiple rounds of acks; show step-by-step how
 *          received_messages shrinks as T_OLD advances.
 *
 * ===========================================================================
 */
public class PersistentMessagingSimulator {

    public static void main(String[] args) {

        examScenario();

        scenario("PM-1: Happy path - all messages delivered and acknowledged");
        {
            var channel = new NetworkChannel();
            var S = new Sender("S");
            var R = new Receiver("R");
            R.initDataStore("Q", 10);
            R.initDataStore("A", 20);

            var m1 = S.enqueue("Q ← Q + 5");
            var m2 = S.enqueue("A ← A + 3");
            var m3 = S.enqueue("Q ← Q - 2");

            System.out.println("\n  [Initial state]");
            S.printTable();

            System.out.println("\n  [Delivery round]");
            R.receiveAll(S.send(m1.number, channel, Fault.NONE), channel);
            S.receiveAck(m1.number);
            R.receiveAll(S.send(m2.number, channel, Fault.NONE), channel);
            S.receiveAck(m2.number);
            R.receiveAll(S.send(m3.number, channel, Fault.NONE), channel);
            S.receiveAck(m3.number);

            System.out.println("\n  [After all acks]");
            S.printTable();
            R.printReceivedTable();
            R.printDataStore();

            long tOld = S.computeTOld();
            System.out.println();
            R.applyTOld(tOld);
            System.out.println("\n  [After T_OLD cleanup]");
            R.printReceivedTable();
        }

        scenario("PM-2: Dropped message - sender retransmits after timeout");
        {
            var channel = new NetworkChannel();
            var S = new Sender("S");
            var R = new Receiver("R");
            R.initDataStore("Q", 100);

            var m1 = S.enqueue("Q ← Q + 10");
            var m2 = S.enqueue("Q ← Q + 20");

            System.out.println("\n  [Attempt 1: m1 dropped, m2 succeeds]");
            R.receiveAll(S.send(m1.number, channel, Fault.DROP), channel);    // lost!
            R.receiveAll(S.send(m2.number, channel, Fault.NONE), channel);
            S.receiveAck(m2.number);

            System.out.println("\n  [State after attempt 1]");
            S.printTable();
            R.printReceivedTable();
            R.printDataStore();
            System.out.printf("  T_OLD = %d (m1 still unacked)%n", S.computeTOld());

            System.out.println("\n  [Attempt 2: retransmit m1]");
            R.receiveAll(S.send(m1.number, channel, Fault.NONE), channel);
            S.receiveAck(m1.number);

            System.out.println("\n  [State after retransmit]");
            S.printTable();
            R.printReceivedTable();
            R.printDataStore();
            S.computeTOld();
        }

        scenario("PM-3: *** Dropped ACK - the key deduplication scenario ***\n" +
                 "       Receiver processes message, ack lost, sender retransmits.\n" +
                 "       Receiver must NOT apply the operation a second time.");
        {
            var channel = new NetworkChannel();
            channel.dropAckFor(1L);   // ack for message #1 will be dropped

            var S = new Sender("S");
            var R = new Receiver("R");
            R.initDataStore("B", 100);

            var m1 = S.enqueue("B ← B - 30");

            System.out.println("\n  [Attempt 1: message delivered, ack dropped]");
            R.receiveAll(S.send(m1.number, channel, Fault.NONE), channel);
            // Ack was dropped - S never hears back

            System.out.println("\n  [State after attempt 1]");
            System.out.printf("  S thinks m1 is unacked. B = %d (correctly updated once)%n",
                    R.getDataStore().get("B"));
            S.printTable();
            R.printReceivedTable();

            System.out.println("\n  [Attempt 2: sender retransmits (timeout)]");
            // Receiver already has #1 in received_messages → duplicate → ignored
            R.receiveAll(S.send(m1.number, channel, Fault.NONE), channel);
            // This time ack goes through
            S.receiveAck(m1.number);

            System.out.println("\n  [State after retransmit]");
            S.printTable();
            R.printDataStore();
            System.out.printf("  B = %d (applied exactly once - deduplication worked!)%n",
                    R.getDataStore().get("B"));
        }

        scenario("PM-4: Duplicate delivery - network delivers same message twice");
        {
            var channel = new NetworkChannel();
            var S = new Sender("S");
            var R = new Receiver("R");
            R.initDataStore("A", 50);

            var m1 = S.enqueue("A ← A + 15");

            System.out.println("\n  [Delivery with DUPLICATE fault]");
            // channel.deliver() sends the message twice in one call
            var delivered = S.send(m1.number, channel, Fault.DUPLICATE);
            System.out.printf("  Channel returned %d copies for delivery%n", delivered.size());
            R.receiveAll(delivered, channel);

            System.out.println("\n  [State after duplicate delivery]");
            R.printDataStore();
            System.out.printf("  A = %d (should be 65, not 80 - second copy ignored)%n",
                    R.getDataStore().get("A"));
            R.printReceivedTable();
        }

        scenario("PM-5: Receiver crashes AFTER commit, BEFORE sending ack\n" +
                 "       Most dangerous crash point - tests that recovery is safe");
        {
            var channel = new NetworkChannel();
            var S = new Sender("S");
            var R = new Receiver("R");
            R.initDataStore("C", 200);

            var m1 = S.enqueue("C ← C - 50");

            System.out.println("\n  [Attempt 1: receiver crashes after commit, before ack]");
            R.crashAfterNextCommit();   // arm the crash trigger
            R.receiveAll(S.send(m1.number, channel, Fault.NONE), channel);
            // Message was applied (C = 150) but ack was never sent
            // S still thinks m1 is unacked

            System.out.println("\n  [Receiver recovers]");
            R.recover();

            System.out.printf("  C = %d (operation was applied before crash)%n",
                    R.getDataStore().get("C"));

            System.out.println("\n  [Attempt 2: sender retransmits]");
            // Receiver sees #1 already in received_messages → duplicate → not re-applied
            R.receiveAll(S.send(m1.number, channel, Fault.NONE), channel);
            S.receiveAck(m1.number);

            System.out.printf("%n  C = %d (still 150 - operation applied exactly once)%n",
                    R.getDataStore().get("C"));
            System.out.printf("  This proves why the ack must be sent AFTER commit, not before:%n");
            System.out.printf("  If we had sent ack before commit, a crash would lose the message.%n");
        }

        scenario("PM-6: Out-of-order delivery - msg #2 arrives before msg #1");
        {
            var channel = new NetworkChannel();
            var S = new Sender("S");
            var R = new Receiver("R");
            R.initDataStore("X", 0);

            var m1 = S.enqueue("X ← X + 10");  // sent first, arrives second
            var m2 = S.enqueue("X ← X + 20");  // sent second, arrives first

            System.out.println("\n  [m2 arrives first (delayed delivery of m1)]");
            // Delay m1 (it's in transit), deliver m2 immediately
            S.send(m1.number, channel, Fault.DELAY);
            R.receiveAll(S.send(m2.number, channel, Fault.NONE), channel);
            S.receiveAck(m2.number);

            System.out.println("\n  [State: only m2 processed so far]");
            S.printTable();
            R.printReceivedTable();
            System.out.printf("  T_OLD = %d (m1 still unacked, even though m2 is done)%n",
                    S.computeTOld());

            System.out.println("\n  [Delayed m1 finally arrives]");
            R.receiveAll(channel.flushDelayed(), channel);
            S.receiveAck(m1.number);

            System.out.println("\n  [Final state]");
            S.printTable();
            R.printDataStore();
            S.computeTOld();
        }

        scenario("PM-7: T_OLD advancement - step-by-step table cleanup");
        {
            var channel = new NetworkChannel();
            var S = new Sender("S");
            var R = new Receiver("R");

            // Pre-load 5 messages - some already acked (as in the exam table)
            var m1 = S.enqueueWithTime(1, "Q ← Q + 9", 2, true);   // acked
            var m3 = S.enqueueWithTime(3, "A ← A + 3", 3, true);   // acked
            var m7 = S.enqueueWithTime(7, "Q ← Q + 3", 5, false);  // NOT acked
            var m8 = S.enqueueWithTime(8, "B ← B - 9", 7, true);   // acked
            var m9 = S.enqueueWithTime(9, "C ← C - 6", 8, false);  // NOT acked

            // Pre-load receiver's table to match exam state
            R.preloadReceivedMessage(7, "Q ← Q + 3", 5);
            R.preloadReceivedMessage(8, "B ← B - 9", 7);
            R.preloadReceivedMessage(9, "C ← C - 6", 8);

            System.out.println("\n  [Initial state - matches Midterm II Table 1]");
            S.printTable();
            R.printReceivedTable();

            System.out.println("\n  [Round 1: compute and apply T_OLD]");
            // Unacked: m7 (t=5), m9 (t=8) → T_OLD = 5
            long tOld1 = S.computeTOld();
            R.applyTOld(tOld1);
            System.out.println();
            R.printReceivedTable();

            System.out.println("\n  [Round 2: ack arrives for m9]");
            S.receiveAck(m9.number);
            // Unacked: only m7 (t=5) → T_OLD = 5 (unchanged!)
            long tOld2 = S.computeTOld();
            R.applyTOld(tOld2);
            R.printReceivedTable();

            System.out.println("\n  [Round 3: ack arrives for m7]");
            S.receiveAck(m7.number);
            // All acked → T_OLD = ∞
            long tOld3 = S.computeTOld();
            R.applyTOld(tOld3);
            System.out.println();
            R.printReceivedTable();
        }
    }

    // ── Exact exam scenario from Midterm II Exercise 2 ────────────────────────

    private static void examScenario() {
        scenario("EXAM: Midterm II Exercise 2 - exact table state from exam\n" +
                 "       Task: (1) compute T_OLD after ack for msg#9 arrives,\n" +
                 "             (2) show received_messages after applying T_OLD");

        var S = new Sender("S");
        var R = new Receiver("R");

        // Recreate the exact messages_to_send table from the exam:
        //   number | message      | time | ack
        //   1      | Q ← Q + 9   | 2    | received
        //   3      | A ← A + 3   | 3    | received
        //   7      | Q ← Q + 3   | 5    |
        //   8      | B ← B − 9   | 7    | received
        //   9      | C ← C − 6   | 8    |
        S.enqueueWithTime(1, "Q ← Q + 9", 2, true);
        S.enqueueWithTime(3, "A ← A + 3", 3, true);
        S.enqueueWithTime(7, "Q ← Q + 3", 5, false);
        S.enqueueWithTime(8, "B ← B - 9", 7, true);
        S.enqueueWithTime(9, "C ← C - 6", 8, false);

        // Recreate the exact received_messages table from the exam:
        //   number | message      | time | ack
        //   7      | Q ← Q + 3   | 5    | sent
        //   8      | B ← B − 9   | 7    | sent
        //   9      | C ← C − 6   | 8    | sent
        R.preloadReceivedMessage(7, "Q ← Q + 3", 5);
        R.preloadReceivedMessage(8, "B ← B - 9", 7);
        R.preloadReceivedMessage(9, "C ← C - 6", 8);

        System.out.println("\n  [Exact exam table state]");
        S.printTable();
        System.out.println();
        R.printReceivedTable();

        // Part (1): S receives ack for message number 9
        System.out.println("\n  [Part 1: S receives ack for message #9]");
        S.receiveAck(9L);
        S.printTable();

        // Compute T_OLD:
        // Unacked messages: #7 (time=5) - the only remaining unacked message
        // T_OLD = min(time of unacked messages) = 5
        System.out.println();
        long tOld = S.computeTOld();

        System.out.println("\n  ── Answer to Part 1 ──────────────────────────────");
        System.out.printf("  T_OLD = %d%n", tOld);
        System.out.println("  Reasoning: after acking msg#9, unacked messages are:");
        System.out.println("    - msg#7 (time=5)");
        System.out.println("  T_OLD = min{5} = 5");

        // Part (2): Show received_messages after receiver applies T_OLD
        System.out.println("\n  [Part 2: apply T_OLD to received_messages]");
        System.out.println("  Removing all rows with time < T_OLD = " + tOld);
        R.applyTOld(tOld);

        System.out.println("\n  ── Answer to Part 2 ──────────────────────────────");
        R.printReceivedTable();
        System.out.println("  Reasoning: no row has time < 5 (all rows have time ≥ 5)");
        System.out.println("  Therefore received_messages is UNCHANGED.");
    }

    // ── Utility ───────────────────────────────────────────────────────────────

    private static void scenario(String description) {
        System.out.printf("%n%n");
        System.out.printf("======================================================%n");
        System.out.printf("SCENARIO: %s%n", description);
        System.out.printf("======================================================%n%n");
    }
}
