package com.example.order_service.metrics;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

@Component
public class ApplicationReadyTimestamp {

    private static final AtomicLong mainEnteredTimestampMillis = new AtomicLong(0);
    private final AtomicLong timestampMillis = new AtomicLong(0);

    public ApplicationReadyTimestamp(MeterRegistry meterRegistry) {
        Gauge.builder("application.main.timestamp", mainEnteredTimestampMillis, AtomicLong::get)
                .description("Unix epoch timestamp in milliseconds when application main() was entered")
                .baseUnit("milliseconds")
                .register(meterRegistry);
        Gauge.builder("application.ready.timestamp", timestampMillis, AtomicLong::get)
                .description("Unix epoch timestamp in milliseconds when this listener handled ApplicationReadyEvent")
                .baseUnit("milliseconds")
                .register(meterRegistry);
    }

    public static void markMainEntered() {
        mainEnteredTimestampMillis.compareAndSet(0, System.currentTimeMillis());
    }

    @EventListener(ApplicationReadyEvent.class)
    public void markReady() {
        timestampMillis.set(System.currentTimeMillis());
    }
}
