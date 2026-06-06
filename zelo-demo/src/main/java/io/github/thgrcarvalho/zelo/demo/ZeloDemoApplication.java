package io.github.thgrcarvalho.zelo.demo;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * A tiny sample integrator app that embeds the Zelo starter. On receiving a
 * {@code dsr.delete.requested} webhook it runs its own erasure logic against its
 * own (fake) user table and reports fulfillment back to Zelo — proving the full
 * DELETE loop end to end. Fleshed out in milestone M5.
 */
@SpringBootApplication
public class ZeloDemoApplication {

    public static void main(String[] args) {
        SpringApplication.run(ZeloDemoApplication.class, args);
    }
}
