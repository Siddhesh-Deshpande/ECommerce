package com.example.coordinator.services;

import com.example.events.dtos.InventoryResponse;
import com.example.events.dtos.OrderResponse;
import com.example.events.dtos.PaymentResponse;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
@KafkaListener(topics = "coor-service")
public class CoordinatorService {
    private final CoordinatorWorkflowService workflowService;
    private final Tracer tracer;

    public CoordinatorService(CoordinatorWorkflowService workflowService, Tracer tracer) {
        this.workflowService = workflowService;
        this.tracer = tracer;
    }

    @KafkaHandler
    public void receiveOrderResult(OrderResponse response) {
        receive(response.getCorrelationId(), "order", response.getStatus(), response.getId(), response.getStage());
    }

    @KafkaHandler
    public void receiveInventoryResult(InventoryResponse response) {
        receive(response.getCorrelationId(), "inventory", response.getStatus(), null, response.getStage());
    }

    @KafkaHandler
    public void receivePaymentResult(PaymentResponse response) {
        receive(response.getCorrelationId(), "payment", response.getStatus(), null, response.getStage());
    }

    private void receive(String correlationId, String participant, boolean success, Integer orderId, String stage) {
        Span span = tracer.nextSpan().name("receive-" + participant + "-" + (stage == null ? "prepare" : stage.toLowerCase()));
        try (Tracer.SpanInScope ignored = tracer.withSpan(span.start())) {
            span.tag("correlationId", correlationId);
            span.tag("participant", participant);
            workflowService.recordParticipantResult(correlationId, participant, success, orderId, stage);
        } finally {
            span.end();
        }
    }
}
