package io.github.thgrcarvalho.zelo.demo;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Smoke test: the demo boots with the Zelo starter auto-configured. Context
 * loading exercises the dispatcher discovering {@link DeletionWebhookHandler}
 * and the webhook receiver wiring up (zelo.webhook-secret is set).
 */
@SpringBootTest(properties = {
        "zelo.webhook-secret=test-secret",
        "zelo.api-url=http://localhost:9999",
        "zelo.api-key=test-key"
})
class ZeloDemoApplicationTest {

    @Test
    void contextLoads() {
    }
}
