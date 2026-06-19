package main;

import main.common.FailurePoint;
import main.threepc.ThreePhaseCoordinator;
import main.threepc.ThreePhaseParticipant;
import main.twopc.TwoPhaseCoordinator;
import main.twopc.TwoPhaseParticipant;

import java.util.List;

/**
 *  This simulator runs eight scenarios covering all exam-relevant cases.
 *
 *  HOW TO READ THE OUTPUT
 *  ──────────────────────
 *  [LOG site]  = a log entry written to stable storage (survives crashes)
 *  [site]      = an action or message received by that site
 *  *** text *** = a crash or key observation
 *
 *  SCENARIOS
 *  ─────────
 *  2PC-1  Happy path: all participants vote READY, transaction commits
 *  2PC-2  One participant votes ABORT, transaction aborts
 *  2PC-3  *** THE BLOCKING SCENARIO ***
 *          Coordinator crashes after writing <COMMIT T> but before
 *          telling participants. All participants have <READY T> only.
 *          They hold locks and cannot proceed.
 *  2PC-4  Participant crashes before Phase 1, then recovers
 *
 *  3PC-1  Happy path: all three phases complete normally
 *  3PC-2  One participant votes ABORT -> clean abort via Phase 2
 *  3PC-3  Coordinator crashes after distributing PRECOMMIT
 *          -> Participants have <PRECOMMIT T> -> new coordinator can commit
 *          -> NO BLOCKING (compare with 2PC-3)
 *  3PC-4  Participant misses PRECOMMIT message, then recovers
 *
 * ===========================================================================
 */
public class CommitSimulator {

