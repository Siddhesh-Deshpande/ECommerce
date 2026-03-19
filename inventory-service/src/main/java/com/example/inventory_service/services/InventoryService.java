//package com.example.inventory_service.services;
//
//import com.example.events.dtos.DeductItems;
//import com.example.events.dtos.ReleaseItems;
//import com.example.events.dtos.ReserveItems;
//import com.example.inventory_service.entity.Item;
//import com.example.inventory_service.repository.ItemRepository;
//import com.google.common.cache.Cache;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.kafka.annotation.KafkaHandler;
//import org.springframework.kafka.annotation.KafkaListener;
//import org.springframework.stereotype.Service;
//
//@Service
//@KafkaListener(topics="inventory-service")
//public class InventoryService {
//
//    @Autowired
//    private Cache<String, ReserveItems> guavaCache;
//    @Autowired
//    private ItemRepository itemRepository;
//
//    @KafkaHandler
//    public void ReserveItems(ReserveItems items)
//    {
//        items.setStatus(0);
//        guavaCache.put(items.getCorrelationId(), items);
//    }
//    @KafkaHandler
//    public void deductitems(DeductItems deductItems)
//    {
//        if(guavaCache.asMap().containsKey(deductItems.getCorrelationid()))
//        {
//            guavaCache.asMap().get(deductItems.getCorrelationid()).setStatus(1);
//        }
//    }
//    @KafkaHandler
//    public void releaseitems(ReleaseItems items)
//    {
//       if(guavaCache.asMap().containsKey(items.getCorrelationid()))
//       {
//           guavaCache.invalidate(items.getCorrelationid());
//       }
//    }
//}
package com.example.inventory_service.services;

import com.example.events.dtos.DeductItems;
import com.example.events.dtos.ReleaseItems;
import com.example.events.dtos.ReserveItems;
import com.example.inventory_service.entity.Item;
import com.example.inventory_service.repository.ItemRepository;
import com.google.common.cache.Cache;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
@KafkaListener(topics="inventory-service")
public class InventoryService {

    @Autowired
    private Cache<String, ReserveItems> guavaCache;

    @Autowired
    private Tracer tracer; // Manual tracer injection

    @KafkaHandler
    public void ReserveItems(ReserveItems items) {
        Span span = this.tracer.nextSpan().name("inventory-receive-reserve");
        try (Tracer.SpanInScope ws = this.tracer.withSpan(span.start())) {
            span.tag("correlationId", items.getCorrelationId());

            items.setStatus(0);
            guavaCache.put(items.getCorrelationId(), items);
        } finally {
            span.end();
        }
    }

    @KafkaHandler
    public void deductitems(DeductItems deductItems) {
        Span span = this.tracer.nextSpan().name("inventory-receive-deduct");
        try (Tracer.SpanInScope ws = this.tracer.withSpan(span.start())) {
            span.tag("correlationId", deductItems.getCorrelationid());

            if(guavaCache.asMap().containsKey(deductItems.getCorrelationid())) {
                guavaCache.asMap().get(deductItems.getCorrelationid()).setStatus(1);
            }
        } finally {
            span.end();
        }
    }

    @KafkaHandler
    public void releaseitems(ReleaseItems items) {
        Span span = this.tracer.nextSpan().name("inventory-receive-release");
        try (Tracer.SpanInScope ws = this.tracer.withSpan(span.start())) {
            span.tag("correlationId", items.getCorrelationid());

            if(guavaCache.asMap().containsKey(items.getCorrelationid())) {
                guavaCache.invalidate(items.getCorrelationid());
            }
        } finally {
            span.end();
        }
    }
}