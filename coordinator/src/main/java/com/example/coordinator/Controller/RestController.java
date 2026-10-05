package com.example.coordinator.Controller;

import com.example.coordinator.entity.Item;
import com.example.coordinator.services.CoordinatorWorkflowService;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.List;

@org.springframework.web.bind.annotation.RestController
@RequestMapping("/ecomm")
public class RestController {
    private final CoordinatorWorkflowService workflowService;
    private final Tracer tracer;

    public RestController(CoordinatorWorkflowService workflowService, Tracer tracer) {
        this.workflowService = workflowService;
        this.tracer = tracer;
    }

    @PostMapping("/order")
    public ResponseEntity<OrderSubmissionResponse> sendOrder(@RequestBody OrderSubmissionRequest request) {
        Span span = tracer.nextSpan().name("http-receive-order");
        try (Tracer.SpanInScope ignored = tracer.withSpan(span.start())) {
            span.tag("clientId", String.valueOf(request.clientid()));
            return ResponseEntity.ok(new OrderSubmissionResponse(workflowService.submit(request)));
        } finally {
            span.end();
        }
    }

    @GetMapping("/order/{correlationId}")
    public ResponseEntity<WorkflowStatusResponse> getWorkflow(@PathVariable String correlationId) {
        return workflowService.findStatus(correlationId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    public record OrderSubmissionRequest(Integer clientid, List<Item> items) {}
    public record OrderSubmissionResponse(String correlationId) {}
    public record WorkflowStatusResponse(String correlationId, String status, Integer orderId) {}
}
