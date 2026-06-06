package io.github.thgrcarvalho.zelo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Entry point for the Zelo control plane.
 *
 * <p>Zelo is a control plane, not a data store: it holds consent records, DSR
 * request state and a tamper-evident audit trail, but never end-user PII. The
 * actual personal data stays in the integrator's database; Zelo orchestrates
 * operations on it via signed webhooks.</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
public class ZeloServerApplication {

    public static void main(String[] args) {
        SpringApplication.run(ZeloServerApplication.class, args);
    }
}
