package com.example.order_service.services;

import com.example.events.dtos.*;
import com.example.order_service.entity.Order;
import com.example.order_service.repository.OrderRepository;
import com.google.common.cache.Cache;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Arrays;

@Service
@KafkaListener(topics="order-service")
public class OrderService {

    @Autowired
    private Cache<String, CreateOrder> guavaCache;

    @Autowired
    private KafkaTemplate<String, OrderResponse> responsetemplate;

    @Autowired
    private OrderRepository orderRepository;
    private static final Logger logger = LoggerFactory.getLogger("DB_OPERATIONS");


    @KafkaHandler
    public void CreateOrderListener(CreateOrder response)
    {
        guavaCache.put(response.getCorrelationId(), response);
    }

    @KafkaHandler
    public void CancelEventListener(CancelOrder response)
    {
        String id = response.getCorrelationId();
        guavaCache.invalidate(id);
    }

    @KafkaHandler
    public void FinalizeEventListener(FinalizeOrder response)
    {
          Order order = orderRepository.findById(response.getOrder_id()).orElse(null);
          order.setStatus(ORDER_STATUS.ORDER_COMPLETED.toString());
          orderRepository.save(order);
        logger.info("correlationId: {}, eventType: {},timestamp:{}, orderId: {}, clientId: {}, itemsIds: {}, quantity: {}, amount: {}, status: {}",
                response.getCorrelationid(),
                "FinalizeOrder",
                Instant.now(),
                order.getOrderid(),
                order.getClientId(),
                order.getItemsids() != null ? Arrays.toString(order.getItemsids()) : "[]",
                order.getQuantity() != null ? Arrays.toString(order.getQuantity()) : "[]",
                order.getAmount(),
                order.getStatus());
          responsetemplate.send("coor-service",new OrderResponse(response.getCorrelationid(),true,response.getOrder_id()));

    }
}
