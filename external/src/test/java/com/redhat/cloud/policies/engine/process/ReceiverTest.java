package com.redhat.cloud.policies.engine.process;

import com.redhat.cloud.notifications.ingress.Action;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.quarkus.test.junit.QuarkusTest;
import io.reactivex.subscribers.TestSubscriber;
import io.vertx.core.json.JsonObject;
import org.apache.avro.io.DatumReader;
import org.apache.avro.io.DecoderFactory;
import org.apache.avro.io.JsonDecoder;
import org.apache.avro.specific.SpecificDatumReader;
import org.apache.commons.io.IOUtils;
import org.eclipse.microprofile.reactive.messaging.Channel;
import org.eclipse.microprofile.reactive.messaging.Emitter;
import org.hawkular.alerts.api.model.action.ActionDefinition;
import org.hawkular.alerts.api.model.condition.Condition;
import org.hawkular.alerts.api.model.condition.EventCondition;
import org.hawkular.alerts.api.model.event.Alert;
import org.hawkular.alerts.api.model.event.EventType;
import org.hawkular.alerts.api.model.paging.Page;
import org.hawkular.alerts.api.model.trigger.FullTrigger;
import org.hawkular.alerts.api.model.trigger.Mode;
import org.hawkular.alerts.api.model.trigger.Trigger;
import org.hawkular.alerts.api.model.trigger.TriggerAction;
import org.hawkular.alerts.api.services.AlertsCriteria;
import org.hawkular.alerts.api.services.AlertsService;
import org.hawkular.alerts.api.services.DefinitionsService;
import org.hawkular.alerts.api.services.StatusService;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.reactivestreams.Publisher;

