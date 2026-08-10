package com.example.sil;

import static org.assertj.core.api.Assertions.assertThat;

import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * The fast counterpart of {@link EngineBootstrapIT}: same engine, in-memory H2, no Docker.
 * From Phase 2 onwards the BPMN process tests live in this lane, which keeps the edit-run loop
 * to a couple of seconds. The Postgres integration lane stays as the thing that has to be true.
 */
@SpringBootTest(properties = {
        "spring.datasource.url=jdbc:h2:mem:sil;DB_CLOSE_DELAY=-1;DB_CLOSE_ON_EXIT=FALSE",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        // Timers and async jobs are driven explicitly in process tests, not by a background
        // thread, so results are deterministic.
        "flowable.async-executor-activate=false"
})
class EngineBootstrapH2Test {

    @Autowired
    private RepositoryService repositoryService;

    @Autowired
    private RuntimeService runtimeService;

    @Test
    @DisplayName("auto-deploys the process definitions found on the classpath")
    void deploysProcessDefinitionsOnStartup() {
        assertThat(repositoryService.createProcessDefinitionQuery()
                .processDefinitionKey("serviceOrder")
                .latestVersion()
                .singleResult())
                .as("every BPMN file under resources/processes should be deployed at startup")
                .isNotNull();

        assertThat(runtimeService.createProcessInstanceQuery().count())
                .as("deploying a definition must not start anything by itself")
                .isZero();
    }
}
