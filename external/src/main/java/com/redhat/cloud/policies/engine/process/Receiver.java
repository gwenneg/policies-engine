package com.redhat.cloud.policies.engine.process;

import com.google.common.collect.Multimap;
import com.google.common.collect.MultimapBuilder;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.vertx.core.json.JsonArray;
import io.vertx.core.json.JsonObject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.reactive.messaging.Acknowledgment;
import org.eclipse.microprofile.reactive.messaging.Incoming;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.hawkular.alerts.api.model.event.Event;
import org.hawkular.alerts.api.services.AlertsService;
import org.hawkular.alerts.log.MsgLogger;
import org.hawkular.alerts.log.MsgLogging;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.inject.Inject;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionStage;

import static org.hawkular.alerts.api.util.Util.isEmpty;

/**
 * This is the main process for Policies. It ingests data from Kafka, enriches it with information from
 * insights-host-inventory and then sends it for event processing in the engine.
 */
@ApplicationScoped
public class Receiver {
    private final MsgLogger log = MsgLogging.getMsgLogger(Receiver.class);

    private static final Set<String> ACCEPTED_REPORTERS;
    private static final Set<String> ACCEPTED_TYPES;

    static {
        ACCEPTED_REPORTERS = new HashSet<>();
        ACCEPTED_REPORTERS.add("puptoo");

        ACCEPTED_TYPES = new HashSet<>();
        ACCEPTED_TYPES.add("created");
        ACCEPTED_TYPES.add("updated");
    }

    // This needs to be the same value as set in the ui-backend Condition class.
    // This must not be modified unless the data in ISPN is migrated to a new value
    public static final String INSIGHTS_REPORT_DATA_ID = "platform.inventory.host-egress";

    public static final String CATEGORY_NAME = "insight_report";
    public static final String INSIGHT_ID_FIELD = "insights_id";
    public static final String DISPLAY_NAME_FIELD = "display_name";
    public static final String INVENTORY_ID_FIELD = "inventory_id";
    public static final String HOST_ID = "id";
    public static final String FQDN_NAME_FIELD = "fqdn";
    public static final String UPDATED = "updated";

    private static final String HOST_FIELD = "host";
    private static final String TYPE_FIELD = "type";
    private static final String REPORTER_FIELD = "reporter";
    private static final String TENANT_ID_FIELD = "account";
    private static final String SYSTEM_PROFILE_FIELD = "system_profile";
    private static final String NETWORK_INTERFACES_FIELD = "network_interfaces";
    private static final String YUM_REPOS_FIELD = "yum_repos";
    private static final String NAME_FIELD = "name";
    private static final String TAGS_FIELD = "tags";
    private static final String TAGS_KEY_FIELD = "key";
    private static final String TAGS_VALUE_FIELD = "value";
    private static final String CHECK_IN_FIELD = "check_in";

    @ConfigProperty(name = "engine.receiver.store-events")
    boolean storeEvents;

    @Inject
    AlertsService alertsService;

