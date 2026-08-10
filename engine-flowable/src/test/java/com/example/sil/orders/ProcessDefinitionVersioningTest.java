package com.example.sil.orders;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.repository.Deployment;
import org.flowable.engine.repository.ProcessDefinition;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.result.MockMvcResultMatchers;

/**
 * What happens to orders that are already in flight when the process model changes.
 *
 * <p>This is the question that decides whether a workflow engine is safe to deploy on a Friday. A
 * process instance is bound to the definition <em>version</em> it started on: deploying a new model
 * does not migrate anything, in-flight orders finish under the rules they began with, and only new
 * orders pick up the change. Getting this wrong - assuming a deploy rewrites running work - is how
 * a release corrupts orders that were mid-journey.
 */
class ProcessDefinitionVersioningTest extends AbstractOrderWorkflowTest {

    @Autowired
    private RepositoryService repositoryService;

    private String extraDeploymentId;

    @BeforeEach
    void resetSupplierAndEngine() {
        supplier.resetAll();
        stubHappyPathSupplier();
        deleteRunningProcessInstances();
    }

    @AfterEach
    void removeExtraDeployment() {
        if (extraDeploymentId != null) {
            // Cascade, because by now there are instances running on this version.
            repositoryService.deleteDeployment(extraDeploymentId, true);
            extraDeploymentId = null;
        }
    }

    @Test
    @DisplayName("an in-flight order keeps running the definition version it started on")
    void inFlightOrdersStayOnTheirOriginalVersion() throws Exception {
        int versionBefore = latestVersion();
        String inFlightOrderId = submitOrder();
        String inFlightDefinitionId = definitionIdOf(inFlightOrderId);

        // Deploy a second version of the same process while the first order sits at its first step.
        extraDeploymentId = deployShorterVersion();

        assertThat(latestVersion())
                .as("a redeploy of the same process key produces a new version")
                .isEqualTo(versionBefore + 1);

        assertThat(definitionIdOf(inFlightOrderId))
                .as("the running instance must not be migrated by a deployment")
                .isEqualTo(inFlightDefinitionId);

        // A new order picks up the new version.
        String newOrderId = submitOrder();
        assertThat(definitionIdOf(newOrderId)).isNotEqualTo(inFlightDefinitionId);
        assertThat(versionOf(definitionIdOf(newOrderId))).isEqualTo(versionBefore + 1);

        // And the old instance still finishes through the old model: five steps, four supplier calls.
        executeAllJobs();
        assertThat(fetchOrderState(inFlightOrderId)).isEqualTo("completed");
    }

    /**
     * A trimmed model under the same process key: customer creation only, then done. Different
     * enough that an accidentally migrated instance would be obvious.
     */
    private String deployShorterVersion() {
        String bpmn = """
                <?xml version="1.0" encoding="UTF-8"?>
                <definitions xmlns="http://www.omg.org/spec/BPMN/20100524/MODEL"
                             xmlns:flowable="http://flowable.org/bpmn"
                             targetNamespace="http://example.com/sil">
                  <process id="serviceOrder" name="VOIP Service Order Fulfilment v2" isExecutable="true">
                    <startEvent id="orderSubmitted" name="Order submitted"/>
                    <sequenceFlow id="toCreateCustomer" sourceRef="orderSubmitted" targetRef="createCustomer"/>
                    <serviceTask id="createCustomer" name="Create customer"
                                 flowable:async="true"
                                 flowable:delegateExpression="${createCustomerDelegate}"/>
                    <sequenceFlow id="toCompleteOrder" sourceRef="createCustomer" targetRef="completeOrder"/>
                    <serviceTask id="completeOrder" name="Complete order"
                                 flowable:async="true"
                                 flowable:delegateExpression="${completeOrderDelegate}"/>
                    <sequenceFlow id="toOrderCompleted" sourceRef="completeOrder" targetRef="orderCompleted"/>
                    <endEvent id="orderCompleted" name="Order completed"/>
                  </process>
                </definitions>
                """;

        Deployment deployment = repositoryService.createDeployment()
                .name("serviceOrder v2 (test)")
                .addBytes("serviceOrder.bpmn20.xml", bpmn.getBytes(StandardCharsets.UTF_8))
                .deploy();
        return deployment.getId();
    }

    private int latestVersion() {
        return repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey("serviceOrder")
                .latestVersion()
                .singleResult()
                .getVersion();
    }

    private int versionOf(String processDefinitionId) {
        ProcessDefinition definition = repositoryService.createProcessDefinitionQuery()
                .processDefinitionId(processDefinitionId)
                .singleResult();
        return definition.getVersion();
    }

    private String definitionIdOf(String orderId) {
        return runtimeService.createProcessInstanceQuery()
                .processInstanceBusinessKey(orderId)
                .singleResult()
                .getProcessDefinitionId();
    }

    private String submitOrder() throws Exception {
        String body = mockMvc.perform(MockMvcRequestBuilders.post(ORDERS_URL)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"externalId":"OMS-%s",
                                 "customer":{"name":"Acme Ltd","email":"ops@acme.example"},
                                 "place":{"postcode":"SW1A 1AA"},
                                 "serviceSpecId":"VOIP_BUSINESS",
                                 "speedMbps":100}""".formatted(UUID.randomUUID())))
                .andExpect(MockMvcResultMatchers.status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$.id");
    }

    private String fetchOrderState(String orderId) throws Exception {
        String body = mockMvc.perform(MockMvcRequestBuilders.get(ORDERS_URL + "/" + orderId))
                .andExpect(MockMvcResultMatchers.status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$.state");
    }
}
