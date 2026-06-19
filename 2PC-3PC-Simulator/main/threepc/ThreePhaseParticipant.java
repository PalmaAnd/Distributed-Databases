package main.threepc;

import main.common.LogEntry;
import main.common.SiteLog;

/**
 * A 3PC participant site.
 *
 * The key difference from 2PC: there is an intermediate PRECOMMIT state.
 * Once a participant logs <PRECOMMIT T>, it knows the coordinator decided to
 * commit - so a new coordinator can complete the protocol without the original.
 *
 * Recovery table (exam version):
 * ┌──────────────────────────┬──────────────────────────────────────────────────┐
 * │ Log entry found          │ Action                                           │
 * ├──────────────────────────┼──────────────────────────────────────────────────┤
 * │ <COMMIT T>               │ Nothing - already committed                      │
 * │ <ABORT T>                │ Nothing - already aborted                        │
 * │ <PRECOMMIT T> only       │ Ask coordinator; if still precommit -> send ACK   │
 * │ <READY T> only           │ Ask coordinator                                  │
 * │ Nothing                  │ undo(T), write <ABORT T>                         │
 * └──────────────────────────┴──────────────────────────────────────────────────┘
 */
public class ThreePhaseParticipant {

    private final String siteId;
    private final SiteLog log;
    private final boolean canCommit;
    private boolean crashed = false;

    public ThreePhaseParticipant(String siteId, boolean canCommit) {
        this.siteId = siteId;
        this.log = new SiteLog(siteId);
        this.canCommit = canCommit;
    }

    // ── Phase 1: vote ─────────────────────────────────────────────────────────

    public boolean prepare(String txId) {
        if (crashed) {
            System.out.printf("  [%s] is crashed - no response to PREPARE%n", siteId);
            return false;
        }
        if (canCommit) {
            log.write(LogEntry.Type.READY, txId);
            System.out.printf("  [%s] voted READY%n", siteId);
            return true;
        } else {
            log.write(LogEntry.Type.ABORT, txId);
            System.out.printf("  [%s] voted ABORT%n", siteId);
            return false;
        }
    }

    // ── Phase 2: receive PRECOMMIT or ABORT ───────────────────────────────────

    /**
     * Receive PRECOMMIT from coordinator.
     *
     * Writing <PRECOMMIT T> is the key difference from 2PC:
     * any surviving site can now take over and complete the commit.
     *
     * @return true = acknowledgement sent back to coordinator
     */
    public boolean precommit(String txId) {
        if (crashed) {
            System.out.printf("  [%s] crashed - missed PRECOMMIT message%n", siteId);
            return false;
        }
        log.write(LogEntry.Type.PRECOMMIT, txId);
        System.out.printf("  [%s] received PRECOMMIT, logging and sending ACK%n", siteId);
        return true; // ACK
    }

    public void abortPhase2(String txId) {
        if (crashed) { System.out.printf("  [%s] crashed - missed ABORT (phase 2)%n", siteId); return; }
        log.write(LogEntry.Type.ABORT, txId);
        System.out.printf("  [%s] aborted transaction %s ✗%n", siteId, txId);
    }

    // ── Phase 3: final commit ─────────────────────────────────────────────────

    public void commit(String txId) {
        if (crashed) { System.out.printf("  [%s] crashed - missed COMMIT (phase 3)%n", siteId); return; }
        log.write(LogEntry.Type.COMMIT, txId);
        System.out.printf("  [%s] committed transaction %s ✓%n", siteId, txId);
    }

    // ── Recovery ──────────────────────────────────────────────────────────────

    public void recover(String txId) {
        crashed = false;
        System.out.printf("%n  [%s] RECOVERING for transaction %s...%n", siteId, txId);
        log.printAll();

        boolean hasCommit    = log.contains(LogEntry.Type.COMMIT,     txId);
        boolean hasAbort     = log.contains(LogEntry.Type.ABORT,      txId);
        boolean hasPrecommit = log.contains(LogEntry.Type.PRECOMMIT,  txId);
        boolean hasReady     = log.contains(LogEntry.Type.READY,      txId);

        if (hasCommit) {
            System.out.printf("  [%s] Found <COMMIT T> -> already committed, nothing to do.%n", siteId);
        } else if (hasAbort) {
            System.out.printf("  [%s] Found <ABORT T> -> already aborted, nothing to do.%n", siteId);
        } else if (hasPrecommit) {
            System.out.printf("  [%s] Found <PRECOMMIT T> -> coordinator decided COMMIT.%n", siteId);
            System.out.printf("  [%s] Contacting coordinator/peers to resume Phase 3.%n", siteId);
            System.out.printf("  [%s] If no coordinator, new coordinator can safely commit.%n", siteId);
            // KEY INSIGHT: Unlike 2PC <ready T>, <precommit T> tells us the decision.
            // A new coordinator elected by peers can commit without the original coordinator.
        } else if (hasReady) {
            System.out.printf("  [%s] Found <READY T> only -> contact coordinator.%n", siteId);
            System.out.printf("  [%s] New coordinator can abort (nobody precommitted yet).%n", siteId);
        } else {
            System.out.printf("  [%s] No log entry -> undo(%s), write <ABORT T>.%n", siteId, txId);
        }
    }

    public void crash() {
        crashed = true;
        System.out.printf("  [%s] *** CRASHED ***%n", siteId);
    }

    public String getSiteId() { return siteId; }
    public SiteLog getLog()   { return log; }
    public boolean isCrashed(){ return crashed; }
}
