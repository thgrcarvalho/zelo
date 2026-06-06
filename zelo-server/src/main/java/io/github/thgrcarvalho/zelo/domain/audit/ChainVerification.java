package io.github.thgrcarvalho.zelo.domain.audit;

/**
 * Result of recomputing an integrator's audit chain ("prove the proof").
 *
 * @param ok                 true if every link verified
 * @param entriesChecked     number of entries examined
 * @param firstBrokenEntryId id of the first entry that failed, or {@code null} if ok
 * @param detail             human-readable explanation of the first break, or {@code null}
 */
public record ChainVerification(
        boolean ok,
        long entriesChecked,
        Long firstBrokenEntryId,
        String detail
) {

    public static ChainVerification valid(long entriesChecked) {
        return new ChainVerification(true, entriesChecked, null, null);
    }

    public static ChainVerification broken(long entriesChecked, long firstBrokenEntryId, String detail) {
        return new ChainVerification(false, entriesChecked, firstBrokenEntryId, detail);
    }
}
