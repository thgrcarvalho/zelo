package io.github.thgrcarvalho.zelo.demo;

import io.github.thgrcarvalho.zelo.starter.ZeloDeletionRequest;
import io.github.thgrcarvalho.zelo.starter.ZeloWebhook;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Map;

/**
 * The integrator's erasure logic. When Zelo fires {@code dsr.delete.requested},
 * the starter calls this method; it deletes the user from the app's own store and
 * returns proof, which the starter relays back to Zelo as the fulfillment.
 */
@Component
public class DeletionWebhookHandler {

    private static final Logger log = LoggerFactory.getLogger(DeletionWebhookHandler.class);

    private final UserStore users;

    public DeletionWebhookHandler(UserStore users) {
        this.users = users;
    }

    @ZeloWebhook("dsr.delete.requested")
    public Map<String, Object> onDeletionRequested(ZeloDeletionRequest request) {
        boolean removed = users.delete(request.externalId());
        log.info("Erased user '{}' for Zelo request {} (removed={})",
                request.externalId(), request.requestId(), removed);
        return Map.of(
                "deletedRows", removed ? 1 : 0,
                "externalId", request.externalId(),
                "erasedAt", Instant.now().toString());
    }
}
