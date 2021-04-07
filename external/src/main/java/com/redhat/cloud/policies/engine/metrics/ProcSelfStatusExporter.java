package com.redhat.cloud.policies.engine.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.scheduler.Scheduled;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.io.File;
import java.util.Scanner;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Logger;


/**
 * Exports the following from /proc/self/status. See proc(5)
 * VmHWM:    265580 kB
 * VmRSS:    233156 kB
 * RssAnon:          210444 kB
 * RssFile:           22712 kB
 * VmStk:       136 kB
 * VmLib:     24416 kB
 * VmData:  3529900 kB
 * VmSize:  13529900 kB
 * Threads: 23
 *
 * @author hrupp
 */
@ApplicationScoped
public class ProcSelfStatusExporter {

    private final Logger log = Logger.getLogger(this.getClass().getSimpleName());

    private static final String PATHNAME = "/proc/self/status";
    private static final String METRIC_UNIT_KILOBYTES = "kilobits";
    private static final String METRIC_UNIT_NONE = "none";

    @Inject
    MeterRegistry meterRegistry;

    private boolean hasWarned = false;

    private AtomicLong vmHwm = new AtomicLong(0L);
    private AtomicLong vmRss = new AtomicLong(0L);
    private AtomicLong rssAnon = new AtomicLong(0L);
    private AtomicLong rssFile = new AtomicLong(0L);
    private AtomicLong vmStk = new AtomicLong(0L);
    private AtomicLong vmLib = new AtomicLong(0L);
    private AtomicLong vmData = new AtomicLong(0L);
    private AtomicLong vmSize = new AtomicLong(0L);
    private AtomicInteger threads = new AtomicInteger(0);

    @Scheduled(every = "10s")
    void gather() {
        File status = new File(PATHNAME);
        if (!status.exists() || !status.canRead()) {
            if (!hasWarned) {
                log.warning("Can't read " + PATHNAME);
                hasWarned = true;
            }
            return;
        }

        try (Scanner fr = new Scanner(status)) {
            while (fr.hasNextLine()) {
                String line = fr.nextLine();
                String[] parts = line.split("[ \t]+");

                switch (parts[0]) {
                    case "VmHWM:":
                        vmHwm.set(Long.parseLong(parts[1]));
                        break;
                    case "VmRSS:":
                        vmRss.set(Long.parseLong(parts[1]));
                        break;
                    case "RssAnon:":
                        rssAnon.set(Long.parseLong(parts[1]));
                        break;
                    case "RssFile:":
                        rssFile.set(Long.parseLong(parts[1]));
                        break;
                    case "VmStk:":
                        vmStk.set(Long.parseLong(parts[1]));
                        break;
                    case "VmLib:":
                        vmLib.set(Long.parseLong(parts[1]));
                        break;
                    case "VmData:":
                        vmData.set(Long.parseLong(parts[1]));
                        break;
                    case "VmSize:":
                        vmSize.set(Long.parseLong(parts[1]));
                        break;
                    case "Threads:":
                        threads.set(Integer.parseInt(parts[1]));
                        break;
                    default:
                        // Nothing. File has more entries that we are not interested in.
                }
            }
        } catch (Exception e) {
            log.warning("Scanning of file failed: " + e.getMessage());
        }
    }

    @PostConstruct
    void initGauges() {
        registerGauge("status.vmHwm", vmHwm, METRIC_UNIT_KILOBYTES);
        registerGauge("status.vmRss", vmRss, METRIC_UNIT_KILOBYTES);
        registerGauge("status.rssAnon", rssAnon, METRIC_UNIT_KILOBYTES);
        registerGauge("status.rssFile", rssFile, METRIC_UNIT_KILOBYTES);
        registerGauge("status.vmStk", vmStk, METRIC_UNIT_KILOBYTES);
        registerGauge("status.vmLib", vmLib, METRIC_UNIT_KILOBYTES);
        registerGauge("status.vmData", vmData, METRIC_UNIT_KILOBYTES);
        registerGauge("status.vmSize", vmSize, METRIC_UNIT_KILOBYTES);
        registerGauge("status.threads", threads, METRIC_UNIT_NONE);
    }

    private void registerGauge(String name, Number value, String metricUnit) {
        Gauge.builder(name, value, Number::doubleValue)
                .baseUnit(metricUnit)
                .tags("type", "proc")
                .register(meterRegistry);
    }

    @Override
    public String toString() {
        final StringBuilder sb = new StringBuilder("ProcSelfStatusExporter{");
        sb.append("vmHwm=").append(vmHwm.get() / 1024);
        sb.append(", vmRss=").append(vmRss.get() / 1024);
        sb.append(", rssAnon=").append(rssAnon.get() / 1024);
        sb.append(", rssFile=").append(rssFile.get() / 1024);
        sb.append(", vmStk=").append(vmStk.get() / 1024);
        sb.append(", vmLib=").append(vmLib.get() / 1024);
        sb.append(", vmData=").append(vmData.get() / 1024);
        sb.append(", vmSize=").append(vmSize.get() / 1024);
        sb.append(", threads=").append(threads.get());
        sb.append('}');
        return sb.toString();
    }
}
