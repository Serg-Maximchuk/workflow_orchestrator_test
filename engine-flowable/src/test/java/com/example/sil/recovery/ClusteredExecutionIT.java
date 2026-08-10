package com.example.sil.recovery;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Two instances, one database, and no coordination between them beyond that database.
 *
 * <p>This is the property that makes the engine deployable on more than one node, and it is easy to
 * assume rather than check. Every instance polls the same job table; if the locking is wrong, two
 * of them pick up the same job and the supplier gets asked to create the same customer twice. The
 * supplier's request journal is the only honest witness, so that is what the test reads.
 */
class ClusteredExecutionIT extends AbstractRestartTest {

    private static final int ORDERS = 6;

    @BeforeEach
    void resetSupplier() {
        SUPPLIER.resetAll();
        stubSupplier();
    }

    @Test
    @DisplayName("no job runs twice when two instances share a database")
    void jobsAreNotExecutedTwiceAcrossInstances() throws Exception {
        ConfigurableApplicationContext one = startInstance();
        ConfigurableApplicationContext two = startInstance();

        try {
            int portOne = portOf(one);
            int portTwo = portOf(two);

            // Submitted through both instances, so neither can be idle and the jobs of a single
            // order are genuinely up for grabs by whichever executor gets there first.
            List<String> orderIds = new ArrayList<>();
            for (int i = 0; i < ORDERS; i++) {
                orderIds.add(submitOrder(i % 2 == 0 ? portOne : portTwo));
            }

            for (String orderId : orderIds) {
                awaitValue(() -> orderJson(portOne, orderId),
                        json -> JsonPath.<String>read(json, "$.supplierRefs.phoneNumber") != null);
            }

            // Each order provisioned exactly once, no matter which instance did the work.
            SUPPLIER.verify(ORDERS, postRequestedFor(urlPathEqualTo(CUSTOMERS_PATH)));
            SUPPLIER.verify(ORDERS, postRequestedFor(urlPathEqualTo(SUBSCRIPTIONS_PATH)));
            SUPPLIER.verify(ORDERS, postRequestedFor(urlPathEqualTo(USERS_PATH)));
            SUPPLIER.verify(ORDERS, postRequestedFor(urlPathEqualTo(NUMBERS_PATH)));
            SUPPLIER.verify(ORDERS, postRequestedFor(urlPathEqualTo(ACTIVATIONS_PATH)));

            // Callbacks land on whichever instance the load balancer picked, which is not
            // necessarily the one that started the process - and must not need to be.
            for (int i = 0; i < orderIds.size(); i++) {
                deliverActivationCallback(i % 2 == 0 ? portTwo : portOne, orderIds.get(i));
            }

            for (String orderId : orderIds) {
                awaitValue(() -> orderJson(portTwo, orderId),
                        json -> "completed".equals(JsonPath.<String>read(json, "$.state")));
            }

            SUPPLIER.verify(ORDERS, postRequestedFor(urlPathEqualTo(SHIPMENTS_PATH)));
        } finally {
            one.close();
            two.close();
        }
    }

    @Test
    @DisplayName("work in flight on a dying instance is picked up by the surviving one")
    void survivingInstanceTakesOverAbandonedWork() throws Exception {
        ConfigurableApplicationContext one = startInstance();
        ConfigurableApplicationContext two = startInstance();

        try {
            int portOne = portOf(one);
            int portTwo = portOf(two);

            String orderId = submitOrder(portOne);
            awaitValue(() -> orderJson(portOne, orderId),
                    json -> JsonPath.<String>read(json, "$.supplierRefs.phoneNumber") != null);

            // Kill the instance that has been doing the work. In a rolling deployment this is
            // simply what happens to whichever pod goes first.
            one.close();
            one = null;

            deliverActivationCallback(portTwo, orderId);

            String finished = awaitValue(() -> orderJson(portTwo, orderId),
                    json -> "completed".equals(JsonPath.<String>read(json, "$.state")));

            assertThat(JsonPath.<String>read(finished, "$.fulfilment.shipmentStatus"))
                    .isEqualTo("delivered");
            SUPPLIER.verify(1, postRequestedFor(urlPathEqualTo(CUSTOMERS_PATH)));
            SUPPLIER.verify(1, postRequestedFor(urlPathEqualTo(SHIPMENTS_PATH)));
        } finally {
            if (one != null) {
                one.close();
            }
            two.close();
        }
    }
}
