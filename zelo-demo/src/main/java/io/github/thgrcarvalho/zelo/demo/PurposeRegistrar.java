package io.github.thgrcarvalho.zelo.demo;

import io.github.thgrcarvalho.zelo.starter.ZeloClient;
import io.github.thgrcarvalho.zelo.starter.ZeloLegalBasis;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.client.ResourceAccessException;

/**
 * Declares this integrator's processing purposes at startup. Consent is always
 * recorded <em>against</em> a purpose, so the purposes must exist before the first
 * user signs up. {@link ZeloClient#definePurpose} is idempotent, so this is safe to
 * run on every boot; it waits while the Zelo control plane is still coming up.
 *
 * <p>Disabled with {@code zelo.demo.seed-purposes=false} (e.g. in a context test
 * that has no live control plane to talk to).</p>
 */
@Component
@ConditionalOnProperty(prefix = "zelo.demo", name = "seed-purposes", matchIfMissing = true)
public class PurposeRegistrar implements ApplicationRunner {

    /** Necessary to provide the service (LGPD Art. 7 V — performance of a contract). */
    public static final String TERMS = "terms-of-service";
    /** Opt-in marketing (LGPD Art. 7 I — consent). */
    public static final String MARKETING = "marketing-emails";
    /** Processing of health-related data (LGPD Art. 7 VIII / Art. 11 — health protection). */
    public static final String HEALTH_DATA = "health-data-processing";

    private static final Logger log = LoggerFactory.getLogger(PurposeRegistrar.class);
    private static final int MAX_ATTEMPTS = 30;

    private final ZeloClient zelo;

    public PurposeRegistrar(ZeloClient zelo) {
        this.zelo = zelo;
    }

    @Override
    public void run(ApplicationArguments args) throws InterruptedException {
        awaitZelo();
        zelo.definePurpose(TERMS, "Provide the service under our terms of use", ZeloLegalBasis.CONTRACT);
        zelo.definePurpose(MARKETING, "Send product and marketing emails", ZeloLegalBasis.CONSENT);
        zelo.definePurpose(HEALTH_DATA, "Process health-related personal data", ZeloLegalBasis.HEALTH_PROTECTION);
        log.info("Declared Zelo purposes: {}, {}, {}", TERMS, MARKETING, HEALTH_DATA);
    }

    /** Poll until the control plane answers — it may still be starting up. */
    private void awaitZelo() throws InterruptedException {
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                zelo.listPurposes();
                return;
            } catch (ResourceAccessException stillStarting) {
                if (attempt == MAX_ATTEMPTS) {
                    throw stillStarting;
                }
                log.info("Waiting for Zelo to be reachable ({}/{})", attempt, MAX_ATTEMPTS);
                Thread.sleep(1000);
            }
        }
    }
}
