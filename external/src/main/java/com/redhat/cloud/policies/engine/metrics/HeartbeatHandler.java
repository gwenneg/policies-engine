package com.redhat.cloud.policies.engine.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.scheduler.Scheduled;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.util.logging.Logger;

/**
 * Log a heart beat message with some stats
 */
@ApplicationScoped
public class HeartbeatHandler {

    private final Logger log = Logger.getLogger(this.getClass().getSimpleName());

    @Inject
    MeterRegistry meterRegistry;

    // The following metrics are defined in process.Receiver
    Counter incomingMessagesCount;
    Counter rejectedCount;
    Counter rejectedCountType;
    Counter rejectedCountHost;
    Counter rejectedCountReporter;
    Counter rejectedCountId;
    Counter processingErrors;

    @PostConstruct
    void initCounters() {
        incomingMessagesCount = meterRegistry.counter("engine.input.processed", "queue", "host-egress");
        rejectedCount = meterRegistry.counter("engine.input.rejected", "queue", "host-egress");
        rejectedCountType = meterRegistry.counter("engine.input.rejected.detail", "queue", "host-egress", "reason", "type");
        rejectedCountHost = meterRegistry.counter("engine.input.rejected.detail", "queue", "host-egress", "reason", "noHost");
        rejectedCountReporter = meterRegistry.counter("engine.input.rejected.detail", "queue", "host-egress", "reason", "reporter");
        rejectedCountId = meterRegistry.counter("engine.input.rejected.detail", "queue", "host-egress", "reason", "insightsId");
        processingErrors = meterRegistry.counter("engine.input.processed.errors", "queue", "host-egress");
    }

    @Scheduled(every = "1h")
    void printHeartbeat() {

        String msg = String.format("Heartbeat: processed %s, rejected %s (t=%s, h=%s, r=%s, i=%s), process errors %s",
                incomingMessagesCount.count(),
                rejectedCount.count(),
                rejectedCountType.count(),
                rejectedCountHost.count(),
                rejectedCountReporter.count(),
                rejectedCountId.count(),
                processingErrors.count());

        log.info(msg);


    }
}
