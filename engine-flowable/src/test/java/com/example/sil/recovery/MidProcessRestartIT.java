package com.example.sil.recovery;

import static com.github.tomakehurst.wiremock.client.WireMock.exactly;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * The headline claim of the whole project: an order in flight survives the application being
 * killed and started again, and finishes without repeating anything.
 *
 * <p>Nothing in the codebase implements this. That is the point. Because the engine keeps its state
 * in the same database as the order - every completed step, the pending job, the message the
 * process is parked on - a restart is not an event the application has to handle. The new instance
 * picks up rows the old one left behind and carries on.
 *
 * <p>The comparison worth holding in mind: the same journey written as a service method with a
 * retry loop loses everything at the moment of the kill. Not the order row, which is committed, but
 * the knowledge of where it had got to and what still had to happen - and reconstructing that
 * afterwards is exactly the reconciliation job nobody wants to write.
 */
class MidProcessRestartIT extends AbstractRestartTest {

    @BeforeEach
    void resetSupplier() {
        SUPPLIER.resetAll();
        stubSupplier();
    }

    @Test
    @DisplayName("an order parked mid-journey is picked up by a new instance and completes")
    void orderSurvivesARestart() throws Exception {
        String orderId;
        int firstPort;

        // --- instance one: provision as far as the activation callback, then die ----------------
        ConfigurableApplicationContext first = startInstance();
        try {
            firstPort = portOf(first);
            orderId = submitOrder(firstPort);

            String parked = awaitValue(
                    () -> orderJson(firstPort, orderId),
                    json -> JsonPath.<String>read(json, "$.supplierRefs.phoneNumber") != null);

            assertThat(JsonPath.<String>read(parked, "$.state")).isEqualTo("inProgress");
            assertThat(JsonPath.<String>read(parked, "$.supplierRefs.customerId")).isEqualTo("cust-1");
        } finally {
            first.close();
        }

        // The kill. Four supplier resources exist, the process is parked on a message, and there is
        // no JVM left that knows any of it.
        SUPPLIER.verify(exactly(1), postRequestedFor(urlPathEqualTo(CUSTOMERS_PATH)));
        SUPPLIER.verify(exactly(1), postRequestedFor(urlPathEqualTo(ACTIVATIONS_PATH)));
        SUPPLIER.verify(exactly(0), postRequestedFor(urlPathEqualTo(SHIPMENTS_PATH)));

        // --- instance two: same database, no shared memory, no handover -------------------------
        ConfigurableApplicationContext second = startInstance();
        try {
            int secondPort = portOf(second);

            // The callback the supplier would have sent anyway arrives at the new instance, and
            // correlates to a process the new instance never started.
            deliverActivationCallback(secondPort, orderId);

            String finished = awaitValue(
                    () -> orderJson(secondPort, orderId),
                    json -> "completed".equals(JsonPath.<String>read(json, "$.state")));

            assertThat(JsonPath.<String>read(finished, "$.fulfilment.shipmentStatus"))
                    .isEqualTo("delivered");

            // Nothing was redone. This is the assertion that separates "it recovered" from "it
            // started again": every provisioning call still happened exactly once across both
            // lifetimes, so no customer was created twice at the supplier.
            SUPPLIER.verify(exactly(1), postRequestedFor(urlPathEqualTo(CUSTOMERS_PATH)));
            SUPPLIER.verify(exactly(1), postRequestedFor(urlPathEqualTo(SUBSCRIPTIONS_PATH)));
            SUPPLIER.verify(exactly(1), postRequestedFor(urlPathEqualTo(USERS_PATH)));
            SUPPLIER.verify(exactly(1), postRequestedFor(urlPathEqualTo(NUMBERS_PATH)));
            SUPPLIER.verify(exactly(1), postRequestedFor(urlPathEqualTo(ACTIVATIONS_PATH)));
            SUPPLIER.verify(exactly(1), postRequestedFor(urlPathEqualTo(SHIPMENTS_PATH)));

            // And the timeline still reads as one continuous journey, because it is one process
            // instance - the restart left no seam in it.
            String timeline = orderJson(secondPort, orderId);
            assertThat(JsonPath.<String>read(timeline, "$.id")).isEqualTo(orderId);
        } finally {
            second.close();
        }
    }

    @Test
    @DisplayName("a timer that came due while nothing was running fires once the app is back")
    void timerFiresAfterTheOutageThatCoveredIt() throws Exception {
        String orderId;

        ConfigurableApplicationContext first = startInstance();
        try {
            int port = portOf(first);
            orderId = submitOrder(port);
            awaitValue(() -> orderJson(port, orderId),
                    json -> JsonPath.<String>read(json, "$.supplierRefs.phoneNumber") != null);
            deliverActivationCallback(port, orderId);

            // Wait until the handset has been dispatched and the process is asleep on the poll
            // timer, then kill the application before that timer is due.
            awaitValue(() -> orderJson(port, orderId),
                    json -> "requested".equals(
                            JsonPath.<String>read(json, "$.fulfilment.shipmentStatus")));
        } finally {
            first.close();
        }

        // The poll timer comes due during the outage, with nobody to run it.
        Thread.sleep(4000);

        ConfigurableApplicationContext second = startInstance();
        try {
            int port = portOf(second);

            // A due timer is a row with a past due date, so the new instance's job executor finds
            // it immediately. An in-memory scheduler would simply have forgotten this order.
            String finished = awaitValue(() -> orderJson(port, orderId),
                    json -> "completed".equals(JsonPath.<String>read(json, "$.state")));

            assertThat(JsonPath.<Integer>read(finished, "$.fulfilment.shipmentPollCount"))
                    .isGreaterThanOrEqualTo(1);
        } finally {
            second.close();
        }
    }
}
