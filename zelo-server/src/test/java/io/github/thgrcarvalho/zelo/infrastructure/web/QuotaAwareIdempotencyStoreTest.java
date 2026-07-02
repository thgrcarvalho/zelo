package io.github.thgrcarvalho.zelo.infrastructure.web;

import io.github.thgrcarvalho.idempotency.CachedResponse;
import io.github.thgrcarvalho.idempotency.IdempotencyStore;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class QuotaAwareIdempotencyStoreTest {

    private static final Duration TTL = Duration.ofHours(1);

    private final IdempotencyStore store = new QuotaAwareIdempotencyStore();

    @Test
    void neverMemoizesA429() {
        store.put("quota-key", response(429), TTL);
        assertThat(store.get("quota-key")).isEmpty();
    }

    @Test
    void cachesSuccessAndDefinitive4xxAsUsual() {
        store.put("ok-key", response(200), TTL);
        store.put("conflict-key", response(409), TTL);

        assertThat(store.get("ok-key")).hasValueSatisfying(r -> assertThat(r.status()).isEqualTo(200));
        assertThat(store.get("conflict-key")).hasValueSatisfying(r -> assertThat(r.status()).isEqualTo(409));
    }

    @Test
    void firstWriterWinsForTheSameKey() {
        store.put("race-key", response(201), TTL);
        store.put("race-key", response(200), TTL);
        assertThat(store.get("race-key")).hasValueSatisfying(r -> assertThat(r.status()).isEqualTo(201));
    }

    @Test
    void expiredEntriesAreGone() {
        store.put("expired-key", response(200), Duration.ofSeconds(-1));
        assertThat(store.get("expired-key")).isEmpty();
    }

    @Test
    void sizeCountsOnlyWhatWasActuallyStored() {
        store.put("kept", response(201), TTL);
        store.put("skipped", response(429), TTL);
        assertThat(store.size()).isEqualTo(1);
    }

    private static CachedResponse response(int status) {
        return new CachedResponse(status, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
    }
}
