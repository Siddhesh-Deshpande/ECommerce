package com.example.coordinator.scheduler;

import com.example.coordinator.services.CoordinatorWorkflowService;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;

@Component
public class CoordinatorRecoveryScheduler {
    private final CoordinatorWorkflowService workflowService;
    private final long timeoutMillis;
    private final long decisionRetryMillis;

    public CoordinatorRecoveryScheduler(CoordinatorWorkflowService workflowService,
                                        @Value("${coordinator.workflow.timeout-ms:120000}") long timeoutMillis,
                                        @Value("${coordinator.workflow.decision-retry-ms:15000}") long decisionRetryMillis) {
        this.workflowService = workflowService;
        this.timeoutMillis = timeoutMillis;
        this.decisionRetryMillis = decisionRetryMillis;
    }

    @Scheduled(fixedDelayString = "${coordinator.workflow.recovery-interval-ms:5000}")
    public void abortExpiredPreparations() {
        workflowService.abortExpiredPreparations(timeoutMillis);
        workflowService.resendStaleDecisions(decisionRetryMillis);
    }
}