    public static void main(String[] args) {

        // ────────────────────────────────────────────────────────────────────
        // 2PC SCENARIOS
        // ────────────────────────────────────────────────────────────────────

        scenario("2PC-1: Happy path - all participants ready");
        {
            var coord = new TwoPhaseCoordinator("C");
            var p1 = new TwoPhaseParticipant("S1", true);
            var p2 = new TwoPhaseParticipant("S2", true);
            var p3 = new TwoPhaseParticipant("S3", true);
            coord.runProtocol("T1", List.of(p1, p2, p3), FailurePoint.NONE);
        }

        // ────────────────────────────────────────────────────────────────────

        scenario("2PC-2: One participant votes ABORT");
        {
            var coord = new TwoPhaseCoordinator("C");
            var p1 = new TwoPhaseParticipant("S1", true);
            var p2 = new TwoPhaseParticipant("S2", false); // <-- votes ABORT
            var p3 = new TwoPhaseParticipant("S3", true);
            coord.runProtocol("T2", List.of(p1, p2, p3), FailurePoint.NONE);
        }

        // ────────────────────────────────────────────────────────────────────

        scenario("2PC-3: *** THE BLOCKING PROBLEM ***\n" +
                 "       Coordinator crashes after writing decision, before sending it.\n" +
                 "       All participants have <READY T> and are stuck.");
        {
            var coord = new TwoPhaseCoordinator("C");
            var p1 = new TwoPhaseParticipant("S1", true);
            var p2 = new TwoPhaseParticipant("S2", true);
            var p3 = new TwoPhaseParticipant("S3", true);
            coord.runProtocol("T3", List.of(p1, p2, p3), FailurePoint.AFTER_DECISION);

            // Now show what recovery looks like for each participant
            System.out.printf("%n  --- Recovery attempts for participants ---%n");
            System.out.printf("  (Coordinator is still down - they cannot get an answer)%n");
            p1.recover("T3");
            p2.recover("T3");
            p3.recover("T3");
        }

        // ────────────────────────────────────────────────────────────────────

        scenario("2PC-4: Participant S2 crashes before Phase 1, then recovers");
        {
            var coord = new TwoPhaseCoordinator("C");
            var p1 = new TwoPhaseParticipant("S1", true);
            var p2 = new TwoPhaseParticipant("S2", true);
            var p3 = new TwoPhaseParticipant("S3", true);

            p2.crash(); // crashes before receiving PREPARE

            // Coordinator gets no response from S2, treats it as ABORT vote
            // (timeout-based), aborts the transaction
            coord.runProtocol("T4", List.of(p1, p2, p3), FailurePoint.NONE);

            System.out.printf("%n  --- S2 recovers ---%n");
            p2.recover("T4"); // finds nothing in log -> undo
        }

        // ────────────────────────────────────────────────────────────────────
        // 3PC SCENARIOS
        // ────────────────────────────────────────────────────────────────────

        scenario("3PC-1: Happy path - all three phases complete");
        {
            var coord = new ThreePhaseCoordinator("C", 3);
            var p1 = new ThreePhaseParticipant("S1", true);
            var p2 = new ThreePhaseParticipant("S2", true);
            var p3 = new ThreePhaseParticipant("S3", true);
            coord.runProtocol("T5", List.of(p1, p2, p3), FailurePoint.NONE);
        }

        // ────────────────────────────────────────────────────────────────────

        scenario("3PC-2: One participant votes ABORT -> clean abort");
        {
            var coord = new ThreePhaseCoordinator("C", 3);
            var p1 = new ThreePhaseParticipant("S1", true);
            var p2 = new ThreePhaseParticipant("S2", false); // <-- votes ABORT
            var p3 = new ThreePhaseParticipant("S3", true);
            coord.runProtocol("T6", List.of(p1, p2, p3), FailurePoint.NONE);
        }

        // ────────────────────────────────────────────────────────────────────

        scenario("3PC-3: *** KEY INSIGHT - No blocking after coordinator crash ***\n" +
                 "       Coordinator crashes after PRECOMMIT distributed.\n" +
                 "       Compare recovery with 2PC-3: participants are NOT stuck.");
        {
            var coord = new ThreePhaseCoordinator("C", 3);
            var p1 = new ThreePhaseParticipant("S1", true);
            var p2 = new ThreePhaseParticipant("S2", true);
            var p3 = new ThreePhaseParticipant("S3", true);
            coord.runProtocol("T7", List.of(p1, p2, p3), FailurePoint.AFTER_DECISION);

            System.out.printf("%n  --- Recovery for each participant ---%n");
            System.out.printf("  (Compare with 2PC-3: these sites can continue!) %n");
            p1.recover("T7");
            p2.recover("T7");
            p3.recover("T7");
        }

        // ────────────────────────────────────────────────────────────────────

        scenario("3PC-4: Participant S2 misses PRECOMMIT, then recovers");
        {
            var coord = new ThreePhaseCoordinator("C", 2); // only need 2 of 3 acks
            var p1 = new ThreePhaseParticipant("S1", true);
            var p2 = new ThreePhaseParticipant("S2", true);
            var p3 = new ThreePhaseParticipant("S3", true);

            p2.crash(); // crashes before PRECOMMIT is sent

            coord.runProtocol("T8", List.of(p1, p2, p3), FailurePoint.NONE);

            System.out.printf("%n  --- S2 recovers ---%n");
            p2.recover("T8");
        }

        // ────────────────────────────────────────────────────────────────────
        // SIDE-BY-SIDE COMPARISON SUMMARY
        // ────────────────────────────────────────────────────────────────────

        System.out.printf("%n%n");
        System.out.printf("╔======================================================================╗%n");
        System.out.printf("║              2PC vs 3PC - Key Comparison                            ║%n");
        System.out.printf("╠======================================================================╣%n");
        System.out.printf("║ Scenario               │ 2PC outcome          │ 3PC outcome          ║%n");
        System.out.printf("╠======================================================================╣%n");
        System.out.printf("║ Happy path             │ commits              │ commits              ║%n");
        System.out.printf("║ One no vote            │ aborts               │ aborts               ║%n");
        System.out.printf("║ Coordinator crash      │ BLOCKS (all ready,   │ No block - sites     ║%n");
        System.out.printf("║ after decision         │ no decision known)   │ with <precommit T>   ║%n");
        System.out.printf("║                        │                      │ reveal the decision  ║%n");
        System.out.printf("║ Network partition      │ May block            │ Still blocks (3PC    ║%n");
        System.out.printf("║                        │                      │ assumes no partition)║%n");
        System.out.printf("║ Phase 1                │ prepare->ready/abort │ IDENTICAL            ║%n");
        System.out.printf("║ Log entry after Phase 1│ <ready T>            │ <ready T>            ║%n");
        System.out.printf("║ Log entry after Phase 2│ <commit/abort T>     │ <precommit/abort T>  ║%n");
        System.out.printf("╚======================================================================╝%n");
    }

    private static void scenario(String description) {
        System.out.printf("%n%n");
        System.out.printf("======================================================%n");
        System.out.printf("SCENARIO: %s%n", description);
        System.out.printf("======================================================%n");
    }
}
