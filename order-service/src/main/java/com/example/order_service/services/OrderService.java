package com.example.order_service.services;

import com.example.events.dtos.CancelOrder;
import com.example.events.dtos.CreateOrder;
import com.example.events.dtos.FinalizeOrder;
import com.example.events.dtos.ORDER_STATUS;
import com.example.events.dtos.OrderResponse;
import com.example.events.outbox.OutboxEventWriter;
import com.example.order_service.entity.Order;
import com.example.order_service.repository.OrderRepository;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@KafkaListener(topics = "order-service")
public class OrderService {
    private final OrderRepository orderRepository;
    private final OutboxEventWriter outbox;
    private final Tracer tracer;

    public OrderService(OrderRepository orderRepository, OutboxEventWriter outbox, Tracer tracer) {
        this.orderRepository = orderRepository;
        this.outbox = outbox;
        this.tracer = tracer;
    }

    @KafkaHandler
    @Transactional
    public void prepareOrder(CreateOrder command) {
        inSpan("order-prepare", command.getCorrelationId(), () -> {
            Order order = orderRepository.findByCorrelationId(command.getCorrelationId()).orElseGet(() ->
                    orderRepository.saveAndFlush(new Order(command.getCorrelationId(), command.getClientid(),
                            command.getItemIds(), command.getQuantity(), command.getAmount())));
            boolean prepared = !ORDER_STATUS.ORDER_CANCELLED.toString().equals(order.getStatus());
            outbox.enqueue("coor-service", command.getCorrelationId(),
                    command.getCorrelationId() + ":vote:order:prepare",
                    new OrderResponse(command.getCorrelationId(), prepared, order.getOrderid(), "PREPARE"));
        });
    }

    @KafkaHandler
    @Transactional
    public void commitOrder(FinalizeOrder command) {
        inSpan("order-commit", command.getCorrelationid(), () -> {
            Order order = orderRepository.findByCorrelationId(command.getCorrelationid()).orElse(null);
            if (order == null) {
                throw new IllegalStateException("Cannot commit missing prepared order " + command.getCorrelationid());
            }
            boolean committed = !ORDER_STATUS.ORDER_CANCELLED.toString().equals(order.getStatus());
            if (committed) {
                order.setStatus(ORDER_STATUS.ORDER_COMPLETED.toString());
                orderRepository.save(order);
            }
            OrderResponse response = new OrderResponse(command.getCorrelationid(), committed, order.getOrderid(), "COMMIT");
            outbox.enqueue("coor-service", command.getCorrelationid(),
                    command.getCorrelationid() + ":ack:order:commit:" + response.getTimestamp().toEpochMilli(), response);
        });
    }

    @KafkaHandler
    @Transactional
    public void abortOrder(CancelOrder command) {
        inSpan("order-abort", command.getCorrelationId(), () -> {
            Order order = orderRepository.findByCorrelationId(command.getCorrelationId()).orElse(null);
            if (order != null && ORDER_STATUS.ORDER_CREATED.toString().equals(order.getStatus())) {
                order.setStatus(ORDER_STATUS.ORDER_CANCELLED.toString());
                orderRepository.save(order);
            }
            boolean aborted = order == null || ORDER_STATUS.ORDER_CANCELLED.toString().equals(order.getStatus());
            OrderResponse response = new OrderResponse(command.getCorrelationId(), aborted,
                    order == null ? null : order.getOrderid(), "ABORT");
            outbox.enqueue("coor-service", command.getCorrelationId(),
                    command.getCorrelationId() + ":ack:order:abort:" + response.getTimestamp().toEpochMilli(), response);
        });
    }

    private void inSpan(String name, String correlationId, Runnable operation) {
        Span span = tracer.nextSpan().name(name);
        try (Tracer.SpanInScope ignored = tracer.withSpan(span.start())) {
            span.tag("correlationId", correlationId);
            operation.run();
        } finally {
            span.end();
        }
    }
}
