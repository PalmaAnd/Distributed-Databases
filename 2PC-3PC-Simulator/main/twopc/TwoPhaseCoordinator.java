package main.twopc;

import main.common.FailurePoint;
import main.common.LogEntry;
import main.common.SiteLog;

import java.util.List;

/**
 * The 2PC coordinator.
 *
 * Drives both phases of the protocol. Accepts a FailurePoint to simulate
 * crashes at precise moments and expose the blocking problem.
 */
public class TwoPhaseCoordinator {

    private final String siteId;
    private final SiteLog log;
    private boolean crashed = false;

    public TwoPhaseCoordinator(String siteId) {
        this.siteId = siteId;
        this.log = new SiteLog(siteId);
    }

    /**
     * Run the full 2PC protocol.
     *
     * @param txId         transaction identifier
     * @param participants all participant sites
     * @param failure      where to inject a coordinator crash
     */
    public void runProtocol(String txId,
                            List<TwoPhaseParticipant> participants,
                            FailurePoint failure) {

        System.out.printf("%n╔======================================╗%n");
        System.out.printf("║  2PC - transaction %-18s║%n", txId);
        System.out.printf("╚======================================╝%n");

        // ── Inject crash before we even start ────────────────────────────────
        if (failure == FailurePoint.BEFORE_PREPARE) {
            simulateCrash("before sending PREPARE");
            return;
        }

        // ====================================================================
        // PHASE 1 - PREPARE
        // ====================================================================
        System.out.printf("%n--- Phase 1: Prepare ---%n");
        log.write(LogEntry.Type.PREPARE, txId);

        boolean allReady = true;
        for (TwoPhaseParticipant p : participants) {
            boolean vote = p.prepare(txId);
            if (!vote) { allReady = false; }
        }

        // ── Inject crash after collecting votes, before writing decision ──────
        if (failure == FailurePoint.AFTER_PREPARE) {
            simulateCrash("after collecting votes, before writing decision");
            return;
        }

        // ====================================================================
        // PHASE 2 - DECISION
        // ====================================================================
        System.out.printf("%n--- Phase 2: Decision ---%n");

        if (allReady) {
            log.write(LogEntry.Type.COMMIT, txId);
            System.out.printf("  [%s] Decision: COMMIT%n", siteId);
        } else {
            log.write(LogEntry.Type.ABORT, txId);
            System.out.printf("  [%s] Decision: ABORT (at least one participant voted no)%n", siteId);
        }

        // ── Inject crash AFTER writing decision but BEFORE telling anyone ─────
        // This is THE critical blocking scenario in 2PC:
        //   - All participants have <ready T> in their log
        //   - Nobody has received the commit/abort message
        //   - Coordinator is down -> participants block holding locks forever
        if (failure == FailurePoint.AFTER_DECISION) {
            simulateCrash("after writing decision to log, before sending to participants");
            System.out.printf("%n  *** BLOCKING PROBLEM DEMONSTRATED ***%n");
            System.out.printf("  All participants have <READY T> but no decision.%n");
            System.out.printf("  They hold all their locks and CANNOT proceed.%n");
            System.out.printf("  They must wait until coordinator recovers.%n");
            return;
        }

        // ── Broadcast decision ────────────────────────────────────────────────
        int sent = 0;
        for (TwoPhaseParticipant p : participants) {
            // Inject crash mid-broadcast (after some but not all are notified)
            if (failure == FailurePoint.AFTER_SOME_COMMITS && sent == 1) {
                simulateCrash("after sending commit to " + sent + " participant(s)");
                return;
            }
            if (allReady) p.commit(txId);
            else          p.abort(txId);
            sent++;
        }

        System.out.printf("%n  [%s] Protocol complete. All participants notified.%n", siteId);
    }

    private void simulateCrash(String when) {
        crashed = true;
        System.out.printf("%n  [%s] *** COORDINATOR CRASHED: %s ***%n%n", siteId, when);
    }

    public boolean isCrashed() { return crashed; }
    public SiteLog getLog()    { return log; }
    public String getSiteId()  { return siteId; }
}
