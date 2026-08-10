package com.example.sil.shared.correlation;

import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.util.StringUtils;

/**
 * Reads the correlation id of the current call. Backed by the MDC that
 * {@link CorrelationIdFilter} populates, so it works in controllers, services and - once the
 * workflow engine arrives - inside job executor threads that copy the MDC across.
 */
public final class CorrelationContext {

    private CorrelationContext() {}

    /** Current correlation id, or a fresh one when the code runs outside a request (e.g. a job). */
    public static String currentOrNew() {
        String value = MDC.get(CorrelationIdFilter.MDC_KEY);
        return StringUtils.hasText(value) ? value : UUID.randomUUID().toString();
    }
}
