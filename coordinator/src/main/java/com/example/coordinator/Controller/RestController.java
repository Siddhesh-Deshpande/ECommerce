//package com.example.coordinator.Controller;
//
//import com.example.coordinator.entity.Order;
//import com.google.common.cache.Cache;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.web.bind.annotation.PostMapping;
//import org.springframework.web.bind.annotation.RequestBody;
//import org.springframework.web.bind.annotation.RequestMapping;
//
//import java.util.Map;
//import java.util.UUID;
//
//@org.springframework.web.bind.annotation.RestController
//@RequestMapping("/ecomm")
//public class RestController {
//
//    @Autowired
//    private Cache<String, Order> guavaCache; //usually a redis cache in real world
//
//    @PostMapping("/order")
//    public void SendOrder(@RequestBody Order order) //in real world just put in redis cache
//    {
//        guavaCache.put(UUID.randomUUID().toString(), order);
//    }
//
//}
package com.example.coordinator.Controller;

import com.example.coordinator.entity.Order;
import com.google.common.cache.Cache;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;

import java.util.UUID;
@org.springframework.web.bind.annotation.RestController
@RequestMapping("/ecomm")
public class RestController {

    @Autowired
    private Cache<String, Order> guavaCache;

    @Autowired
    private Tracer tracer; // Injected to start the very first span

    @PostMapping("/order")
    public void SendOrder(@RequestBody Order order) {
        // 1. Generate the Correlation ID at the entry point
        String correlationId = UUID.randomUUID().toString();

        // 2. Start the Root Span for the entire 2PC process
        Span initialSpan = this.tracer.nextSpan().name("http-receive-order");

        try (Tracer.SpanInScope ws = this.tracer.withSpan(initialSpan.start())) {
            // 3. Tag it so you can find this HTTP call in Jaeger
            initialSpan.tag("correlationId", correlationId);

            System.out.println("REST Endpoint hit. Generated CID: " + correlationId);

            // 4. Store in cache (The Scheduler will pick it up from here)
            guavaCache.put(correlationId, order);

        } finally {
            // 5. End the span so it exports to Jaeger
            initialSpan.end();
        }
    }
}