    @Inject
    MeterRegistry meterRegistry;

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
        rejectedCountReporter = meterRegistry.counter("engine.input.rejected.detail", "queue", "host-egress","reason", "reporter");
        rejectedCountId = meterRegistry.counter("engine.input.rejected.detail", "queue" , "host-egress", "reason", "insightsId");
        processingErrors = meterRegistry.counter("engine.input.processed.errors", "queue", "host-egress");
    }

    @Incoming("events")
    @Acknowledgment(Acknowledgment.Strategy.MANUAL)
    public CompletionStage<Void> processAsync(Message<String> input) {
        incomingMessagesCount.increment();
        if (log.isTraceEnabled()) {
            log.tracef("Received message, input payload: %s", input.getPayload());
        }
        JsonObject json;
        try {
            json = new JsonObject(input.getPayload());
        } catch(Exception e) {
            processingErrors.increment();
            return input.ack();
        }
        if (json.containsKey(TYPE_FIELD)) {
            String eventType = json.getString(TYPE_FIELD);
            if(!ACCEPTED_TYPES.contains(eventType)) {
                if (log.isDebugEnabled()) {
                    log.debugf("Got a request with type='%s', ignoring ", eventType);
                }
                rejectedCount.increment();
                rejectedCountType.increment();
                return input.ack();
            }
        }

        if (json.containsKey(HOST_FIELD)) {
            json = json.getJsonObject(HOST_FIELD);
        } else {
            rejectedCount.increment();
            rejectedCountHost.increment();
            return input.ack();
        }

        // Verify host.reporter (not platform_metadata.metadata.reporter!) is one of the accepted values
        String reporter = json.getString(REPORTER_FIELD);
        if(!ACCEPTED_REPORTERS.contains(reporter)) {
            rejectedCount.increment();
            rejectedCountReporter.increment();
            return input.ack();
        }

        String insightsId = json.getString(INSIGHT_ID_FIELD);

        if (isEmpty(insightsId)) {
            rejectedCount.increment();
            rejectedCountId.increment();
            return input.ack();
        }

        String tenantId = json.getString(TENANT_ID_FIELD);
        String displayName = json.getString(DISPLAY_NAME_FIELD);
        String text = String.format("host-egress report %s for %s", insightsId, displayName);

        Event event = new Event(tenantId, UUID.randomUUID().toString(), INSIGHTS_REPORT_DATA_ID, CATEGORY_NAME, text);
        // Indexed searchable events
        Multimap<String, String> tagsMap = parseTags(json.getJsonArray(TAGS_FIELD));
        tagsMap.put(DISPLAY_NAME_FIELD, displayName);
        tagsMap.put(INVENTORY_ID_FIELD, json.getString(HOST_ID));
        event.setTags(tagsMap);

        // Additional context for processing
        Map<String, String> contextMap = new HashMap<>();
        contextMap.put(INSIGHT_ID_FIELD, insightsId);
        contextMap.put(CHECK_IN_FIELD, json.getString(UPDATED));
        event.setContext(contextMap);

        JsonObject sp = json.getJsonObject(SYSTEM_PROFILE_FIELD);
        Map<String, Object> systemProfile = parseSystemProfile(sp);

        systemProfile.put(FQDN_NAME_FIELD, json.getString(FQDN_NAME_FIELD));

        event.setFacts(systemProfile);

        try {
            List<Event> eventList = new ArrayList<>(1);
            eventList.add(event);
            if (storeEvents) {
                alertsService.addEvents(eventList);
            } else {
                alertsService.sendEvents(eventList);
            }
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        return input.ack();
    }

    /**
     * parseSystemProfile extracts certain parts of the input JSON and modifies them for easier use
     */
    static Map<String, Object> parseSystemProfile(JsonObject json) {
        if (json == null) {
            return new HashMap<>();
        }
        Map<String, Object> facts = json.getMap();

        JsonArray networkInterfaces = json.getJsonArray(NETWORK_INTERFACES_FIELD);
        if (networkInterfaces != null) {
            facts.put(NETWORK_INTERFACES_FIELD, namedObjectsToMap(networkInterfaces));
        }

        JsonArray yumRepos = json.getJsonArray(YUM_REPOS_FIELD);
        if (yumRepos != null) {
            facts.put(YUM_REPOS_FIELD, namedObjectsToMap(yumRepos));
        }

        return facts;
    }

    static Map<String, Object> namedObjectsToMap(JsonArray objectArray) {
        Map<String, Object> arrayObjectKey = new HashMap<>();
        for (Object o : objectArray) {
            JsonObject json = (JsonObject) o;
            String name = json.getString(NAME_FIELD);
            if (name == null || name.isEmpty()) {
                continue;
            }
            arrayObjectKey.put(name, json.getMap());
        }
        return arrayObjectKey;
    }

    static Multimap<String, String> parseTags(JsonArray tagsInput) {
        Multimap<String, String> tagsMap = MultimapBuilder.hashKeys().hashSetValues().build();
        for (Object o : tagsInput) {
            JsonObject json = (JsonObject) o;
            String key = json.getString(TAGS_KEY_FIELD).toLowerCase();
            String value = json.getString(TAGS_VALUE_FIELD);
            tagsMap.put(key, value);
        }

        return tagsMap;
    }
}
