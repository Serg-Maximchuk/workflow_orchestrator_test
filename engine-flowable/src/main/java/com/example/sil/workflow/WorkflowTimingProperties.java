package com.example.sil.workflow;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Timings the process model reads as expressions rather than hard-coding.
 *
 * <p>A timer literal in the BPMN would mean redeploying the model - and creating a new definition
 * version - to change how often a shipment is polled. Passing it in as a process variable keeps
 * that an operational decision. It also makes a demo possible: the same model that waits six hours
 * in production can wait five seconds locally.
 *
 * <p>The value is read when the instance starts, so orders already in flight keep the interval they
 * began with, which is the same rule that governs definition versions.
 */
@ConfigurationProperties(prefix = "sil.workflow")
public record WorkflowTimingProperties(
        @DefaultValue("PT6H") Duration shipmentPollDelay) {}
