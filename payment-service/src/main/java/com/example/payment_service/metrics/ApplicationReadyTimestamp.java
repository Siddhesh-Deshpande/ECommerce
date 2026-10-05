package com.example.payment_service.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
public class ApplicationReadyTimestamp {

    private final AtomicLong timestampMillis = new AtomicLong(0);

    public ApplicationReadyTimestamp(MeterRegistry meterRegistry) {
        Gauge.builder("application.ready.timestamp", timestampMillis, AtomicLong::get)
                .description("Unix epoch timestamp in milliseconds when this listener handled ApplicationReadyEvent")
                .baseUnit("milliseconds")
                .register(meterRegistry);
    }

    @EventListener(ApplicationReadyEvent.class)
    public void markReady() {
        timestampMillis.set(System.currentTimeMillis());
    }
}
