package io.github.thgrcarvalho.zelo.infrastructure.scheduling;

import io.github.thgrcarvalho.zelo.application.OverdueSweepService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Periodically runs the overdue sweep. The interval is
 * {@code zelo.dsr.overdue-sweep-interval-ms} (default 60s). Becomes a Kubernetes
 * CronJob in the M8 infra phase.
 */
@Component
public class OverdueSweepJob {

    private final OverdueSweepService sweep;

    public OverdueSweepJob(OverdueSweepService sweep) {
        this.sweep = sweep;
    }

    @Scheduled(
            initialDelayString = "${zelo.dsr.overdue-sweep-interval-ms:60000}",
            fixedDelayString = "${zelo.dsr.overdue-sweep-interval-ms:60000}")
    public void run() {
        sweep.sweep();
    }
}
