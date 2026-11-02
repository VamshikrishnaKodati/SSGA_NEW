package SSGA_Backend.payroll;

/**
 * Finalization is irreversible until an explicit reopen, so the caller must
 * confirm it deliberately rather than by an accidental POST. The version is the
 * batch state the user reviewed; the server rejects a stale version.
 */
public record PayrollFinalizeRequest(Boolean confirmed, Long version) {
}
