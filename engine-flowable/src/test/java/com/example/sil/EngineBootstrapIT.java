package com.example.sil;

import static org.assertj.core.api.Assertions.assertThat;

import com.example.sil.support.AbstractPostgresIntegrationTest;
import org.flowable.engine.ProcessEngine;
import org.flowable.engine.RepositoryService;
import org.flowable.engine.RuntimeService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Phase 0 acceptance test: the application boots against a real Postgres, the embedded Flowable
 * engine comes up inside the same Spring context, and it owns its schema in the same database as
 * the business data. That co-location is the whole reason this project uses an embedded engine.
 */
class EngineBootstrapIT extends AbstractPostgresIntegrationTest {

    @Autowired
    private ProcessEngine processEngine;

    @Autowired
    private RepositoryService repositoryService;

    @Autowired
    private RuntimeService runtimeService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private TestRestTemplate restTemplate;

    @Test
    @DisplayName("the Flowable engine is embedded in the Spring context")
    void engineIsEmbedded() {
        assertThat(processEngine).isNotNull();
        assertThat(repositoryService).isNotNull();
        assertThat(runtimeService).isNotNull();
        assertThat(runtimeService.createProcessInstanceQuery().count()).isZero();
    }

    @Test
    @DisplayName("Flowable created its schema in the application's own database")
    void engineSchemaLivesInTheApplicationDatabase() {
        Long runtimeTables = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name LIKE 'act_%'",
                Long.class);

        assertThat(runtimeTables)
                .as("Flowable ACT_* tables should exist alongside the business schema")
                .isNotNull()
                .isGreaterThan(0L);

        assertThat(tableExists("act_ru_execution")).isTrue();
        assertThat(tableExists("act_ru_job")).isTrue();
        assertThat(tableExists("act_ru_deadletter_job")).isTrue();
        assertThat(tableExists("act_hi_procinst")).isTrue();
    }

    @Test
    @DisplayName("the async executor is running, so timers and async continuations will fire")
    void asyncExecutorIsActive() {
        assertThat(processEngine.getProcessEngineConfiguration().getAsyncExecutor().isActive())
                .isTrue();
    }

    @Test
    @DisplayName("actuator reports the application as healthy")
    void actuatorHealthIsUp() {
        String body = restTemplate.getForObject("/actuator/health", String.class);
        assertThat(body).contains("\"status\":\"UP\"");
    }

    private boolean tableExists(String tableName) {
        Long count = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'public' AND table_name = ?",
                Long.class,
                tableName);
        return count != null && count > 0;
    }
}
