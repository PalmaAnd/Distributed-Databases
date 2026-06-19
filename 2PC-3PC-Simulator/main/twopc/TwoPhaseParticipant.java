package main.twopc;

import main.common.LogEntry;
import main.common.SiteLog;

/**
 * A 2PC participant site.
 *
 * Responsibilities:
 *   - Vote yes/no in Phase 1
 *   - Apply the coordinator's decision in Phase 2
 *   - Recover correctly after a crash
 *
 * The "canCommit" flag simulates whether the participant's local work
 * succeeded (e.g. no constraint violations, disk space available).
 */
public class TwoPhaseParticipant {

    private final String siteId;
    private final SiteLog log;
    private boolean canCommit;
    private boolean crashed = false;

    public TwoPhaseParticipant(String siteId, boolean canCommit) {
        this.siteId = siteId;
        this.log = new SiteLog(siteId);
        this.canCommit = canCommit;
    }

    // -------------------------------------------------------------------------
    // Phase 1: receive PREPARE, return vote
    // -------------------------------------------------------------------------

    /**
     * Receive a PREPARE message from the coordinator.
     *
     * @return true = READY (yes vote), false = ABORT (no vote)
     *
     * IMPORTANT: Once we log <READY T> and return true, we are permanently
     * obligated. We cannot unilaterally abort anymore - we gave up our autonomy.
     */
    public boolean prepare(String txId) {
        if (crashed) {
            System.out.printf("  [%s] is crashed - no response to PREPARE%n", siteId);
            return false; // simulate timeout from coordinator's perspective
        }
        if (canCommit) {
            log.write(LogEntry.Type.READY, txId);
            System.out.printf("  [%s] voted READY (yes)%n", siteId);
            return true;
        } else {
            log.write(LogEntry.Type.ABORT, txId);
            System.out.printf("  [%s] voted ABORT (no)%n", siteId);
            return false;
        }
    }

    // -------------------------------------------------------------------------
    // Phase 2: receive COMMIT or ABORT decision
    // -------------------------------------------------------------------------

    public void commit(String txId) {
        if (crashed) { System.out.printf("  [%s] crashed - missed COMMIT message%n", siteId); return; }
        log.write(LogEntry.Type.COMMIT, txId);
        System.out.printf("  [%s] committed transaction %s ✓%n", siteId, txId);
    }

    public void abort(String txId) {
        if (crashed) { System.out.printf("  [%s] crashed - missed ABORT message%n", siteId); return; }
        log.write(LogEntry.Type.ABORT, txId);
        System.out.printf("  [%s] aborted transaction %s ✗%n", siteId, txId);
    }

    // -------------------------------------------------------------------------
    // Recovery: called when a crashed site comes back online
    // -------------------------------------------------------------------------

    /**
     * Recovery protocol for a 2PC participant.
     *
     * EXAM TABLE:
     * ┌─────────────────────────────┬──────────────────────────────────────────┐
     * │ Log entry found             │ Action                                   │
     * ├─────────────────────────────┼──────────────────────────────────────────┤
     * │ <COMMIT T>                  │ Nothing - already committed              │
     * │ <ABORT T>                   │ Nothing - already aborted                │
     * │ <READY T> only              │ Unknown fate -> contact coordinator       │
     * │ Nothing about T             │ Coordinator aborted -> undo(T)            │
     * └─────────────────────────────┴──────────────────────────────────────────┘
     */
    public void recover(String txId) {
        crashed = false;
        System.out.printf("%n  [%s] RECOVERING for transaction %s...%n", siteId, txId);
        log.printAll();

        boolean hasCommit    = log.contains(LogEntry.Type.COMMIT, txId);
        boolean hasAbort     = log.contains(LogEntry.Type.ABORT,  txId);
        boolean hasReady     = log.contains(LogEntry.Type.READY,  txId);

        if (hasCommit) {
            System.out.printf("  [%s] Found <COMMIT T> -> already committed, nothing to do.%n", siteId);
        } else if (hasAbort) {
            System.out.printf("  [%s] Found <ABORT T> -> already aborted, nothing to do.%n", siteId);
        } else if (hasReady) {
            System.out.printf("  [%s] Found <READY T> only -> fate unknown!%n", siteId);
            System.out.printf("  [%s] *** MUST contact coordinator to learn decision. ***%n", siteId);
            System.out.printf("  [%s] *** Holding all locks until resolved. ***%n", siteId);
            // In a real system: send a query to the coordinator and wait.
            // If coordinator is also down, this site BLOCKS - the 2PC problem.
        } else {
            System.out.printf("  [%s] No log entry for T -> coordinator aborted before reaching us.%n", siteId);
            System.out.printf("  [%s] Executing undo(%s).%n", siteId, txId);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    public void crash() {
        crashed = true;
        System.out.printf("  [%s] *** CRASHED ***%n", siteId);
    }

    public String getSiteId() { return siteId; }
    public SiteLog getLog()   { return log; }
    public boolean isCrashed(){ return crashed; }
}
