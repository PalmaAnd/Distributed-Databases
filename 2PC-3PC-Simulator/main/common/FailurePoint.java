package main.common;

/**
 * Controls exactly where in the protocol the coordinator is injected to fail.
 *
 * This lets you explore every interesting failure scenario from the lecture:
 *
 *  NONE                - no failure; happy path
 *  BEFORE_PREPARE      - coordinator crashes before even starting Phase 1
 *  AFTER_PREPARE       - coordinator crashes after sending prepare, before collecting votes
 *  AFTER_DECISION      - coordinator crashes AFTER writing commit/abort to its own log
 *                        but BEFORE sending the decision to participants.
 *                        --> This is the BLOCKING scenario in 2PC:
 *                            all participants have <ready T>, nobody has the answer.
 *  AFTER_SOME_COMMITS  - coordinator sent commit to some participants but not all,
 *                        then crashed. 3PC prevents this from causing inconsistency.
 */
public enum FailurePoint {
    NONE,
    BEFORE_PREPARE,
    AFTER_PREPARE,
    AFTER_DECISION,       // the critical 2PC blocking scenario
    AFTER_SOME_COMMITS
}
