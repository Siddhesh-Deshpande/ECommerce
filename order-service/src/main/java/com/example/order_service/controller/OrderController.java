package com.example.order_service.controller;

import com.example.order_service.entity.Order;
import com.example.order_service.repository.OrderRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/orders")
public class OrderController {

    private final OrderRepository orderRepository;

    public OrderController(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @GetMapping("/by-correlation/{correlationId}")
    public ResponseEntity<OrderStatusResponse> getOrderByCorrelationId(@PathVariable String correlationId) {
        return orderRepository.findByCorrelationId(correlationId)
                .map(this::toStatusResponse)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    @GetMapping("/{orderId}/status")
    public ResponseEntity<OrderStatusResponse> getOrderStatus(@PathVariable Integer orderId) {
        return orderRepository.findById(orderId)
                .map(this::toStatusResponse)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private OrderStatusResponse toStatusResponse(Order order) {
        return new OrderStatusResponse(order.getOrderid(), order.getCorrelationId(), order.getStatus());
    }

    public record OrderStatusResponse(Integer orderId, String correlationId, String status) {
    }
}
