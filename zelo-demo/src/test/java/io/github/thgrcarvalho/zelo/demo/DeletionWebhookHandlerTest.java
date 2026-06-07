package io.github.thgrcarvalho.zelo.demo;

import io.github.thgrcarvalho.zelo.starter.ZeloDeletionRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeletionWebhookHandlerTest {

    private final UserStore store = new UserStore();
    private final DeletionWebhookHandler handler = new DeletionWebhookHandler(store);

    @Test
    void erasesTheUserAndReturnsProof() {
        store.save(new DemoUser("u1", "Alice", "alice@example.com"));

        Map<String, Object> proof = handler.onDeletionRequested(
                new ZeloDeletionRequest("req-1", "u1", "2026-06-21T00:00:00Z"));

        assertThat(store.find("u1")).isEmpty();
        assertThat(proof).containsEntry("deletedRows", 1).containsEntry("externalId", "u1");
    }

    @Test
    void reportsZeroRowsWhenTheUserIsAlreadyGone() {
        Map<String, Object> proof = handler.onDeletionRequested(
                new ZeloDeletionRequest("req-1", "ghost", "2026-06-21T00:00:00Z"));

        assertThat(proof).containsEntry("deletedRows", 0);
    }
}
