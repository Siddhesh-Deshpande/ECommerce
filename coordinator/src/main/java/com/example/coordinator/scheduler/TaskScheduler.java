//package com.example.coordinator.scheduler;
//
//import com.example.coordinator.entity.Order;
//import com.example.events.dtos.*;
//import com.google.common.cache.Cache;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.kafka.core.KafkaTemplate;
//import org.springframework.scheduling.annotation.Scheduled;
//import org.springframework.stereotype.Component;
//
//import java.util.HashSet;
//
//@Component("coordinatorTaskScheduler")
//public class TaskScheduler {
//
//    // Runs every 1 second
//    @Autowired
//    private Cache<String, Order> guavaCache;
//
//    @Autowired
//    private KafkaTemplate<String, CreateOrder> ordertemplate;
//
//    @Autowired
//    private KafkaTemplate<String, ReserveItems> inventorytemplate;
//
//    @Autowired
//    private KafkaTemplate<String, ReservePayment> paymenttemplate;
//
//    @Autowired
//    private KafkaTemplate<String, FinalizeOrder> finalizeordertemplate;
//
//    @Autowired
//    private KafkaTemplate<String, DeductItems> deductitemtemplate;
//
//    @Autowired
//    private KafkaTemplate<String,ChargeMoney>  chargeMoneytemplate;
//
//    @Scheduled(fixedDelay = 1000)
//    public void InitiateFirstPhase() { //only pick up phase 0 orders and send the events
//        for (String key : guavaCache.asMap().keySet()) {
//            Order order = guavaCache.asMap().get(key);
//            if (order.getPhase() == 0)
//            {
//
//                Integer [] items = new Integer[order.getItems().size()];
//                Integer [] quantity = new Integer[order.getItems().size()];
//                int sum = 0;
//                for (int i = 0; i < order.getItems().size(); i++) {
//                    sum += order.getItems().get(i).getQuantity()*order.getItems().get(i).getPrice();
//                    items[i]=order.getItems().get(i).getId();
//                    quantity[i] = order.getItems().get(i).getQuantity();
//                }
//
//                CreateOrder orderevent = new CreateOrder(
//                        key,
//                        items,
//                        quantity,
//                        order.getClientid(),
//                        sum
//                );
//
//                ReserveItems itemevent = new ReserveItems(
//                        key,
//                        items,
//                        quantity
//                );
//
//                ReservePayment paymentevent = new ReservePayment(
//                        key,
//                        order.getClientid(),
//                        sum
//                );
//
//                ordertemplate.send("order-service", orderevent);
//                inventorytemplate.send("inventory-service", itemevent);
//                paymenttemplate.send("payment-service", paymentevent);
//                System.out.println("Prepare Phase Started");
//                order.setPhase(1);
//            }
//        }
//    }
//    @Scheduled(fixedDelay = 1000)
//    public void InitiateSecondPhase() {
//        HashSet<String> keys = new HashSet<>();
//        for (String key : guavaCache.asMap().keySet()) {
//            Order order = guavaCache.asMap().get(key);
//            if(order.getResponses().size()==3 && order.getPhase() == 1) {
//                if (order.getResponses().get(0) && order.getResponses().get(1) && order.getResponses().get(2))
//                {
//                    //Commit phase starts
//                    finalizeordertemplate.send("order-service", new FinalizeOrder(key,order.getOrder_id()));
//                    deductitemtemplate.send("inventory-service", new DeductItems(key));
//                    chargeMoneytemplate.send("payment-service", new ChargeMoney(key));
//                    keys.add(key);
//                    System.out.println("Order Placed Successfully! Congrats.");
//                }
//            }
//
//        }
//        for (String key : keys) {
//            guavaCache.invalidate(key);
//        }
//    }
//}
package com.example.coordinator.scheduler;

import com.example.coordinator.entity.Order;
import com.example.events.dtos.*;
import com.google.common.cache.Cache;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.HashSet;

@Component("coordinatorTaskScheduler")
public class TaskScheduler {

    @Autowired
    private Cache<String, Order> guavaCache;

    @Autowired
    private Tracer tracer;

    @Autowired
    private KafkaTemplate<String, CreateOrder> ordertemplate;
    @Autowired
    private KafkaTemplate<String, ReserveItems> inventorytemplate;
    @Autowired
    private KafkaTemplate<String, ReservePayment> paymenttemplate;
    @Autowired
    private KafkaTemplate<String, FinalizeOrder> finalizeordertemplate;
    @Autowired
    private KafkaTemplate<String, DeductItems> deductitemtemplate;
    @Autowired
    private KafkaTemplate<String, ChargeMoney> chargeMoneytemplate;

    @Scheduled(fixedDelay = 1000)
    public void InitiateFirstPhase() {
        for (String key : guavaCache.asMap().keySet()) {
            Order order = guavaCache.asMap().get(key);
            if (order.getPhase() == 0) {
                // START MANUAL SPAN FOR PREPARE PHASE
                Span prepareSpan = this.tracer.nextSpan().name("2pc-prepare-phase");
                try (Tracer.SpanInScope ws = this.tracer.withSpan(prepareSpan.start())) {
                    prepareSpan.tag("correlationId", key);

                    Integer[] items = new Integer[order.getItems().size()];
                    Integer[] quantity = new Integer[order.getItems().size()];
                    int sum = 0;
                    for (int i = 0; i < order.getItems().size(); i++) {
                        sum += order.getItems().get(i).getQuantity() * order.getItems().get(i).getPrice();
                        items[i] = order.getItems().get(i).getId();
                        quantity[i] = order.getItems().get(i).getQuantity();
                    }

                    ordertemplate.send("order-service", new CreateOrder(key, items, quantity, order.getClientid(), sum));
                    inventorytemplate.send("inventory-service", new ReserveItems(key, items, quantity));
                    paymenttemplate.send("payment-service", new ReservePayment(key, order.getClientid(), sum));

                    System.out.println("Prepare Phase Started for ID: " + key);
                    order.setPhase(1);
                } finally {
                    prepareSpan.end();
                }
            }
        }
    }

    @Scheduled(fixedDelay = 1000)
    public void InitiateSecondPhase() {
        HashSet<String> keysToRemove = new HashSet<>();
        for (String key : guavaCache.asMap().keySet()) {
            Order order = guavaCache.asMap().get(key);

            if (order.getResponses().size() == 3 && order.getPhase() == 1) {
                // START MANUAL SPAN FOR COMMIT PHASE
                Span commitSpan = this.tracer.nextSpan().name("2pc-commit-phase");
                try (Tracer.SpanInScope ws = this.tracer.withSpan(commitSpan.start())) {
                    commitSpan.tag("correlationId", key);

                    if (order.getResponses().get(0) && order.getResponses().get(1) && order.getResponses().get(2)) {
                        finalizeordertemplate.send("order-service", new FinalizeOrder(key, order.getOrder_id()));
                        deductitemtemplate.send("inventory-service", new DeductItems(key));
                        chargeMoneytemplate.send("payment-service", new ChargeMoney(key));

                        keysToRemove.add(key);
                        System.out.println("Order Placed Successfully! ID: " + key);
                    }
                    // Add Else logic here for Rollback phase if needed
                } finally {
                    commitSpan.end();
                }
            }
        }
        for (String key : keysToRemove) {
            guavaCache.invalidate(key);
        }
    }
}