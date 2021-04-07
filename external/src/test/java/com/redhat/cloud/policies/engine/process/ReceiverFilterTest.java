package com.redhat.cloud.policies.engine.process;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.vertx.core.json.JsonObject;
import org.apache.commons.io.IOUtils;
import org.eclipse.microprofile.reactive.messaging.Message;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletionStage;

import static org.junit.jupiter.api.Assertions.assertEquals;

@TestInstance(TestInstance.Lifecycle.PER_CLASS)
public class ReceiverFilterTest {

    MockedAlertsService mockedAlertsService;
    Receiver receiver;

    MeterRegistry meterRegistry = new SimpleMeterRegistry();
    Counter incomingMessagesCount;
    Counter processingErrors;
    Counter rejectedCount;
    Counter rejectedCountReporter;
    Counter rejectedCountHost;
    Counter rejectedCountId;
    Counter rejectedCountType;

    @Test
    public void testAlertsServiceIsCalled() throws Exception {
        InputStream is = getClass().getClassLoader().getResourceAsStream("input/host.json");
        String inputJson = IOUtils.toString(is, StandardCharsets.UTF_8);

        CompletionStage<Void> voidCompletionStage = receiver.processAsync(Message.of(inputJson));
        voidCompletionStage.toCompletableFuture().get();

        assertEquals(1, mockedAlertsService.getPushedEvents().size());
        assertEquals(1, incomingMessagesCount.count());
    }

    @BeforeAll
    public void init() {
        mockedAlertsService = new MockedAlertsService();
        incomingMessagesCount = meterRegistry.counter("incomingMessagesCount");
        processingErrors = meterRegistry.counter("processingErrors");
        rejectedCount = meterRegistry.counter("rejectedCount");
        rejectedCountReporter = meterRegistry.counter("rejectedCountReporter");
        rejectedCountHost = meterRegistry.counter("rejectedCountHost");
        rejectedCountId = meterRegistry.counter("rejectedCountId");
        rejectedCountType = meterRegistry.counter("rejectedCountType");

        receiver = new Receiver();
        receiver.alertsService = mockedAlertsService;
        receiver.processingErrors = processingErrors;
        receiver.incomingMessagesCount = incomingMessagesCount;
        receiver.rejectedCount = rejectedCount;
        receiver.rejectedCountReporter = rejectedCountReporter;
        receiver.rejectedCountHost = rejectedCountHost;
        receiver.rejectedCountId = rejectedCountId;
        receiver.rejectedCountType = rejectedCountType;
    }

    @AfterEach
    public void cleanup() {
        mockedAlertsService.clearEvents();
        receiver.processingErrors.increment(receiver.processingErrors.count() * -1);
        receiver.incomingMessagesCount.increment(receiver.incomingMessagesCount.count() * -1);
        receiver.rejectedCount.increment(receiver.rejectedCount.count() * -1);
        receiver.rejectedCountReporter.increment(receiver.rejectedCountReporter.count() * -1);
    }

    @Test
    public void testFilterWithoutInsightsId() throws Exception {
        InputStream is = getClass().getClassLoader().getResourceAsStream("input/host.json");
        String inputJson = IOUtils.toString(is, StandardCharsets.UTF_8);

        JsonObject jsonObject = new JsonObject(inputJson);
        jsonObject.getJsonObject("host").remove("insights_id");
        inputJson = jsonObject.toString();

        CompletionStage<Void> voidCompletionStage = receiver.processAsync(Message.of(inputJson));
        voidCompletionStage.toCompletableFuture().get();

        assertEquals(0, mockedAlertsService.getPushedEvents().size());
        assertEquals(1, incomingMessagesCount.count());
        assertEquals(0, processingErrors.count());
    }

    @Test
    public void testInventoryIdIsStored() throws Exception {
        InputStream is = getClass().getClassLoader().getResourceAsStream("input/host.json");
        String inputJson = IOUtils.toString(is, StandardCharsets.UTF_8);

        CompletionStage<Void> voidCompletionStage = receiver.processAsync(Message.of(inputJson));
        voidCompletionStage.toCompletableFuture().get();

        assertEquals("ba11a21a-8b22-431b-9b4b-b06006472d54", mockedAlertsService.getPushedEvents().get(0).getTags().get(Receiver.INVENTORY_ID_FIELD).iterator().next());
    }

    @Test
    public void testRejectionTypes() throws Exception {
        InputStream is = getClass().getClassLoader().getResourceAsStream("input/thomas-host.json");
        String inputJson = IOUtils.toString(is, StandardCharsets.UTF_8);

        JsonObject jsonObject = new JsonObject(inputJson);
        jsonObject.put("type", "deleted");
        inputJson = jsonObject.toString();

        CompletionStage<Void> voidCompletionStage = receiver.processAsync(Message.of(inputJson));
        voidCompletionStage.toCompletableFuture().get();

        assertEquals(0, mockedAlertsService.getPushedEvents().size());
        assertEquals(1, incomingMessagesCount.count());
        assertEquals(0, processingErrors.count());
        assertEquals(1, rejectedCount.count());
        assertEquals(1, rejectedCountType.count());

        jsonObject = new JsonObject(inputJson);
        jsonObject.put("type","created");
        jsonObject.getJsonObject("host").put("reporter", "rhsm-conduit");
        inputJson = jsonObject.toString();

        voidCompletionStage = receiver.processAsync(Message.of(inputJson));
        voidCompletionStage.toCompletableFuture().get();

        assertEquals(0, mockedAlertsService.getPushedEvents().size());
        assertEquals(2, incomingMessagesCount.count());
        assertEquals(0, processingErrors.count());
        assertEquals(2, rejectedCount.count());
        assertEquals(1, rejectedCountReporter.count());
        assertEquals(1, rejectedCountType.count());
    }
}
