package io.github.thgrcarvalho.zelo.infrastructure.web;

import io.github.thgrcarvalho.idempotency.CachedResponse;
import io.github.thgrcarvalho.idempotency.IdempotencyStore;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The idempotency store, minus one memoization: a 429 is never cached. That
 * status' contract is "try again later", but a cached entry replays the refusal
 * for the key's whole TTL — so a client that retried a quota-refused write with
 * the same {@code Idempotency-Key} after the month rolled over (or after
 * upgrading to PRO) would keep getting the stale 429 instead of the write
 * executing. Every other outcome, success or definitive 4xx, replays as usual.
 *
 * <p>Mirrors the starter's in-memory default otherwise (fine for this
 * single-instance deployment) rather than decorating it, because that class
 * lives in the library's {@code .internal} package and isn't stable API.</p>
 */
final class QuotaAwareIdempotencyStore implements IdempotencyStore {

    private record Entry(CachedResponse response, Instant expiresAt) {}

    private final ConcurrentHashMap<String, Entry> entries = new ConcurrentHashMap<>();

    @Override
    public Optional<CachedResponse> get(String key) {
        Entry entry = entries.get(key);
        if (entry == null) {
            return Optional.empty();
        }
        if (Instant.now().isAfter(entry.expiresAt())) {
            entries.remove(key, entry);
            return Optional.empty();
        }
        return Optional.of(entry.response());
    }

    @Override
    public void put(String key, CachedResponse response, Duration ttl) {
        if (response.status() == 429) {
            return;
        }
        // putIfAbsent: first writer wins in the rare concurrent-request case
        entries.putIfAbsent(key, new Entry(response, Instant.now().plus(ttl)));
    }

    @Override
    public long size() {
        return entries.size();
    }
}
