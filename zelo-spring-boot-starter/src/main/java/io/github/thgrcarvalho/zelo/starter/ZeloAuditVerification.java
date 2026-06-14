package io.github.thgrcarvalho.zelo.starter;

/**
 * The result of asking Zelo to recompute and verify your tamper-evident audit
 * chain ({@code GET /v1/audit/verify}). When {@code ok} is false,
 * {@code firstBrokenEntryId} points at the first entry that fails to verify.
 *
 * @param ok                  whether the whole chain verifies
 * @param entriesChecked      how many entries were recomputed
 * @param firstBrokenEntryId  the first broken link, or {@code null} when {@code ok}
 * @param detail              a human-readable explanation
 */
public record ZeloAuditVerification(boolean ok, long entriesChecked,
                                    Long firstBrokenEntryId, String detail) {
}
