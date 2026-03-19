//package com.example.order_service.scheduler;
//
//import com.example.events.dtos.CreateOrder;
//import com.example.events.dtos.OrderResponse;
//import com.example.order_service.entity.Order;
//import com.example.order_service.repository.OrderRepository;
//import com.google.common.cache.Cache;
//import org.slf4j.Logger;
//import org.slf4j.LoggerFactory;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.kafka.core.KafkaTemplate;
//import org.springframework.scheduling.annotation.Scheduled;
//import org.springframework.stereotype.Component;
//
//import java.time.Instant;
//import java.util.Arrays;
//import java.util.HashSet;
//
//@Component("orderTaskScheduler")
//public class TaskScheduler {
//
//    @Autowired
//    private Cache<String, CreateOrder> guavaCache;
//    @Autowired
//    private OrderRepository orderRepository;
//    @Autowired
//    private KafkaTemplate<String, OrderResponse> kafkaTemplate;
//
//    private static final Logger logger = LoggerFactory.getLogger("DB_OPERATIONS");
//
//    @Scheduled(fixedDelay = 1000)
//    public void runjob()
//    {
//        HashSet<String> keys = new HashSet<>();
//        for(String key : guavaCache.asMap().keySet())
//        {
//            CreateOrder order = guavaCache.asMap().get(key);
//            Order insertorder = new Order(order.getClientid(),order.getItemIds(), order.getQuantity(), order.getAmount());
//            Order temp = orderRepository.save(insertorder);
//            logger.info("correlationId: {}, eventType: {},timestamp:{}, orderId: {}, clientId: {}, itemsIds: {}, quantity: {}, amount: {}, status: {}",
//                    key,
//                    "CreateOrder",
//                    Instant.now(),
//                    temp.getOrderid(),
//                    temp.getClientId(),
//                    temp.getItemsids() != null ? Arrays.toString(temp.getItemsids()) : "[]",
//                    temp.getQuantity() != null ? Arrays.toString(temp.getQuantity()) : "[]",
//                    temp.getAmount(),
//                    temp.getStatus());
//            kafkaTemplate.send("coor-service",new OrderResponse(order.getCorrelationId(),true,temp.getOrderid()));
//            keys.add(key);
//        }
//        for(String key : keys)
//        {
//            guavaCache.invalidate(key);
//        }
//    }
//}

package com.example.order_service.scheduler;

import com.example.events.dtos.CreateOrder;
import com.example.events.dtos.OrderResponse;
import com.example.order_service.entity.Order;
import com.example.order_service.repository.OrderRepository;
import com.google.common.cache.Cache;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Arrays;
import java.util.HashSet;

@Component("orderTaskScheduler")
public class TaskScheduler {

    @Autowired
    private Cache<String, CreateOrder> guavaCache;
    @Autowired
    private OrderRepository orderRepository;
    @Autowired
    private KafkaTemplate<String, OrderResponse> kafkaTemplate;
    @Autowired
    private Tracer tracer; // Manual tracer injection

    private static final Logger logger = LoggerFactory.getLogger("DB_OPERATIONS");

    @Scheduled(fixedDelay = 1000)
    public void runjob() {
        HashSet<String> keys = new HashSet<>();
        for (String key : guavaCache.asMap().keySet()) {
            CreateOrder orderDto = guavaCache.asMap().get(key);

            // START MANUAL SPAN FOR LOCAL DB TRANSACTION
            Span span = this.tracer.nextSpan().name("2pc-local-order-create");
            try (Tracer.SpanInScope ws = this.tracer.withSpan(span.start())) {
                span.tag("correlationId", key);

                Order insertorder = new Order(orderDto.getClientid(), orderDto.getItemIds(), orderDto.getQuantity(), orderDto.getAmount());
                Order temp = orderRepository.save(insertorder);

                logger.info("correlationId: {}, eventType: {}, orderId: {}, status: {}",
                        key, "CreateOrder", temp.getOrderid(), temp.getStatus());

                kafkaTemplate.send("coor-service", new OrderResponse(orderDto.getCorrelationId(), true, temp.getOrderid()));
                keys.add(key);
            } finally {
                span.end();
            }
        }
        for (String key : keys) {
            guavaCache.invalidate(key);
        }
    }
}