package main.threepc;

import main.common.FailurePoint;
import main.common.LogEntry;
import main.common.SiteLog;

import java.util.List;

/**
 * The 3PC coordinator.
 *
 * Adds Phase 2 (PRECOMMIT) between the vote collection and the final commit.
 * This intermediate phase is what eliminates the blocking problem:
 * if the coordinator crashes after sending PRECOMMIT to at least one participant,
 * that participant's log reveals the intent, and a new coordinator can finish.
 *
 * Assumption: no network partitioning; at most K sites can fail simultaneously.
 */
public class ThreePhaseCoordinator {

    private final String siteId;
    private final SiteLog log;
    private boolean crashed = false;

    // K = how many participant acknowledgements we need before committing.
    // In a real system K = total participants, but for the demo we allow K=1
    // to show that even partial acknowledgement is enough to avoid blocking.
    private final int requiredAcks;

    public ThreePhaseCoordinator(String siteId, int requiredAcks) {
        this.siteId = siteId;
        this.log = new SiteLog(siteId);
        this.requiredAcks = requiredAcks;
    }

    public void runProtocol(String txId,
                            List<ThreePhaseParticipant> participants,
                            FailurePoint failure) {

        System.out.printf("%n╔======================================╗%n");
        System.out.printf("║  3PC - transaction %-18s║%n", txId);
        System.out.printf("╚======================================╝%n");

        if (failure == FailurePoint.BEFORE_PREPARE) {
            simulateCrash("before sending PREPARE"); return;
        }

        // ====================================================================
        // PHASE 1 - PREPARE (identical to 2PC Phase 1)
        // ====================================================================
        System.out.printf("%n--- Phase 1: Prepare (identical to 2PC) ---%n");
        log.write(LogEntry.Type.PREPARE, txId);

        boolean allReady = true;
        for (ThreePhaseParticipant p : participants) {
            if (!p.prepare(txId)) { allReady = false; }
        }

        if (failure == FailurePoint.AFTER_PREPARE) {
            simulateCrash("after collecting votes"); return;
        }

        // ====================================================================
        // PHASE 2 - PRECOMMIT (new in 3PC)
        // ====================================================================
        System.out.printf("%n--- Phase 2: Pre-commit decision ---%n");

        if (!allReady) {
            // Abort path: same as 2PC - broadcast abort
            log.write(LogEntry.Type.ABORT, txId);
            System.out.printf("  [%s] At least one ABORT vote -> broadcasting ABORT%n", siteId);
            for (ThreePhaseParticipant p : participants) p.abortPhase2(txId);
            System.out.printf("%n  [%s] Protocol complete (aborted).%n", siteId);
            return;
        }

        // All voted ready -> broadcast PRECOMMIT
        log.write(LogEntry.Type.PRECOMMIT, txId);
        System.out.printf("  [%s] All ready -> logging <PRECOMMIT T>, broadcasting PRECOMMIT%n", siteId);

        int acks = 0;
        for (ThreePhaseParticipant p : participants) {
            if (p.precommit(txId)) acks++;
        }
        System.out.printf("  [%s] Received %d/%d acknowledgements%n", siteId, acks, participants.size());

        // ── Crash AFTER PRECOMMIT is distributed ─────────────────────────────
        // CRITICAL COMPARISON WITH 2PC:
        // In 2PC's equivalent moment (all ready, coordinator writes commit but hasn't sent it),
        // participants only have <READY T> -> fate unknown -> they BLOCK.
        //
        // Here, participants that received PRECOMMIT have <PRECOMMIT T> in their log.
        // A new coordinator can inspect those logs and know: the decision was COMMIT.
        // -> 3PC does NOT block (under the no-partition assumption).
        if (failure == FailurePoint.AFTER_DECISION) {
            simulateCrash("after distributing PRECOMMIT (equivalent to 2PC blocking moment)");
            System.out.printf("%n  *** KEY DIFFERENCE vs 2PC demonstrated ***%n");
            System.out.printf("  Participants with <PRECOMMIT T> tell any new coordinator: COMMIT.%n");
            System.out.printf("  Participants with only <READY T> tell any new coordinator: safe to ABORT.%n");
            System.out.printf("  Either way the protocol can continue - no blocking!%n");
            return;
        }

        // ====================================================================
        // PHASE 3 - COMMIT
        // ====================================================================
        System.out.printf("%n--- Phase 3: Final commit ---%n");

        if (acks < requiredAcks) {
            System.out.printf("  [%s] Not enough acks (%d < %d required) -> abort%n",
                    siteId, acks, requiredAcks);
            log.write(LogEntry.Type.ABORT, txId);
            for (ThreePhaseParticipant p : participants) p.abortPhase2(txId);
            return;
        }

        log.write(LogEntry.Type.COMMIT, txId);
        System.out.printf("  [%s] Sufficient acks received -> broadcasting COMMIT%n", siteId);

        int sent = 0;
        for (ThreePhaseParticipant p : participants) {
            if (failure == FailurePoint.AFTER_SOME_COMMITS && sent == 1) {
                simulateCrash("after committing " + sent + " participant(s)");
                return;
            }
            p.commit(txId);
            sent++;
        }

        System.out.printf("%n  [%s] Protocol complete. All participants committed.%n", siteId);
    }

    private void simulateCrash(String when) {
        crashed = true;
        System.out.printf("%n  [%s] *** COORDINATOR CRASHED: %s ***%n%n", siteId, when);
    }

    public boolean isCrashed() { return crashed; }
    public SiteLog getLog()    { return log; }
    public String getSiteId()  { return siteId; }
}
