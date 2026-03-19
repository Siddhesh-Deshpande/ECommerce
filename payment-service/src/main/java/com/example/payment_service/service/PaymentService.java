//package com.example.payment_service.service;
//
//import com.example.events.dtos.ChargeMoney;
//import com.example.events.dtos.CreateOrder;
//import com.example.events.dtos.ReleaseFunds;
//import com.example.events.dtos.ReservePayment;
//import com.google.common.cache.Cache;
//import org.springframework.beans.factory.annotation.Autowired;
//import org.springframework.kafka.annotation.KafkaHandler;
//import org.springframework.kafka.annotation.KafkaListener;
//import org.springframework.stereotype.Service;
//
//@Service
//@KafkaListener(topics = "payment-service")
//public class PaymentService {
//
//    @Autowired
//    private Cache<String, ReservePayment> guavacache;
//
//    @KafkaHandler
//    public void reservefunds(ReservePayment payments)
//    {
//        payments.setStatus(0);
//        guavacache.asMap().put(payments.getCorrelationId(),  payments);
//    }
//
//    @KafkaHandler
//    public void releasefunds(ReleaseFunds payments)
//    {
//        if(guavacache.asMap().containsKey(payments.getCorrelationid()))
//        {
//            guavacache.invalidate(payments.getCorrelationid());
//        }
//    }
//    @KafkaHandler
//    public void deductmoney(ChargeMoney payments)
//    {
//        if(guavacache.asMap().containsKey(payments.getCorrelationid()))
//        {
//            guavacache.asMap().get(payments.getCorrelationid()).setStatus(1);
//        }
//    }
//}

package com.example.payment_service.service;

import com.example.events.dtos.ChargeMoney;
import com.example.events.dtos.ReleaseFunds;
import com.example.events.dtos.ReservePayment;
import com.google.common.cache.Cache;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.annotation.KafkaHandler;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Service;

@Service
@KafkaListener(topics = "payment-service")
public class PaymentService {

    @Autowired
    private Cache<String, ReservePayment> guavacache;

    @Autowired
    private Tracer tracer; // Manual tracer injection

    @KafkaHandler
    public void reservefunds(ReservePayment payments) {
        Span span = this.tracer.nextSpan().name("payment-receive-reserve");
        try (Tracer.SpanInScope ws = this.tracer.withSpan(span.start())) {
            span.tag("correlationId", payments.getCorrelationId());

            payments.setStatus(0);
            guavacache.asMap().put(payments.getCorrelationId(), payments);
        } finally {
            span.end();
        }
    }

    @KafkaHandler
    public void releasefunds(ReleaseFunds payments) {
        Span span = this.tracer.nextSpan().name("payment-receive-release");
        try (Tracer.SpanInScope ws = this.tracer.withSpan(span.start())) {
            span.tag("correlationId", payments.getCorrelationid());

            if(guavacache.asMap().containsKey(payments.getCorrelationid())) {
                guavacache.invalidate(payments.getCorrelationid());
            }
        } finally {
            span.end();
        }
    }

    @KafkaHandler
    public void deductmoney(ChargeMoney payments) {
        Span span = this.tracer.nextSpan().name("payment-receive-deduct");
        try (Tracer.SpanInScope ws = this.tracer.withSpan(span.start())) {
            span.tag("correlationId", payments.getCorrelationid());

            if(guavacache.asMap().containsKey(payments.getCorrelationid())) {
                guavacache.asMap().get(payments.getCorrelationid()).setStatus(1);
            }
        } finally {
            span.end();
        }
    }
}