import javax.inject.Inject;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@QuarkusTest
@Tag("integration")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class ReceiverTest {

    @Inject
    StatusService statusService;

    @BeforeAll
    void init() throws InterruptedException {
        System.setProperty("hawkular.data", "./target/hawkular.data");
        for(int i = 0; i < 100; i++) {
            if(statusService.isHealthy()) {
                break;
            }
            Thread.sleep(100);
        }
    }

    @Inject
    @Channel("events")
    Emitter<String> hostEmitter;

    @Inject
    @Channel("webhook")
    Publisher<String> webhookReceiver;

    @Inject
    DefinitionsService definitionsService;

    @Inject
    AlertsService alertsService;

    @Inject
    MeterRegistry meterRegistry;

    private static final String TENANT_ID = "integration-test";
    private static final String ACTION_PLUGIN = "email";
    private static final String ACTION_ID = "email-notif";
    private static final String TRIGGER_ID = "arch-trigger";

    private Action deserializeAction(String payload) {
        Action action = new Action();
        try {
            JsonDecoder jsonDecoder = DecoderFactory.get().jsonDecoder(Action.getClassSchema(), payload);
            DatumReader<Action> reader = new SpecificDatumReader<>(Action.class);
            reader.read(action, jsonDecoder);
        } catch (IOException e) {
            throw new IllegalArgumentException("Payload extraction failed", e);
        }
        return action;
    }

    private FullTrigger createTriggeringTrigger(String triggerId) {
        EventCondition evCond = new EventCondition();
//        evCond.setExpression("facts.arch = 'string'");
        evCond.setExpression("");
        evCond.setTenantId(TENANT_ID);
        evCond.setDataId(Receiver.INSIGHTS_REPORT_DATA_ID);
        List<Condition> conditions = Collections.singletonList(evCond);

        TriggerAction action = new TriggerAction(ACTION_PLUGIN, ACTION_ID);
        Set<TriggerAction> actions = Collections.singleton(action);

        Trigger trigger = new Trigger(TENANT_ID, triggerId, "Trigger from arch", null);
        trigger.setDescription("My description");
        trigger.setEventType(EventType.ALERT);
        trigger.setActions(actions);
        trigger.setMode(Mode.FIRING);
        trigger.setEnabled(true);

        FullTrigger fullTrigger = new FullTrigger(trigger, null, conditions);

        return fullTrigger;
    }

    @Test
    public void testReceiver() throws Exception {
        /*
        Create trigger definitions, send to hostEmitter and wait for the trigger to send an alert to email
         */
        ActionDefinition actionDefinition = new ActionDefinition(TENANT_ID, ACTION_PLUGIN, ACTION_ID);
        Map<String, String> props = new HashMap<>();
        actionDefinition.setProperties(props);
        definitionsService.addActionDefinition(TENANT_ID, actionDefinition);

        FullTrigger fullTrigger = createTriggeringTrigger(TRIGGER_ID);
        definitionsService.createFullTrigger(TENANT_ID, fullTrigger);

        // Create second trigger
        FullTrigger fullTrigger2 = createTriggeringTrigger(TRIGGER_ID + "2");
        definitionsService.createFullTrigger(TENANT_ID, fullTrigger2);

        TestSubscriber<String> testSubscriber = new TestSubscriber<>();
        webhookReceiver.subscribe(testSubscriber);

        // Read the input file and send it
        InputStream is = getClass().getClassLoader().getResourceAsStream("input/host.json");
        String inputJson = IOUtils.toString(is, StandardCharsets.UTF_8);
        hostEmitter.send(inputJson);

        // Wait for the async messaging to arrive
        // It's aggregated into one message
        testSubscriber.awaitCount(1);
        testSubscriber.assertValueCount(1);

        Action action = deserializeAction(testSubscriber.values().get(0));

        assertEquals(TENANT_ID, action.getAccountId());
        assertTrue(action.getContext().containsKey("insights_id"));
        assertEquals(2, action.getEvents().size());

        // Now send broken data and then working and expect things to still work
        String brokenJson = "{ \"json\": ";
        hostEmitter.send(brokenJson);
        hostEmitter.send(inputJson);

        // Wait for the async messaging to arrive
        testSubscriber.awaitCount(2);
        testSubscriber.assertValueCount(2);

        Counter hostEgressProcessingErrors = meterRegistry.counter("engine.input.processed.errors", "queue", "host-egress");
        assertEquals(1.0, hostEgressProcessingErrors.count());
        testSubscriber.dispose();

        // Verify the alert includes the tags from the event
        AlertsCriteria criteria = new AlertsCriteria();
        criteria.setTagQuery("tags.display_name = 'VM'");
        Page<Alert> alerts = alertsService.getAlerts(TENANT_ID, criteria, null);

        // 4, because we have two triggers and we send the correct input twice
        assertEquals(4, alerts.size());

        definitionsService.removeTrigger(TENANT_ID, TRIGGER_ID + "2");
    }

    @Test
    void testMoreComplexInput() throws Exception {
        // Read the input file and send it
        InputStream is = getClass().getClassLoader().getResourceAsStream("input/thomas-host.json");
        String inputJson = IOUtils.toString(is, StandardCharsets.UTF_8);
        hostEmitter.send(inputJson);

        TestSubscriber<String> testSubscriber = new TestSubscriber<>();
        webhookReceiver.subscribe(testSubscriber);

        // Wait for the async messaging to arrive (there's two identical triggers..)
        testSubscriber.awaitCount(1);
        testSubscriber.assertValueCount(1);

        Action action = deserializeAction(testSubscriber.values().get(0));
        assertEquals(1, action.getEvents().size());

        List<Map<String, String>> tags = (List<Map<String, String>>) action.getContext().get("tags");
        boolean foundNeuchatel = false;
        boolean foundCharmey = false;
        for(Map<String, String> tag : tags) {
            if (tag.get("key").equals("location")) {
                if (tag.get("value").equals("Neuchatel")) {
                    foundNeuchatel = true;
                } else if (tag.get("value").equals("Charmey")) {
                    foundCharmey =  true;
                }
            }
        }
        assertTrue(foundNeuchatel);
        assertTrue(foundCharmey);
    }

    @Test
    void testWebhookAvroOutput() throws Exception {
        FullTrigger fullTrigger = createTriggeringTrigger(TRIGGER_ID + "3");

        TriggerAction triggerAction = new TriggerAction();
        triggerAction.setActionPlugin("notification");
        Set<TriggerAction> actions = Collections.singleton(triggerAction);

        fullTrigger.getTrigger().setActions(actions);
        definitionsService.createFullTrigger(TENANT_ID + "2", fullTrigger);

        TestSubscriber<String> testSubscriber = new TestSubscriber<>();
        webhookReceiver.subscribe(testSubscriber);

        InputStream is = getClass().getClassLoader().getResourceAsStream("input/thomas-host.json");
        JsonObject pushJson = new JsonObject(IOUtils.toString(is, StandardCharsets.UTF_8));

        pushJson.getJsonObject("host").put("account", TENANT_ID + "2");
        hostEmitter.send(pushJson.encode());

        // Wait for the async messaging to arrive (there's two identical triggers..)
        testSubscriber.awaitCount(1);
        testSubscriber.assertValueCount(1);

        Action action = deserializeAction(testSubscriber.values().get(0));
        assertEquals("rhel", action.getBundle());
        assertEquals("policies", action.getApplication());
        assertEquals("policy-triggered", action.getEventType());
        assertEquals(TENANT_ID + "2", action.getAccountId());
    }

    @Test
    void testTakeSystemCheckInFromUpdate() throws Exception {
        FullTrigger fullTrigger = createTriggeringTrigger(TRIGGER_ID + "4");

        TriggerAction triggerAction = new TriggerAction();
        triggerAction.setActionPlugin("notification");
        Set<TriggerAction> actions = Collections.singleton(triggerAction);

        fullTrigger.getTrigger().setActions(actions);
        definitionsService.createFullTrigger(TENANT_ID + "2", fullTrigger);

        TestSubscriber<String> testSubscriber = new TestSubscriber<>();
        webhookReceiver.subscribe(testSubscriber);

        InputStream is = getClass().getClassLoader().getResourceAsStream("input/thomas-host.json");
        JsonObject pushJson = new JsonObject(IOUtils.toString(is, StandardCharsets.UTF_8));

        pushJson.getJsonObject("host").put("account", TENANT_ID + "2");
        hostEmitter.send(pushJson.encode());

        // Wait for the async messaging to arrive (there's two identical triggers..)
        testSubscriber.awaitCount(1);
        testSubscriber.assertValueCount(1);

        Action action = deserializeAction(testSubscriber.values().get(0));
        assertEquals("rhel", action.getBundle());
        assertEquals("policies", action.getApplication());
        assertEquals("policy-triggered", action.getEventType());
        assertEquals(TENANT_ID + "2", action.getAccountId());

        assertNotNull(action.getTimestamp());
        assertEquals("2020-04-16T16:10:42.199046", action.getContext().get("system_check_in"));
    }

    @AfterAll
    void cleanup() throws Exception {
        // Delete what we created..
        definitionsService.removeTrigger(TENANT_ID, TRIGGER_ID);
        definitionsService.removeActionDefinition(TENANT_ID, ACTION_PLUGIN, ACTION_ID);
    }
}
